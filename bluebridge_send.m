#import <Foundation/Foundation.h>
#import <IOBluetooth/IOBluetooth.h>
#import <AppKit/AppKit.h>
#import <ImageIO/ImageIO.h>
#import <CoreGraphics/CoreGraphics.h>
#include <unistd.h>

#define MAGIC 0x4242
#define CMD_BATCH_START 0x10
#define CMD_ITEM_START  0x11
#define CMD_CHUNK_DATA  0x12
#define CMD_ITEM_END    0x13
#define CMD_BATCH_END   0x14
#define CMD_ACK         0x20
#define CMD_PING        0x21
#define CMD_ANSWERS     0x30
#define CMD_PROGRESS    0x31
#define CMD_ANS_BEGIN   0x32
#define CMD_ANS_CHUNK   0x33
#define CMD_ANS_END     0x34
#define CMD_FETCH       0x35
#define CMD_CHAT        0x40
#define CMD_CHAT_ANS    0x41
#define CMD_CANCEL      0x42
#define CMD_PAUSE       0x43
#define CMD_RESOLVE_ONE 0x44

#define TYPE_TEXT  0x01
#define TYPE_IMAGE 0x02
#define TYPE_AUDIO 0x03

@interface CBManager : NSObject
- (long)state;
@end

@interface CBClassicPeer : NSObject
- (CBManager *)manager;
@end

@interface IOBluetoothDevice (Private)
- (CBClassicPeer *)classicPeer;
@end

static NSString *formatAnswerObj(id obj);
static NSString *formatReasonObj(id obj);

#define LAST_GOOD_CH_PATH "/tmp/blue_last_ch"

static int readLastGoodChannel(void) {
    NSString *s = [NSString stringWithContentsOfFile:@LAST_GOOD_CH_PATH
                                            encoding:NSUTF8StringEncoding error:nil];
    int ch = [s intValue];
    return (ch > 0 && ch <= 30) ? ch : 0;
}

static void writeLastGoodChannel(int ch) {
    NSString *s = [NSString stringWithFormat:@"%d", ch];
    [s writeToFile:@LAST_GOOD_CH_PATH atomically:YES
          encoding:NSUTF8StringEncoding error:nil];
}

// P2 send-resume: verified 1-based item indices persist in /tmp across the
// master's retry processes, so a retry skips ACKed items (minutes -> seconds).
static NSString *SendResumePath(NSString *batchId) {
    NSMutableString *safe = [NSMutableString string];
    for (NSUInteger i = 0; i < [batchId length]; i++) {
        unichar c = [batchId characterAtIndex:i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-') [safe appendFormat:@"%C", c];
    }
    if ([safe length] == 0) [safe appendString:@"batch"];
    return [@"/tmp" stringByAppendingPathComponent:
            [NSString stringWithFormat:@"blue_sent_%@.json", safe]];
}

static NSString *getAnswersDirectory(void) {
    const char *envDir = getenv("BLUE_ANSWERS_DIR");
    if (envDir && strlen(envDir) > 0) {
        return [NSString stringWithUTF8String:envDir];
    }
    NSString *curr = [[NSFileManager defaultManager] currentDirectoryPath];
    NSString *cand = [curr stringByAppendingPathComponent:@"answers"];
    if ([[NSFileManager defaultManager] fileExistsAtPath:cand]) {
        return cand;
    }
    return [NSHomeDirectory() stringByAppendingPathComponent:@"Documents/blue/answers"];
}

@interface BlueSender : NSObject <IOBluetoothRFCOMMChannelDelegate>
@property (nonatomic, retain) IOBluetoothRFCOMMChannel *channel;
@property (nonatomic, assign) BOOL isOpen;
@property (nonatomic, retain) NSMutableData *rxBuffer;
@property (nonatomic, retain) dispatch_semaphore_t ackSemaphore;
@property (nonatomic, assign) uint8_t lastAckStatus;
@property (nonatomic, assign) uint16_t lastAckId;
@property (nonatomic, retain) NSData *receivedAnswersData;
@property (nonatomic, retain) NSMutableData *answersAccum;
@property (nonatomic, retain) NSData *receivedChatData;
@property (nonatomic, copy) NSString *currentBatchId;
@property (nonatomic, retain) NSMutableArray *liveAnswers;
@property (atomic, retain) NSDate *lastActivityDate;
@end

@implementation BlueSender

- (instancetype)init {
    self = [super init];
    if (self) {
        _isOpen = NO;
        _rxBuffer = [[NSMutableData alloc] init];
        _ackSemaphore = dispatch_semaphore_create(0);
        _lastAckStatus = 0xFF;
        _lastAckId = 0xFFFF;
        _receivedAnswersData = nil;
        _currentBatchId = nil;
        _liveAnswers = [[NSMutableArray alloc] init];
        _lastActivityDate = [NSDate date];
    }
    return self;
}

- (void)rfcommChannelOpenComplete:(IOBluetoothRFCOMMChannel *)rfcommChannel status:(IOReturn)error {
    if (error == kIOReturnSuccess) {
        self.isOpen = YES;
        self.channel = rfcommChannel;
    }
}

- (void)rfcommChannelClosed:(IOBluetoothRFCOMMChannel *)rfcommChannel {
    self.isOpen = NO;
    self.channel = nil;
}

- (void)rfcommChannelData:(IOBluetoothRFCOMMChannel *)rfcommChannel data:(void *)dataPointer length:(size_t)dataLength {
    [self.rxBuffer appendBytes:dataPointer length:dataLength];

    while ([self.rxBuffer length] >= 7) {
        const uint8_t *bytes = (const uint8_t *)[self.rxBuffer bytes];
        uint16_t magic = (bytes[0] << 8) | bytes[1];
        if (magic != MAGIC) {
            [self.rxBuffer replaceBytesInRange:NSMakeRange(0, 1) withBytes:NULL length:0];
            continue;
        }

        uint16_t streamId = (bytes[2] << 8) | bytes[3];
        uint8_t cmd = bytes[4];
        uint16_t pLen = (bytes[5] << 8) | bytes[6];

        if ([self.rxBuffer length] < 7 + pLen) {
            break;
        }

        NSData *payload = [NSData dataWithBytes:(bytes + 7) length:pLen];
        [self.rxBuffer replaceBytesInRange:NSMakeRange(0, 7 + pLen) withBytes:NULL length:0];

        if (cmd == CMD_ACK && [payload length] >= 1) {
            const uint8_t *p = (const uint8_t *)[payload bytes];
            self.lastAckStatus = p[0];
            self.lastAckId = streamId;
            dispatch_semaphore_signal(self.ackSemaphore);
        } else if (cmd == CMD_PROGRESS) {
            self.lastActivityDate = [NSDate date];
            NSError *err = nil;
            NSDictionary *json = [NSJSONSerialization JSONObjectWithData:payload options:0 error:&err];
            if (json) {
                int qNum = [[json objectForKey:@"nomor"] intValue];
                if (qNum == 0 && [json objectForKey:@"num"]) {
                    qNum = [[json objectForKey:@"num"] intValue];
                }
                id rawJaw = [json objectForKey:@"jawaban"] ?: [json objectForKey:@"answer"];
                NSString *jaw = formatAnswerObj(rawJaw);
                id rawAlasan = [json objectForKey:@"alasan"] ?: [json objectForKey:@"reason"];
                NSString *alasan = formatReasonObj(rawAlasan);
                if ([alasan length] > 55) {
                    alasan = [NSString stringWithFormat:@"%@...", [alasan substringToIndex:52]];
                }
                printf("  [Phone AI] Solved Soal #%02d -> Jawaban: %-6s | %s\n", qNum, [jaw UTF8String], [alasan UTF8String]);
                fflush(stdout);

                if (self.currentBatchId) {
                    @synchronized(self.liveAnswers) {
                        [self.liveAnswers addObject:json];
                        NSDictionary *liveDict = @{
                            @"batch_id": self.currentBatchId,
                            @"solved_count": @([self.liveAnswers count]),
                            @"status": @"solving_in_progress",
                            @"answers": self.liveAnswers
                        };
                        NSData *liveData = [NSJSONSerialization dataWithJSONObject:liveDict options:NSJSONWritingPrettyPrinted error:nil];
                        if (liveData) {
                            NSString *answersDir = getAnswersDirectory();
                            [[NSFileManager defaultManager] createDirectoryAtPath:answersDir withIntermediateDirectories:YES attributes:nil error:nil];
                            NSString *liveFile = [NSString stringWithFormat:@"%@/%@_live.json", answersDir, self.currentBatchId];
                            [liveData writeToFile:liveFile atomically:YES];
                        }
                    }
                }
            }
        } else if (cmd == CMD_ANSWERS) {
            self.receivedAnswersData = [NSData dataWithData:payload];
        } else if (cmd == CMD_ANS_BEGIN) {
            self.answersAccum = [NSMutableData data];
        } else if (cmd == CMD_ANS_CHUNK) {
            if (!self.answersAccum) self.answersAccum = [NSMutableData data];
            [self.answersAccum appendData:payload];
        } else if (cmd == CMD_ANS_END) {
            if (self.answersAccum) {
                self.receivedAnswersData = [NSData dataWithData:self.answersAccum];
                self.answersAccum = nil;
            }
        } else if (cmd == CMD_CHAT_ANS) {
            self.receivedChatData = [NSData dataWithData:payload];
        }
    }
}

- (BOOL)sendRawFrameWithId:(uint16_t)streamId cmd:(uint8_t)cmd payload:(NSData *)payload {
    if (!self.isOpen || !self.channel) return NO;
    uint16_t pLen = (uint16_t)[payload length];
    NSMutableData *frame = [NSMutableData dataWithCapacity:7 + pLen];
    uint8_t hdr[7];
    hdr[0] = (MAGIC >> 8) & 0xFF;
    hdr[1] = MAGIC & 0xFF;
    hdr[2] = (streamId >> 8) & 0xFF;
    hdr[3] = streamId & 0xFF;
    hdr[4] = cmd;
    hdr[5] = (pLen >> 8) & 0xFF;
    hdr[6] = pLen & 0xFF;
    [frame appendBytes:hdr length:7];
    if (pLen > 0) {
        [frame appendData:payload];
    }

    @synchronized(self.channel) {
        IOReturn r = [self.channel writeSync:(void *)[frame bytes] length:[frame length]];
        return (r == kIOReturnSuccess);
    }
}

- (BOOL)waitForAckWithId:(uint16_t)streamId timeoutSec:(double)sec {
    NSDate *start = [NSDate date];
    self.lastAckStatus = 255;
    self.lastAckId = 0xFFFF;
    while ((self.lastAckId != streamId || self.lastAckStatus == 255) && [[NSDate date] timeIntervalSinceDate:start] < sec && self.isOpen) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
    }
    return (self.lastAckStatus == 0 && self.lastAckId == streamId);
}

@end

// Helper to compress images safely without WindowServer / GUI context
NSData *optimizeImage(NSData *inData, NSString *ext) {
    if (!inData || [inData length] < 150 * 1024) return inData;

    CGImageSourceRef src = CGImageSourceCreateWithData((__bridge CFDataRef)inData, NULL);
    if (!src) return inData;

    NSDictionary *options = @{
        (id)kCGImageSourceCreateThumbnailFromImageAlways: @YES,
        (id)kCGImageSourceThumbnailMaxPixelSize: @1400,
        (id)kCGImageSourceCreateThumbnailWithTransform: @YES
    };

    CGImageRef thumb = CGImageSourceCreateThumbnailAtIndex(src, 0, (__bridge CFDictionaryRef)options);
    CFRelease(src);
    if (!thumb) return inData;

    NSMutableData *destData = [NSMutableData data];
    CGImageDestinationRef dest = CGImageDestinationCreateWithData((__bridge CFMutableDataRef)destData, CFSTR("public.jpeg"), 1, NULL);
    if (!dest) {
        CGImageRelease(thumb);
        return inData;
    }

    NSDictionary *props = @{
        (id)kCGImageDestinationLossyCompressionQuality: @0.75
    };
    CGImageDestinationAddImage(dest, thumb, (__bridge CFDictionaryRef)props);
    BOOL ok = CGImageDestinationFinalize(dest);
    CFRelease(dest);
    CGImageRelease(thumb);

    if (ok && [destData length] > 0) {
        return [NSData dataWithData:destData];
    }
    return inData;
}

static void printProgress(int itemIdx, int totalItems, NSString *name, size_t sent, size_t total) {
    int percent = (total > 0) ? (int)((sent * 100) / total) : 100;
    int barWidth = 25;
    int filled = (percent * barWidth) / 100;

    printf("\r  [%02d/%02d] %-18.18s [", itemIdx, totalItems, [name UTF8String]);
    for (int i = 0; i < barWidth; i++) {
        if (i < filled) putchar('=');
        else if (i == filled) putchar('>');
        else putchar(' ');
    }
    printf("] %3d%% (%zu/%zu KB)", percent, sent / 1024, total / 1024);
    fflush(stdout);
}

@interface SDPQueryReceiver : NSObject
@property (nonatomic, assign) BOOL done;
@end

@implementation SDPQueryReceiver
- (void)sdpQueryComplete:(IOBluetoothDevice *)device status:(IOReturn)status {
    self.done = YES;
}
@end

static BluetoothRFCOMMChannelID discoverChannelForDevice(IOBluetoothDevice *dev) {
    SDPQueryReceiver *receiver = [[SDPQueryReceiver alloc] init];
    IOReturn r = [dev performSDPQuery:receiver];
    const char *dbgq = getenv("BLUE_DEBUG_SDP");
    if (dbgq && dbgq[0] == '1') printf("  [SDP] performSDPQuery rc=0x%x\n", r);
    if (r == kIOReturnSuccess) {
        NSDate *start = [NSDate date];
        while (!receiver.done && [[NSDate date] timeIntervalSinceDate:start] < 8.0) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
        }
        if (dbgq && dbgq[0] == '1') printf("  [SDP] query done=%d\n", receiver.done);
    }

    NSArray *services = [dev services];
    const char *dbg = getenv("BLUE_DEBUG_SDP");
    if (services) {
        for (IOBluetoothSDPServiceRecord *rec in services) {
            NSString *sName = [rec getServiceName] ?: @"(no-name)";
            BluetoothRFCOMMChannelID chId = 0;
            IOReturn chr = [rec getRFCOMMChannelID:&chId];
            if (dbg && dbg[0] == '1') {
                printf("  [SDP] service='%s' rfcomm=%d (rc=0x%x)\n", [sName UTF8String], chId, chr);
            }
            if ([sName rangeOfString:@"BlueBridge" options:NSCaseInsensitiveSearch].location != NSNotFound) {
                if (chr == kIOReturnSuccess && chId > 0) {
                    return chId;
                }
            }
        }
    }
    return 0;
}

static NSString *safeJoinArray(NSArray *arr, NSString *sep) {
    NSMutableArray *strs = [NSMutableArray arrayWithCapacity:[arr count]];
    for (id el in arr) {
        if ([el isKindOfClass:[NSString class]]) {
            [strs addObject:el];
        } else if ([el isKindOfClass:[NSDictionary class]]) {
            // Matrix/matching rows: render {"index":1,"status":"Benar",...} compactly.
            NSDictionary *d = (NSDictionary *)el;
            id idx = d[@"index"] ?: d[@"nomor"] ?: d[@"number"];
            id st = d[@"status"] ?: d[@"answer"] ?: d[@"option"];
            id tx = d[@"text"] ?: d[@"option_answer"];
            NSMutableArray *bits = [NSMutableArray array];
            if (idx) [bits addObject:[NSString stringWithFormat:@"%@", idx]];
            if (st) [bits addObject:[NSString stringWithFormat:@"%@", st]];
            if (tx) [bits addObject:[NSString stringWithFormat:@"%@", tx]];
            [strs addObject:[bits componentsJoinedByString:@" "]];
        } else if (el) {
            [strs addObject:[NSString stringWithFormat:@"%@", el]];
        }
    }
    return [strs componentsJoinedByString:sep];
}

static NSString *formatAnswerObj(id obj) {
    if (!obj) return @"-";
    if ([obj isKindOfClass:[NSString class]]) return (NSString *)obj;
    if ([obj isKindOfClass:[NSArray class]]) {
        return safeJoinArray((NSArray *)obj, @", ");
    }
    if ([obj isKindOfClass:[NSDictionary class]]) {
        NSMutableArray *parts = [NSMutableArray array];
        NSDictionary *dict = (NSDictionary *)obj;
        NSArray *kSorted = [[dict allKeys] sortedArrayUsingSelector:@selector(compare:)];
        for (id k in kSorted) {
            [parts addObject:[NSString stringWithFormat:@"%@:%@", k, dict[k]]];
        }
        return [parts componentsJoinedByString:@", "];
    }
    return [NSString stringWithFormat:@"%@", obj];
}

static NSString *formatReasonObj(id obj) {
    if (!obj) return @"";
    if ([obj isKindOfClass:[NSString class]]) return (NSString *)obj;
    if ([obj isKindOfClass:[NSArray class]]) return safeJoinArray((NSArray *)obj, @" ");
    return [NSString stringWithFormat:@"%@", obj];
}

int main(int argc, const char * argv[]) {
    @autoreleasepool {
        if (argc < 2) {
            printf("Usage: bluebridge_send [--batch-id ID] [--device MAC] [--channel CH] [--no-wait] [--fetch-answers BID] [--chat TEXT --or-key K --cb-key K] [--cancel-all] [--pause 1|0] [files...]\n");
            return 1;
        }

        NSMutableArray<NSString *> *itemNames = [NSMutableArray array];
        NSMutableArray<NSData *> *itemDatas = [NSMutableArray array];
        NSMutableArray<NSNumber *> *itemTypes = [NSMutableArray array];

        BOOL autoCompress = YES;
        BOOL waitAnswers = YES;
        BOOL isPingMode = NO;
        BOOL isFetchMode = NO;
        BOOL isChatMode = NO;
        BOOL isCancelMode = NO;
        BOOL isPauseMode = NO;
        BOOL isResolveOneMode = NO;
        int resolveOneNum = -1;
        NSString *chatText = nil;
        const char *envOr = getenv("OPENROUTER_KEY");
        NSString *orKey = envOr ? [NSString stringWithUTF8String:envOr] : @"";
        const char *envCb = getenv("ONOMEO_KEY") ?: getenv("CEREBRAS_KEY");
        NSString *cbKey = envCb ? [NSString stringWithUTF8String:envCb] : @"";
        NSString *pauseVal = @"1";
        NSString *customBatchId = nil;
        NSString *baseDir = [[NSProcessInfo processInfo] environment][@"BLUE_DIR"] ?: [[NSFileManager defaultManager] currentDirectoryPath];
        const char *envMac = getenv("TARGET_PHONE_MAC");
        NSString *targetDeviceAddr = envMac ? [NSString stringWithUTF8String:envMac] : nil;
        if (!targetDeviceAddr) {
            NSString *configPath = [baseDir stringByAppendingPathComponent:@"config.json"];
            if ([[NSFileManager defaultManager] fileExistsAtPath:configPath]) {
                NSData *cfgData = [NSData dataWithContentsOfFile:configPath];
                if (cfgData) {
                    NSDictionary *cfg = [NSJSONSerialization JSONObjectWithData:cfgData options:0 error:nil];
                    if (cfg && cfg[@"target_phone_mac"]) {
                        targetDeviceAddr = cfg[@"target_phone_mac"];
                    }
                }
            }
        }
        if (!targetDeviceAddr) {
            targetDeviceAddr = @"00-00-00-00-00-00";
        }
        int maxRetries = 2;
        int targetChannelArg = 0;

        for (int i = 1; i < argc; i++) {
            NSString *arg = [NSString stringWithUTF8String:argv[i]];

            if ([arg isEqualToString:@"--ping"]) {
                isPingMode = YES;
                continue;
            }

            if ([arg isEqualToString:@"--no-compress"]) {
                autoCompress = NO;
                continue;
            }

            if ([arg isEqualToString:@"--no-wait"]) {
                waitAnswers = NO;
                continue;
            }

            if ([arg isEqualToString:@"--batch-id"] && i + 1 < argc) {
                customBatchId = [NSString stringWithUTF8String:argv[++i]];
                continue;
            }

            if ([arg isEqualToString:@"--device"] && i + 1 < argc) {
                targetDeviceAddr = [NSString stringWithUTF8String:argv[++i]];
                continue;
            }

            if ([arg isEqualToString:@"--retry"] && i + 1 < argc) {
                maxRetries = atoi(argv[++i]);
                continue;
            }

            if ([arg isEqualToString:@"--channel"] && i + 1 < argc) {
                targetChannelArg = atoi(argv[++i]);
                continue;
            }

            if ([arg isEqualToString:@"--fetch-answers"] && i + 1 < argc) {
                customBatchId = [NSString stringWithUTF8String:argv[++i]];
                isFetchMode = YES;
                continue;
            }

            if ([arg isEqualToString:@"--chat"] && i + 1 < argc) {
                chatText = [NSString stringWithUTF8String:argv[++i]];
                isChatMode = YES;
                continue;
            }

            if ([arg isEqualToString:@"--or-key"] && i + 1 < argc) {
                orKey = [NSString stringWithUTF8String:argv[++i]];
                continue;
            }

            if ([arg isEqualToString:@"--cb-key"] && i + 1 < argc) {
                cbKey = [NSString stringWithUTF8String:argv[++i]];
                continue;
            }

            if ([arg isEqualToString:@"--cancel-all"]) {
                isCancelMode = YES;
                continue;
            }

            if ([arg isEqualToString:@"--pause"] && i + 1 < argc) {
                pauseVal = [NSString stringWithUTF8String:argv[++i]];
                isPauseMode = YES;
                continue;
            }

            if ([arg isEqualToString:@"--resolve-one"] && i + 1 < argc) {
                resolveOneNum = atoi(argv[++i]);
                isResolveOneMode = YES;
                continue;
            }

            // Otherwise treat as file path
            NSString *filePath = [arg stringByStandardizingPath];
            if (![[NSFileManager defaultManager] fileExistsAtPath:filePath]) {
                printf("[-] File not found: %s\n", argv[i]);
                continue;
            }

            NSString *ext = [[filePath pathExtension] lowercaseString];
            NSData *fileData = [NSData dataWithContentsOfFile:filePath];
            if (!fileData) continue;

            uint8_t type = TYPE_TEXT;
            NSString *fileName = [filePath lastPathComponent];

            if ([ext isEqualToString:@"png"] || [ext isEqualToString:@"jpg"] ||
                [ext isEqualToString:@"jpeg"] || [ext isEqualToString:@"webp"]) {
                type = TYPE_IMAGE;
                if (autoCompress && [fileData length] > 200 * 1024) {
                    fileData = optimizeImage(fileData, ext);
                    fileName = [[fileName stringByDeletingPathExtension] stringByAppendingPathExtension:@"jpg"];
                }
            } else if ([ext isEqualToString:@"mp3"] || [ext isEqualToString:@"m4a"] ||
                       [ext isEqualToString:@"wav"] || [ext isEqualToString:@"aac"] ||
                       [ext isEqualToString:@"ogg"]) {
                type = TYPE_AUDIO;
                if ([fileData length] > 300 * 1024) {
                    NSString *tmpOut = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSString stringWithFormat:@"blue_voice_%@.m4a", [[NSUUID UUID] UUIDString]]];
                    // No-shell exec (filename injection proof) + hard cap loop
                    NSTask *conv = [[NSTask alloc] init];
                    conv.launchPath = @"/usr/bin/afconvert";
                    conv.arguments = @[@"-f", @"m4af", @"-d", @"aac", @"-b", @"32000", @"-c", @"1", filePath, tmpOut];
                    @try { [conv launch]; [conv waitUntilExit]; } @catch (NSException *e) { conv = nil; }
                    int ret = (conv && [conv terminationStatus] == 0) ? 0 : 1;
                    if (ret == 0) {
                        NSData *compressedData = [NSData dataWithContentsOfFile:tmpOut];
                        if (compressedData && [compressedData length] > 0 && [compressedData length] < [fileData length]) {
                            printf("  [Voice Compress] %s: %.1f MB -> %.1f KB (Saved %.0f%% Bluetooth time)\n",
                                   [fileName UTF8String], [fileData length] / (1024.0 * 1024.0), [compressedData length] / 1024.0,
                                   (1.0 - ((double)[compressedData length] / [fileData length])) * 100.0);
                            fileData = compressedData;
                            fileName = [[fileName stringByDeletingPathExtension] stringByAppendingPathExtension:@"m4a"];
                        }
                        [[NSFileManager defaultManager] removeItemAtPath:tmpOut error:nil];
                    }
                }
            }

            [itemTypes addObject:@(type)];
            [itemNames addObject:fileName];
            [itemDatas addObject:fileData];

            if ([itemDatas count] >= 200) {
                printf("[!] Reached maximum limit of 200 items for this batch.\n");
                break;
            }
        }

        IOBluetoothDevice *dev = [IOBluetoothDevice deviceWithAddressString:targetDeviceAddr];
        if (!dev) {
            printf("[-] Target Android device %s not found in paired list.\n", [targetDeviceAddr UTF8String]);
            return 1;
        }

        if ([dev respondsToSelector:@selector(classicPeer)]) {
            CBClassicPeer *peer = [dev classicPeer];
            if (peer && [peer respondsToSelector:@selector(manager)]) {
                CBManager *mgr = [peer manager];
                if (mgr) {
                    NSDate *btWait = [NSDate date];
                    while ([mgr state] != 5 && [[NSDate date] timeIntervalSinceDate:btWait] < 2.0) {
                        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
                    }
                }
            }
        }

        if (isPingMode) {
            if (![dev isConnected]) {
                [dev openConnection];
            }
            // Order: explicit --channel, last-good cache, SDP discovery, fixed 25.
            // Tries each in turn; first verified ACK wins.
            NSMutableArray<NSNumber *> *pingCandidates = [NSMutableArray array];
            if (targetChannelArg > 0) {
                [pingCandidates addObject:@(targetChannelArg)];
            } else {
                int cached = readLastGoodChannel();
                if (cached > 0) [pingCandidates addObject:@(cached)];
                BluetoothRFCOMMChannelID disc = discoverChannelForDevice(dev);
                // SDP discriminator (frozen vs dead): the SDP record is registered
                // by the BT stack, so it survives a process freeze but vanishes on
                // kill. SDP hit + RFCOMM refuse = frozen; SDP miss = dead/gone.
                if (disc > 0) {
                    printf("[SDP] service record resolves: CH %d (peer stack alive)\n", disc);
                    [pingCandidates addObject:@(disc)];
                } else {
                    printf("[SDP] no service record (peer dead, record gone, or BT off)\n");
                }
                [pingCandidates addObject:@(25)];
            }
            for (NSNumber *candNum in pingCandidates) {
                BluetoothRFCOMMChannelID pingCh = (BluetoothRFCOMMChannelID)[candNum intValue];
                BlueSender *pSender = [[BlueSender alloc] init];
                IOBluetoothRFCOMMChannel *ch = nil;
                IOReturn ret = [dev openRFCOMMChannelSync:&ch withChannelID:pingCh delegate:pSender];
                if (ret == kIOReturnSuccess && ch) {
                    pSender.channel = ch;
                    pSender.isOpen = YES;
                    usleep(50000);
                    [pSender sendRawFrameWithId:0 cmd:CMD_PING payload:[NSData dataWithBytes:"PING" length:4]];
                    if ([pSender waitForAckWithId:0 timeoutSec:3.5]) {
                        printf("[✓] Blue Phone Service is ONLINE & LOCKED IN (CH %d)\n", pingCh);
                        writeLastGoodChannel(pingCh);
                        [ch closeChannel];
                        return 0;
                    }
                    [ch closeChannel];
                }
            }
            printf("[-] Blue Phone Service not responding on any probed channel.\n");
            return 1;
        }

        int totalCount = (int)[itemDatas count];
        if (totalCount == 0 && !isFetchMode && !isChatMode && !isCancelMode && !isPauseMode && !isResolveOneMode) {
            printf("[-] No valid items to send.\n");
            return 1;
        }
        if (isChatMode && (!chatText || [chatText length] == 0)) {
            printf("[-] --chat requires question text.\n");
            return 1;
        }
        if (isFetchMode && (!customBatchId || [customBatchId length] == 0)) {
            printf("[-] --fetch-answers requires a batch ID.\n");
            return 1;
        }

        size_t totalBytes = 0;
        for (NSData *d in itemDatas) totalBytes += [d length];

        printf("\n=======================================================\n");
        printf("  Blue Bluetooth Dispatcher & Receiver\n");
        printf("=======================================================\n");
        printf("[*] Batch size   : %d items (Total: %.1f KB)\n", totalCount, totalBytes / 1024.0);
        printf("[*] Target Phone : %s\n", [targetDeviceAddr UTF8String]);
        printf("-------------------------------------------------------\n");

        BlueSender *sender = [[BlueSender alloc] init];
        NSMutableArray<NSNumber *> *channelsToTry = [NSMutableArray array];
        if (targetChannelArg > 0) {
            [channelsToTry addObject:@(targetChannelArg)];
            [channelsToTry addObject:@(targetChannelArg)]; // 2nd attempt on same channel
        } else {
            // Order: last-good cache (instant on wakeups), SDP discovery,
            // fixed 25 (proven primary on current phones). First verified ACK wins.
            int cached = readLastGoodChannel();
            if (cached > 0) {
                [channelsToTry addObject:@(cached)];
                [channelsToTry addObject:@(cached)];
            }
            BluetoothRFCOMMChannelID discovered = discoverChannelForDevice(dev);
            if (discovered > 0 && discovered != cached) {
                [channelsToTry addObject:@(discovered)];
                [channelsToTry addObject:@(discovered)];
            }
            [channelsToTry addObject:@(25)];
            [channelsToTry addObject:@(25)];
        }

        // Determine batch ID
        NSString *batchId = customBatchId;
        if (!batchId || [batchId length] == 0) {
            NSDateFormatter *df = [[NSDateFormatter alloc] init];
            [df setDateFormat:@"yyyyMMdd_HHmmss"];
            batchId = [NSString stringWithFormat:@"batch_%@", [df stringFromDate:[NSDate date]]];
        }
        sender.currentBatchId = batchId;

        // P2: load verified-item resume state (batch sends only).
        NSString *resumePath = nil;
        NSMutableSet<NSNumber *> *verifiedItems = [NSMutableSet set];
        BOOL isBatchSend = (batchId && !isChatMode && !isFetchMode && !isCancelMode && !isPauseMode && !isResolveOneMode);
        if (isBatchSend) {
            resumePath = SendResumePath(batchId);
            NSArray *arr = [NSArray arrayWithContentsOfFile:resumePath];
            for (id o in arr) {
                if ([o respondsToSelector:@selector(intValue)]) [verifiedItems addObject:@([o intValue])];
            }
            if ([verifiedItems count] > 0) {
                printf("[*] Resuming %s: %lu items already verified, skipping.\n",
                       [batchId UTF8String], (unsigned long)[verifiedItems count]);
            }
        }

        NSData *batchIdData = [batchId dataUsingEncoding:NSUTF8StringEncoding];
        uint16_t bIdLen = (uint16_t)[batchIdData length];
        NSMutableData *batchStartPayload = [NSMutableData dataWithCapacity:4 + bIdLen];
        uint8_t bHdr[4];
        bHdr[0] = (totalCount >> 8) & 0xFF;
        bHdr[1] = totalCount & 0xFF;
        bHdr[2] = (bIdLen >> 8) & 0xFF;
        bHdr[3] = bIdLen & 0xFF;
        [batchStartPayload appendBytes:bHdr length:4];
        [batchStartPayload appendData:batchIdData];

        if (![dev isConnected]) {
            printf("[*] Device not connected at baseband. Opening connection... ");
            IOReturn oRet = [dev openConnection];
            printf("0x%x (connected: %d)\n", oRet, [dev isConnected]);
        }

        printf("[*] Connecting to Blue app on Android %s...\n", [targetDeviceAddr UTF8String]);
        BOOL connected = NO;
        BluetoothRFCOMMChannelID connectedChannel = 0;

        for (NSNumber *chNum in channelsToTry) {
            BluetoothRFCOMMChannelID chId = (BluetoothRFCOMMChannelID)[chNum intValue];
            printf("  -> Probing RFCOMM Channel %-2d... ", chId);
            fflush(stdout);

            IOBluetoothRFCOMMChannel *ch = nil;
            IOReturn ret = [dev openRFCOMMChannelSync:&ch withChannelID:chId delegate:sender];
            if (ret == kIOReturnSuccess && ch) {
                sender.channel = ch;
                sender.isOpen = YES;
                usleep(100000); // 100ms for BT stack to stabilize after screen-off wakeup
                if (!sender.isOpen) {
                    printf("[Dead SCN]\n");
                    continue;
                }

                // Verify handshake (control modes create no batch)
                if (isChatMode) {
                    NSDictionary *chatReq = @{@"q": chatText ?: @"",
                                              @"or_key": orKey ?: @"",
                                              @"cb_key": cbKey ?: @""};
                    NSData *chatPayload = [NSJSONSerialization dataWithJSONObject:chatReq options:0 error:nil];
                    [sender sendRawFrameWithId:0 cmd:CMD_CHAT payload:chatPayload];
                } else if (isCancelMode) {
                    [sender sendRawFrameWithId:0 cmd:CMD_CANCEL
                                       payload:[@"*" dataUsingEncoding:NSUTF8StringEncoding]];
                } else if (isPauseMode) {
                    [sender sendRawFrameWithId:0 cmd:CMD_PAUSE
                                       payload:[pauseVal dataUsingEncoding:NSUTF8StringEncoding]];
                } else if (isResolveOneMode) {
                    // Single-question re-solve: send question num + current batch ID
                    NSDictionary *resolveReq = @{
                        @"num": @(resolveOneNum),
                        @"batch_id": customBatchId ?: batchId ?: @""
                    };
                    NSData *resolvePayload = [NSJSONSerialization dataWithJSONObject:resolveReq options:0 error:nil];
                    [sender sendRawFrameWithId:0 cmd:CMD_RESOLVE_ONE payload:resolvePayload];
                } else if (isFetchMode) {
                    [sender sendRawFrameWithId:0 cmd:CMD_FETCH payload:batchIdData];
                } else {
                    [sender sendRawFrameWithId:0 cmd:CMD_BATCH_START payload:batchStartPayload];
                }
                if ([sender waitForAckWithId:0 timeoutSec:6.0]) {  // 6s timeout — phone may be slow from screen-off
                    writeLastGoodChannel(chId);
                    printf("[CONNECTED & VERIFIED! (Sync, MTU: %u)]\n\n", [sender.channel getMTU]);
                    connected = YES;
                    connectedChannel = chId;
                    break;
                } else {
                    printf("[No Blue ACK, retrying]\n");
                    [ch closeChannel];
                    sender.channel = nil;
                    sender.isOpen = NO;
                    usleep(600000); // 600ms between retries
                }
            } else {
                printf("[Sync error: 0x%x]\n", ret);
            }

        }

        if (!connected) {
            printf("\n[-] Could not connect to Blue app on phone (%s) on any channel.\n", [targetDeviceAddr UTF8String]);
            printf("    Make sure Bluetooth is ON and 'Start Service' is pressed in the Blue app.\n");
            return 1;
        }

        printf("[*] Batch %s verified on phone (Channel %d).\n", [batchId UTF8String], connectedChannel);

        if (isCancelMode) {
            printf("[✓] Phone confirmed whole-reset (solver stopped, batch folder zeroed).\n");
            [sender.channel closeChannel];
            return 0;
        }

        if (isPauseMode) {
            printf("[✓] Phone confirmed pause=%s.\n", [pauseVal UTF8String]);
            [sender.channel closeChannel];
            return 0;
        }

        if (isChatMode) {
            printf("[*] Chat sent, waiting for phone AI (Nemotron -> Cerebras)...\n");
            goto wait_chat;
        }

        if (isResolveOneMode) {
            printf("[*] Re-solve Q%d sent — waiting for phone Gemini answer...\n", resolveOneNum);
            goto wait_answers; // CMD_PROGRESS comes back through the same answer path
        }

        if (isFetchMode) {
            printf("[*] Fetch mode: requesting stored answers for %s (no transfer)...\n", [batchId UTF8String]);
            goto wait_answers;
        }

        // 2. Transfer each item
        NSDate *transferStart = [NSDate date];
        for (int i = 0; i < totalCount; i++) {
            uint16_t itemIdx = (uint16_t)(i + 1); // 1-based index
            uint8_t itemType = [itemTypes[i] unsignedCharValue];
            NSString *name = itemNames[i];
            if ([verifiedItems containsObject:@(itemIdx)]) {
                printf("  [%02d/%d] %s skipped (already verified)\n",
                       itemIdx, totalCount, [name UTF8String]);
                continue;
            }
            NSData *d = itemDatas[i];
            uint32_t itemSize = (uint32_t)[d length];

            NSData *nameData = [name dataUsingEncoding:NSUTF8StringEncoding];
            uint16_t nameLen = (uint16_t)[nameData length];

            // Send ITEM_START
            NSMutableData *itemStartPayload = [NSMutableData dataWithCapacity:7 + nameLen];
            uint8_t iHdr[7];
            iHdr[0] = itemType;
            iHdr[1] = (itemSize >> 24) & 0xFF;
            iHdr[2] = (itemSize >> 16) & 0xFF;
            iHdr[3] = (itemSize >> 8) & 0xFF;
            iHdr[4] = itemSize & 0xFF;
            iHdr[5] = (nameLen >> 8) & 0xFF;
            iHdr[6] = nameLen & 0xFF;
            [itemStartPayload appendBytes:iHdr length:7];
            [itemStartPayload appendData:nameData];

            [sender sendRawFrameWithId:itemIdx cmd:CMD_ITEM_START payload:itemStartPayload];

            // Send Chunks
            const uint8_t *raw = (const uint8_t *)[d bytes];
            size_t offset = 0;
            size_t maxChunk = 850; // safe margin under 990 MTU

            printProgress(itemIdx, totalCount, name, 0, itemSize);

            while (offset < itemSize) {
                size_t thisChunk = itemSize - offset;
                if (thisChunk > maxChunk) thisChunk = maxChunk;

                NSData *chunkData = [NSData dataWithBytes:(raw + offset) length:thisChunk];
                [sender sendRawFrameWithId:itemIdx cmd:CMD_CHUNK_DATA payload:chunkData];
                offset += thisChunk;

                printProgress(itemIdx, totalCount, name, offset, itemSize);
                [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.001]];
            }

            // Send ITEM_END and wait for phone ACK
            [sender sendRawFrameWithId:itemIdx cmd:CMD_ITEM_END payload:nil];
            if (![sender waitForAckWithId:itemIdx timeoutSec:5.0]) {
                printf("\n[-] Phone did not ACK item #%d (%s)\n", itemIdx, [name UTF8String]);
                [sender.channel closeChannel];
                return 1;
            }
            printf(" -> Verified [OK]\n");
            if (resumePath) {
                [verifiedItems addObject:@(itemIdx)];
                [[verifiedItems allObjects] writeToFile:resumePath atomically:YES];
            }
        }

        // 3. Send BATCH_END
        [sender sendRawFrameWithId:0 cmd:CMD_BATCH_END payload:nil];
        if (![sender waitForAckWithId:0 timeoutSec:5.0]) {
            printf("[-] Phone did not ACK BATCH_END.\n");
        } else {
            NSTimeInterval elapsed = [[NSDate date] timeIntervalSinceDate:transferStart];
            printf("\n=======================================================\n");
            printf("*** [✓] BATCH DISPATCHED & VERIFIED ON PHONE! ***\n");
            printf("  Batch ID   : %s\n", [batchId UTF8String]);
            printf("  Items Sent : %d items (%.1f KB)\n", totalCount, totalBytes / 1024.0);
            printf("  Time Taken : %.1f seconds (Avg: %.1f KB/s)\n", elapsed, (totalBytes / 1024.0) / elapsed);
            printf("  Storage    : Saved in phone app batches/%s/\n", [batchId UTF8String]);
            printf("=======================================================\n");
            if (resumePath) {
                [[NSFileManager defaultManager] removeItemAtPath:resumePath error:nil];
            }
        }

        // 3b. Chat answer wait (Nemotron x3 -> Cerebras x3 on phone, ~150s cap)
    wait_chat:
        if (isChatMode) {
        NSDate *startWait = [NSDate date];
        while (!sender.receivedChatData && sender.isOpen) {
            NSTimeInterval elapsed = [[NSDate date] timeIntervalSinceDate:startWait];
            if (elapsed >= 240.0) {
                printf("\n[!] Chat timeout (240s), phone AI did not answer.\n");
                break;
            }
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
        }
        if (sender.receivedChatData) {
            NSString *answersDir = getAnswersDirectory();
            [[NSFileManager defaultManager] createDirectoryAtPath:answersDir withIntermediateDirectories:YES attributes:nil error:nil];
            NSString *chatFile = [answersDir stringByAppendingPathComponent:@"chat_latest.json"];
            NSError *jerr = nil;
            NSDictionary *chatJson = [NSJSONSerialization JSONObjectWithData:sender.receivedChatData options:0 error:&jerr];
            NSMutableDictionary *outJson = chatJson ? [chatJson mutableCopy] : [NSMutableDictionary dictionary];
            if (!chatJson) {
                outJson[@"question"] = chatText ?: @"";
                NSString *rawAns = [[NSString alloc] initWithData:sender.receivedChatData encoding:NSUTF8StringEncoding];
                outJson[@"answer"] = rawAns ?: @"-";
            }
            outJson[@"ts"] = @([[NSDate date] timeIntervalSince1970]);
            NSData *outData = [NSJSONSerialization dataWithJSONObject:outJson options:NSJSONWritingPrettyPrinted error:nil];
            if (outData) [outData writeToFile:chatFile atomically:YES];
            [sender sendRawFrameWithId:0 cmd:CMD_ACK payload:[@"CHAT_OK" dataUsingEncoding:NSUTF8StringEncoding]];
            NSString *mStr = [NSString stringWithFormat:@"%@", [outJson objectForKey:@"model"] ?: @"?"];
            NSString *qStr = [NSString stringWithFormat:@"%@", [outJson objectForKey:@"question"] ?: @""];
            NSString *aStr = [NSString stringWithFormat:@"%@", [outJson objectForKey:@"answer"] ?: @""];
            printf("\n[CHAT] (%s)\n  Q: %s\n  A: %s\n",
                   [mStr UTF8String],
                   [qStr UTF8String],
                   [aStr UTF8String]);
            printf("  Saved to : %s\n", [chatFile UTF8String]);
        } else {
            printf("[-] No chat answer received (cancelled or timed out).\n");
        }
        [sender.channel closeChannel];
        return (sender.receivedChatData ? 0 : 1);
        }

        // 4. Wait for Phone AI to Solve & Return Answers
    wait_answers:
        if (waitAnswers || isFetchMode) {
            printf("\n================================================================================\n");
            printf("  [PHONE AI RUNNING] Querying Google Cloud Discovery Engine via Phone Internet  \n");
            printf("================================================================================\n");
            printf("[*] The Android Phone is executing queries directly to Google Cloud...\n");
            printf("[*] Live progress will stream below:\n\n");
            fflush(stdout);

            NSDate *startWait = [NSDate date];
            NSTimeInterval timeoutSec = 600.0; // 10 min: stagger+90s reads+retries need it
            while (!sender.receivedAnswersData && sender.isOpen) {
                NSTimeInterval elapsed = [[NSDate date] timeIntervalSinceDate:startWait];
                if (elapsed >= timeoutSec) {
                    printf("\n[!] Timeout (%.0f s) waiting for answers from phone.\n", timeoutSec);
                    break;
                }
                [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
            }

            if (sender.receivedAnswersData) {
                // Save answers file locally
                NSString *answersDir = [baseDir stringByAppendingPathComponent:@"answers"];
                [[NSFileManager defaultManager] createDirectoryAtPath:answersDir withIntermediateDirectories:YES attributes:nil error:nil];
                NSString *ansFile = [NSString stringWithFormat:@"%@/%@_answers.json", answersDir, batchId];
                [sender.receivedAnswersData writeToFile:ansFile atomically:YES];

                // Remove live progress file now that complete answers file is safely saved
                NSString *liveFile = [NSString stringWithFormat:@"%@/%@_live.json", answersDir, batchId];
                [[NSFileManager defaultManager] removeItemAtPath:liveFile error:nil];

                // Send ACK back to phone
                [sender sendRawFrameWithId:0 cmd:CMD_ACK payload:[@"ANSWERS_OK" dataUsingEncoding:NSUTF8StringEncoding]];

                // Pretty print answers table
                NSError *jsonErr = nil;
                NSDictionary *root = [NSJSONSerialization JSONObjectWithData:sender.receivedAnswersData options:0 error:&jsonErr];

                printf("\n====================================================================================================\n");
                printf("                          BLUE EXAM ANSWERS RECEIVED FROM ANDROID PHONE                          \n");
                printf("====================================================================================================\n");
                printf("  Batch ID : %s\n", [batchId UTF8String]);
                printf("  Saved to : %s\n", [ansFile UTF8String]);
                NSString *sbStr = [NSString stringWithFormat:@"%@", [root objectForKey:@"solved_by"] ?: @"Android Phone"];
                printf("  Solved By: %s\n", [sbStr UTF8String]);
                printf("------------------------------------------------------------------------------------------------------------------------\n");
                printf("  %-4s | %-40s | %s\n", "No", "Jawaban", "Alasan / Penjelasan Singkat");
                printf("-------+------------------------------------------+------------------------------------------------------------\n");

                NSArray *answers = [root objectForKey:@"answers"];
                if ([answers isKindOfClass:[NSArray class]]) {
                    for (NSDictionary *ans in answers) {
                        int no = [[ans objectForKey:@"nomor"] intValue];
                        NSString *j = formatAnswerObj([ans objectForKey:@"jawaban"]);
                        NSString *a = formatReasonObj([ans objectForKey:@"alasan"]);
                        printf("  #%02d  | %-40s | %s\n", no, [j UTF8String], [a UTF8String]);
                    }
                } else if ([root isKindOfClass:[NSDictionary class]]) {
                    NSArray *sortedKeys = [[root allKeys] sortedArrayUsingSelector:@selector(compare:)];
                    for (NSString *key in sortedKeys) {
                        if ([key isEqualToString:@"solved_by"] || [key isEqualToString:@"batch_id"]) continue;
                        if ([key hasPrefix:@"_"]) continue; // _meta sidecar, not a question
                        id val = root[key];
                        if ([val isKindOfClass:[NSDictionary class]]) {
                            id rawAns = [val objectForKey:@"answer"];
                            if (!rawAns && [val objectForKey:@"option"]) {
                                NSString *opt = [val objectForKey:@"option"];
                                NSString *optAns = [val objectForKey:@"option_answer"] ?: @"";
                                rawAns = [optAns length] > 0 ? [NSString stringWithFormat:@"%@. %@", opt, optAns] : opt;
                            } else if (!rawAns && [val objectForKey:@"options"]) {
                                rawAns = [val objectForKey:@"options"];
                            }
                            NSString *ans = formatAnswerObj(rawAns);
                            if ([[val objectForKey:@"confidence"] isEqualToString:@"auto-filled"]) {
                                ans = [@"? " stringByAppendingString:ans];
                            }
                            id rawRsn = [val objectForKey:@"reason"] ?: [val objectForKey:@"alasan"] ?: [val objectForKey:@"analysis"];
                            NSString *reason = formatReasonObj(rawRsn);
                            // Truncate reason for display
                            if ([reason length] > 58) {
                                reason = [[reason substringToIndex:55] stringByAppendingString:@"..."];
                            }
                            printf("  #%-3s | %-40s | %s\n", [key UTF8String], [ans UTF8String], [reason UTF8String]);
                        }
                    }
                }
                printf("========================================================================================================================\n\n");
            } else {
                printf("[-] No answers received from phone. Connection closed or timed out.\n");
            }
        }

        [sender.channel closeChannel];
    }
    return 0;
}
