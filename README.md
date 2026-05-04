# XLock Locker NFC

Android app that reads a Salto MIFARE DeSFire badge and emulates it via Host Card Emulation (HCE) to unlock an XLock locker without carrying the badge.

## Requirements

- Android 12+ phone with NFC
- Salto badge using MIFARE DeSFire (ISO-DEP)
- Android SDK with platform `android-36` and build-tools `36.1.0`

## Build

The system default Java (26) breaks Kotlin 1.9. The project is pre-configured to use Java 17 via `gradle.properties`. Just run:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Usage

1. Open the app and tap **Scan Badge**
2. Hold the Salto badge to the back of the phone until "X APDU pairs recorded" appears
3. Close the app — the emulation service runs in the background
4. Hold the phone to the XLock locker reader to unlock

The HCE service is active with the screen off (Android 12+), so the phone does not need to be unlocked.

## How it works

### Badge scanning

The app puts the NFC adapter into reader mode and communicates with the DeSFire card via `IsoDep.transceive()`. It runs the following command sequence:

- `00 A4 04 00 00` — ISO 7816 SELECT (default application)
- `90 60 00 00 00` — DeSFire GET_VERSION (repeated for `AF` continuation)
- `90 6A 00 00 00` — DeSFire GET_APPLICATION_IDS
- For each discovered application:
  - `90 5A 00 00 03 <AID>` — SELECT_APPLICATION
  - `90 6F 00 00 00` — GET_FILE_IDS
  - For each file: GET_FILE_SETTINGS, READ_DATA, READ_RECORDS (with `AF` continuation)

Every (command, response) pair is stored in `SharedPreferences` as an ordered transcript.

### HCE emulation

`HceService` extends `HostApduService`. When the locker reader contacts the phone:

1. Android's NFC stack activates the service based on AID routing (registered at scan time via `CardEmulation.registerAidsForService()`)
2. Each incoming APDU is looked up in the stored transcript — exact match first, then 5-byte header prefix match
3. The stored response is returned to the reader
4. Unknown APDUs return `6F 00` (no precise diagnosis)

### AID routing

The `hce_service.xml` declares a placeholder AID `F000000000` in the `other` (non-payment) category. After scanning, discovered 3-byte DeSFire AIDs are registered at runtime. While the app is in the foreground, `CardEmulation.setPreferredService()` is also called so all ISO-DEP traffic is routed to the service regardless of AID.

## Debugging

```bash
adb logcat -s HceService
```

Each received APDU and the matched response are logged. Unknown APDUs (no match in transcript) are logged as warnings — these indicate commands the reader sends that weren't captured during the badge scan.

## Known limitations

- **UID emulation**: Android HCE presents a randomized hardware UID at the ISO 14443-3 layer. If the XLock reader authenticates by UID only, this will not work. DeSFire badges use ISO-DEP (layer 4), so the reader most likely authenticates via APDUs, not raw UID.
- **Cryptographic authentication**: Salto SIDE (offline access control) stores keys on the card and uses DES/AES challenge-response. If the reader issues a nonce that the card must sign, the static transcript replay will fail. This is visible in the APDU log as responses that differ on each tap of the real badge.
- **Screen-off HCE**: Requires the `requireDeviceScreenOn="false"` attribute in `hce_service.xml`, which is supported from Android 12 (API 31). The `minSdk` is set to 31.

## Project structure

```
app/src/main/java/com/fschenkel/xlocklocker/
├── MainActivity.kt       # Badge reader UI and NFC reader mode
├── HceService.kt         # HostApduService — APDU replay
└── BadgeRepository.kt    # SharedPreferences storage for transcript

app/src/main/res/
├── layout/activity_main.xml
├── xml/hce_service.xml   # AID registration and HCE attributes
└── values/               # strings, colors, theme
```
