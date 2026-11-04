#import <Foundation/Foundation.h>
#import <IOBluetooth/IOBluetooth.h>

extern int IOBluetoothPreferenceGetControllerPowerState();
extern int IOBluetoothPreferenceSetControllerPowerState(int state);

int main(int argc, const char * argv[]) {
    @autoreleasepool {
        NSString *cmd = argc > 1 ? [NSString stringWithUTF8String:argv[1]] : @"status";
        NSString *baseDir = [[NSProcessInfo processInfo] environment][@"BLUE_DIR"] ?: [[NSFileManager defaultManager] currentDirectoryPath];
        const char *envMac = getenv("TARGET_PHONE_MAC");
        NSString *defaultMac = envMac ? [NSString stringWithUTF8String:envMac] : nil;
        if (!defaultMac) {
            NSString *configPath = [baseDir stringByAppendingPathComponent:@"config.json"];
            if ([[NSFileManager defaultManager] fileExistsAtPath:configPath]) {
                NSData *cfgData = [NSData dataWithContentsOfFile:configPath];
                if (cfgData) {
                    NSDictionary *cfg = [NSJSONSerialization JSONObjectWithData:cfgData options:0 error:nil];
                    if (cfg && cfg[@"target_phone_mac"]) {
                        defaultMac = cfg[@"target_phone_mac"];
                    }
                }
            }
        }
        if (!defaultMac) {
            defaultMac = @"00-00-00-00-00-00";
        }
        NSString *targetAddr = argc > 2 ? [NSString stringWithUTF8String:argv[2]] : defaultMac;
        
        if ([cmd isEqualToString:@"power-on"]) {
            int cur = IOBluetoothPreferenceGetControllerPowerState();
            if (cur == 0) {
                IOBluetoothPreferenceSetControllerPowerState(1);
                usleep(500000);
            }
            printf("%d\n", IOBluetoothPreferenceGetControllerPowerState());
            return 0;
        }
        
        if ([cmd isEqualToString:@"power-state"]) {
            printf("%d\n", IOBluetoothPreferenceGetControllerPowerState());
            return 0;
        }
        
        if ([cmd isEqualToString:@"is-connected"]) {
            IOBluetoothDevice *dev = [IOBluetoothDevice deviceWithAddressString:targetAddr];
            if (dev && [dev isConnected]) {
                printf("1\n");
                return 0;
            }
            printf("0\n");
            return 1;
        }
        
        if ([cmd isEqualToString:@"connect"]) {
            IOBluetoothDevice *dev = [IOBluetoothDevice deviceWithAddressString:targetAddr];
            if (!dev) {
                printf("NOT_FOUND\n");
                return 1;
            }
            if ([dev isConnected]) {
                printf("ALREADY_CONNECTED\n");
                return 0;
            }
            IOReturn r = [dev openConnection];
            if (r == kIOReturnSuccess || [dev isConnected]) {
                printf("CONNECTED\n");
                return 0;
            } else {
                printf("FAILED\n");
                return 1;
            }
        }
        
        // Default: full status JSON
        int power = IOBluetoothPreferenceGetControllerPowerState();
        IOBluetoothDevice *dev = [IOBluetoothDevice deviceWithAddressString:targetAddr];
        
        NSMutableDictionary *dict = [NSMutableDictionary dictionary];
        dict[@"power"] = @(power == 1);
        if (dev) {
            dict[@"name"] = [dev name] ?: @"Unknown";
            dict[@"address"] = [dev addressString] ?: targetAddr;
            dict[@"paired"] = @([dev isPaired]);
            dict[@"connected"] = @([dev isConnected]);
        } else {
            dict[@"found"] = @(NO);
        }
        
        NSData *data = [NSJSONSerialization dataWithJSONObject:dict options:0 error:nil];
        NSString *json = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        printf("%s\n", [json UTF8String]);
        return 0;
    }
}
