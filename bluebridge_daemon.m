#import <Foundation/Foundation.h>
#import <IOBluetooth/IOBluetooth.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <fcntl.h>

#define MAGIC 0x4242
#define CMD_CONNECT 0x01
#define CMD_CONNECTED 0x02
#define CMD_DATA 0x03
#define CMD_CLOSE 0x04
#define CMD_ERROR 0x05

@interface CBManager : NSObject
- (long)state;
@end

@interface CBClassicPeer : NSObject
- (CBManager *)manager;
@end

@interface IOBluetoothDevice (Private)
- (CBClassicPeer *)classicPeer;
@end

@interface BlueBridgeDaemon : NSObject <IOBluetoothRFCOMMChannelDelegate>
@property (nonatomic, retain) IOBluetoothRFCOMMChannel *rfcommChannel;
@property (nonatomic, assign) BOOL isRfcommOpen;
@property (nonatomic, retain) NSMutableData *rxBuffer;
@property (nonatomic, retain) NSMutableDictionary<NSNumber *, NSNumber *> *activeClientSockets;
@property (nonatomic, retain) NSMutableDictionary<NSNumber *, dispatch_semaphore_t> *streamSemaphores;
@property (nonatomic, assign) uint16_t nextStreamId;
@property (nonatomic, assign) int socksListenFd;
@end

static BlueBridgeDaemon *gDaemon = nil;

@implementation BlueBridgeDaemon

- (instancetype)init {
    self = [super init];
    if (self) {
        self.rxBuffer = [NSMutableData data];
        self.activeClientSockets = [NSMutableDictionary dictionary];
        self.streamSemaphores = [NSMutableDictionary dictionary];
        self.nextStreamId = 1;
    }
    return self;
}

- (void)rfcommChannelOpenComplete:(IOBluetoothRFCOMMChannel*)channel status:(IOReturn)error {
    NSLog(@"[RFCOMM] Channel %u OpenComplete status = %d (0x%08X)", [channel getChannelID], error, error);
    if (error == kIOReturnSuccess) {
        self.rfcommChannel = channel;
        self.isRfcommOpen = YES;
        NSLog(@"\n=======================================================");
        NSLog(@"*** [SUCCESS] CONNECTED TO BLUEBRIDGE ON ANDROID! ***");
        NSLog(@"Channel ID: %u, MTU: %u", [channel getChannelID], [channel getMTU]);
        NSLog(@"=======================================================\n");
        [self startSocksServer];
    } else {
        NSLog(@"[-] Failed to open RFCOMM channel %u", [channel getChannelID]);
    }
}

- (void)rfcommChannelClosed:(IOBluetoothRFCOMMChannel*)channel {
    NSLog(@"[RFCOMM] Channel closed by Android.");
    self.isRfcommOpen = NO;
    @synchronized(self.activeClientSockets) {
        for (NSNumber *fdNum in [self.activeClientSockets allValues]) {
            close([fdNum intValue]);
        }
        [self.activeClientSockets removeAllObjects];
    }
}

- (void)rfcommChannelData:(IOBluetoothRFCOMMChannel*)channel data:(void *)dataPointer length:(size_t)dataLength {
    @synchronized(self.rxBuffer) {
        [self.rxBuffer appendBytes:dataPointer length:dataLength];
        [self processRxBuffer];
    }
}

- (void)processRxBuffer {
    while ([self.rxBuffer length] >= 7) {
        const uint8_t *bytes = (const uint8_t *)[self.rxBuffer bytes];
        uint16_t magic = (bytes[0] << 8) | bytes[1];
        if (magic != MAGIC) {
            [self.rxBuffer replaceBytesInRange:NSMakeRange(0, 1) withBytes:NULL length:0];
            continue;
        }

        uint16_t streamId = (bytes[2] << 8) | bytes[3];
        uint8_t cmd = bytes[4];
        uint16_t payloadLen = (bytes[5] << 8) | bytes[6];

        if ([self.rxBuffer length] < 7 + payloadLen) {
            break;
        }

        NSData *payload = [NSData dataWithBytes:&bytes[7] length:payloadLen];
        [self.rxBuffer replaceBytesInRange:NSMakeRange(0, 7 + payloadLen) withBytes:NULL length:0];

        [self handleFrameWithStreamId:streamId cmd:cmd payload:payload];
    }
}

- (void)handleFrameWithStreamId:(uint16_t)streamId cmd:(uint8_t)cmd payload:(NSData *)payload {
    if (cmd == CMD_CONNECTED) {
        NSLog(@"[Stream %u] Handshake complete", streamId);
        dispatch_semaphore_t sem = nil;
        @synchronized(self.streamSemaphores) {
            sem = self.streamSemaphores[@(streamId)];
        }
        if (sem) {
            dispatch_semaphore_signal(sem);
        }
    } else if (cmd == CMD_DATA) {
        int clientFd = -1;
        @synchronized(self.activeClientSockets) {
            NSNumber *fdNum = self.activeClientSockets[@(streamId)];
            if (fdNum) clientFd = [fdNum intValue];
        }
        if (clientFd >= 0) {
            send(clientFd, [payload bytes], [payload length], 0);
        }
    } else if (cmd == CMD_CLOSE || cmd == CMD_ERROR) {
        int clientFd = -1;
        @synchronized(self.activeClientSockets) {
            NSNumber *fdNum = self.activeClientSockets[@(streamId)];
            if (fdNum) {
                clientFd = [fdNum intValue];
                [self.activeClientSockets removeObjectForKey:@(streamId)];
            }
            [self.streamSemaphores removeObjectForKey:@(streamId)];
        }
        if (clientFd >= 0) {
            close(clientFd);
        }
    }
}

- (BOOL)sendFrameWithStreamId:(uint16_t)streamId cmd:(uint8_t)cmd payload:(NSData *)payload {
    if (!self.isRfcommOpen || !self.rfcommChannel) return NO;

    const uint8_t *rawBytes = (const uint8_t *)[payload bytes];
    size_t totalLen = [payload length];
    size_t offset = 0;
    size_t maxChunk = [self.rfcommChannel getMTU] - 14;
    if (maxChunk == 0 || maxChunk > 960) maxChunk = 960;

    if (totalLen == 0) {
        uint8_t hdr[7];
        hdr[0] = (MAGIC >> 8) & 0xFF;
        hdr[1] = MAGIC & 0xFF;
        hdr[2] = (streamId >> 8) & 0xFF;
        hdr[3] = streamId & 0xFF;
        hdr[4] = cmd;
        hdr[5] = 0;
        hdr[6] = 0;
        @synchronized(self.rfcommChannel) {
            IOReturn r = [self.rfcommChannel writeSync:(void *)hdr length:7];
            return (r == kIOReturnSuccess);
        }
    }

    while (offset < totalLen) {
        size_t thisLen = totalLen - offset;
        if (thisLen > maxChunk) thisLen = maxChunk;

        NSMutableData *frame = [NSMutableData dataWithCapacity:7 + thisLen];
        uint8_t hdr[7];
        hdr[0] = (MAGIC >> 8) & 0xFF;
        hdr[1] = MAGIC & 0xFF;
        hdr[2] = (streamId >> 8) & 0xFF;
        hdr[3] = streamId & 0xFF;
        hdr[4] = cmd;
        hdr[5] = (thisLen >> 8) & 0xFF;
        hdr[6] = thisLen & 0xFF;
        [frame appendBytes:hdr length:7];
        [frame appendBytes:(rawBytes + offset) length:thisLen];

        @synchronized(self.rfcommChannel) {
            IOReturn r = [self.rfcommChannel writeSync:(void *)[frame bytes] length:[frame length]];
            if (r != kIOReturnSuccess) {
                NSLog(@"[-] writeSync error: 0x%08X (chunk len: %zu)", r, thisLen);
                return NO;
            }
        }
        offset += thisLen;
    }
    return YES;
}

- (void)startSocksServer {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        int listenFd = socket(AF_INET, SOCK_STREAM, 0);
        int opt = 1;
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

        struct sockaddr_in addr;
        memset(&addr, 0, sizeof(addr));
        addr.sin_family = AF_INET;
        addr.sin_port = htons(1080);
        addr.sin_addr.s_addr = inet_addr("127.0.0.1");

        if (bind(listenFd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            NSLog(@"[-] Failed to bind SOCKS5 server to 127.0.0.1:1080");
            close(listenFd);
            return;
        }

        listen(listenFd, 50);
        self.socksListenFd = listenFd;

        NSLog(@"\n=======================================================");
        NSLog(@"[*] BlueBridge SOCKS5 Proxy Listening on 127.0.0.1:1080");
        NSLog(@"[*] Ready for traffic!");
        NSLog(@"=======================================================\n");

        while (self.isRfcommOpen) {
            struct sockaddr_in clientAddr;
            socklen_t clientLen = sizeof(clientAddr);
            int clientFd = accept(listenFd, (struct sockaddr *)&clientAddr, &clientLen);
            if (clientFd < 0) break;

            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
                [self handleSocksClient:clientFd];
            });
        }
    });
}

static BOOL readExact(int fd, void *buf, size_t count) {
    size_t total = 0;
    while (total < count) {
        ssize_t n = recv(fd, (char *)buf + total, count - total, 0);
        if (n <= 0) return NO;
        total += n;
    }
    return YES;
}

- (void)handleSocksClient:(int)clientFd {
    uint8_t buf[256];
    if (!readExact(clientFd, buf, 2) || buf[0] != 0x05) { close(clientFd); return; }
    uint8_t nmethods = buf[1];
    if (!readExact(clientFd, buf, nmethods)) { close(clientFd); return; }
    uint8_t authResp[] = {0x05, 0x00};
    send(clientFd, authResp, 2, 0);

    if (!readExact(clientFd, buf, 4) || buf[0] != 0x05 || buf[1] != 0x01) { close(clientFd); return; }
    uint8_t atyp = buf[3];
    char destHost[256] = {0};
    uint16_t destPort = 0;

    if (atyp == 0x01) {
        struct in_addr in;
        if (!readExact(clientFd, &in, 4)) { close(clientFd); return; }
        inet_ntop(AF_INET, &in, destHost, sizeof(destHost));
    } else if (atyp == 0x03) {
        uint8_t dlen = 0;
        if (!readExact(clientFd, &dlen, 1)) { close(clientFd); return; }
        if (!readExact(clientFd, destHost, dlen)) { close(clientFd); return; }
        destHost[dlen] = '\0';
    } else if (atyp == 0x04) {
        struct in6_addr in6;
        if (!readExact(clientFd, &in6, 16)) { close(clientFd); return; }
        inet_ntop(AF_INET6, &in6, destHost, sizeof(destHost));
    } else {
        close(clientFd); return;
    }

    if (!readExact(clientFd, &destPort, 2)) { close(clientFd); return; }
    destPort = ntohs(destPort);

    uint16_t streamId;
    @synchronized(self) {
        streamId = self.nextStreamId++;
        if (self.nextStreamId == 0) self.nextStreamId = 1;
    }

    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    @synchronized(self.activeClientSockets) {
        self.activeClientSockets[@(streamId)] = @(clientFd);
        self.streamSemaphores[@(streamId)] = sem;
    }

    uint16_t hostLen = (uint16_t)strlen(destHost);
    NSMutableData *connectPayload = [NSMutableData data];
    uint8_t portHdr[4];
    portHdr[0] = (destPort >> 8) & 0xFF;
    portHdr[1] = destPort & 0xFF;
    portHdr[2] = (hostLen >> 8) & 0xFF;
    portHdr[3] = hostLen & 0xFF;
    [connectPayload appendBytes:portHdr length:4];
    [connectPayload appendBytes:destHost length:hostLen];

    NSLog(@"[Stream %u] Connecting to %s:%u over Bluetooth...", streamId, destHost, destPort);
    if (![self sendFrameWithStreamId:streamId cmd:CMD_CONNECT payload:connectPayload]) {
        close(clientFd);
        return;
    }

    long waitRes = dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC));
    if (waitRes != 0) {
        NSLog(@"[Stream %u] Connection to %s:%u timed out", streamId, destHost, destPort);
        uint8_t failResp[] = {0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
        send(clientFd, failResp, 10, 0);
        [self sendFrameWithStreamId:streamId cmd:CMD_CLOSE payload:nil];
        close(clientFd);
        return;
    }

    uint8_t okResp[] = {0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x04, 0x38};
    send(clientFd, okResp, 10, 0);
    NSLog(@"[Stream %u] Connected to %s:%u! Forwarding data...", streamId, destHost, destPort);

    uint8_t dataBuf[4096];
    while (self.isRfcommOpen) {
        ssize_t rd = recv(clientFd, dataBuf, sizeof(dataBuf), 0);
        if (rd <= 0) break;
        NSData *d = [NSData dataWithBytes:dataBuf length:rd];
        if (![self sendFrameWithStreamId:streamId cmd:CMD_DATA payload:d]) break;
    }

    [self sendFrameWithStreamId:streamId cmd:CMD_CLOSE payload:nil];
    @synchronized(self.activeClientSockets) {
        [self.activeClientSockets removeObjectForKey:@(streamId)];
        [self.streamSemaphores removeObjectForKey:@(streamId)];
    }
    close(clientFd);
    NSLog(@"[Stream %u] Closed.", streamId);
}

@end

int main() {
    @autoreleasepool {
        NSString *baseDir = [[NSProcessInfo processInfo] environment][@"BLUE_DIR"] ?: [[NSFileManager defaultManager] currentDirectoryPath];
        const char *envMac = getenv("TARGET_PHONE_MAC");
        NSString *macAddr = envMac ? [NSString stringWithUTF8String:envMac] : nil;
        if (!macAddr) {
            NSString *configPath = [baseDir stringByAppendingPathComponent:@"config.json"];
            if ([[NSFileManager defaultManager] fileExistsAtPath:configPath]) {
                NSData *cfgData = [NSData dataWithContentsOfFile:configPath];
                if (cfgData) {
                    NSDictionary *cfg = [NSJSONSerialization JSONObjectWithData:cfgData options:0 error:nil];
                    if (cfg && cfg[@"target_phone_mac"]) {
                        macAddr = cfg[@"target_phone_mac"];
                    }
                }
            }
        }
        if (!macAddr) {
            macAddr = @"00-00-00-00-00-00";
        }
        IOBluetoothDevice *dev = [IOBluetoothDevice deviceWithAddressString:macAddr];
        if (!dev) {
            NSLog(@"[-] Device %@ not found", macAddr);
            return 1;
        }

        NSLog(@"Target device: %@ [%@], connected: %d", [dev name], [dev addressString], [dev isConnected]);
        CBClassicPeer *peer = [dev classicPeer];
        CBManager *mgr = [peer manager];

        NSLog(@"Waiting for Bluetooth Manager state == 5 (PoweredOn)... initial: %ld", [mgr state]);
        while ([mgr state] != 5) {
            [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.1]];
        }
        NSLog(@"Bluetooth Manager is powered on!");

        NSLog(@"Opening RFCOMM Channel 8 to Android BlueBridge...");
        IOBluetoothRFCOMMChannel *ch = nil;
        IOReturn ret = [dev openRFCOMMChannelAsync:&ch withChannelID:8 delegate:gDaemon];
        if (ret != kIOReturnSuccess) {
            NSLog(@"[-] openRFCOMMChannelAsync returned %d", ret);
            return 1;
        }

        [[NSRunLoop currentRunLoop] run];
    }
    return 0;
}
