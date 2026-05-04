# Building and Installing

## Release build

Java 17 is configured in `gradle.properties` (`org.gradle.java.home`), so no `JAVA_HOME` prefix is needed:

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Connect device via Wi-Fi (Android 11+)

1. **Settings → Developer Options → Wireless Debugging** → enable
2. Tap **Pair device with pairing code** — note the IP:port and 6-digit code

```bash
adb pair <ip>:<pairing-port>
# enter the 6-digit code
```

3. Back on the Wireless Debugging screen, note the main IP:port (different from pairing port)

```bash
adb connect <ip>:<main-port>
```

## Install

```bash
adb install app/build/outputs/apk/release/app-release.apk
```

If multiple devices are connected, get the transport ID and target it with `-t`:

```bash
adb devices -l
adb -t <transport-id> install app/build/outputs/apk/release/app-release.apk
```
