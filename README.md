# Un-Locker

Android app that reads a Salto MIFARE DeSFire badge and emulates it via Host Card Emulation (HCE) to unlock your locker without carrying the badge.

## Requirements

- Android 12+ phone with NFC
- Salto badge using MIFARE DeSFire (ISO-DEP)
- Android SDK with platform `android-36` and build-tools `36.1.0`

## Releases

Releases are built automatically by GitHub Actions when a version tag is pushed:

```bash
git tag v1.0
git push --tags
```

This triggers a signed release build, creates a GitHub Release, and attaches the APK. You can also trigger a manual build without creating a release via **Actions → Build → Run workflow** in the GitHub UI.

## Local build

Release builds require keystore credentials via environment variables:

```bash
export KEYSTORE_PATH=/home/fschenkel/.android/xlock-release.keystore
export KEYSTORE_PASSWORD=<password>
export KEY_ALIAS=xlock
export KEY_PASSWORD=<password>

./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The project is pre-configured to use Java 17 via `gradle.properties` for local builds. CI uses JDK 21.

## Usage

1. Open the app and tap **Scan Badge**
2. Hold the Salto badge to the back of the phone until "X APDU pairs recorded" appears
3. Keep the app in the **foreground** and hold the phone to the locker reader to unlock

The app must be in the foreground to claim the Salto AID from the NFC routing table. It releases the AID as soon as it goes to the background, so the Salto JustIN Mobile app handles door access normally when Un-Locker is not open.

The HCE service works with the screen off (Android 12+) as long as the app is the active foreground app.

## How it works

### Badge scanning

The app puts the NFC adapter into reader mode and communicates with the DeSFire card via `IsoDep.transceive()`. It runs the following command sequence:

- `00 A4 04 00 00` — ISO 7816 SELECT (default application)
- `00 A4 04 00 <AID>` — SELECT the Salto ISO 7816 AID
- `90 60 00 00 00` — DeSFire GET_VERSION (repeated for `AF` continuation)
- `90 6A 00 00 00` — DeSFire GET_APPLICATION_IDS
- For each discovered application:
  - `90 5A 00 00 03 <AID>` — SELECT_APPLICATION
  - `90 6F 00 00 00` — GET_FILE_IDS
  - For each file: GET_FILE_SETTINGS, READ_DATA, READ_RECORDS (with `AF` continuation)

Every (command, response) pair is stored in `SharedPreferences` as an ordered transcript.

### HCE emulation

`HceService` extends `HostApduService`. When the locker reader contacts the phone:

1. Android's NFC stack routes ISO-DEP traffic to the service via the dynamically registered Salto AID
2. Each incoming APDU is looked up in the stored transcript — exact match first, then 5-byte header prefix match
3. The stored response is returned to the reader
4. Unknown APDUs return `6F 00`

### AID routing and JustIN Mobile coexistence

The Salto ISO 7816 AID (`A000000743CC843413925E20C59B0100`) is registered dynamically in `onResume()` via `CardEmulation.registerAidsForService()` and released back to a placeholder in `onPause()`. This means:

- **Un-Locker in foreground** → handles locker NFC traffic
- **Un-Locker in background / closed** → JustIN Mobile handles all Salto traffic normally (door access unaffected)

## Debugging

The app displays an HCE activity log directly on the main screen, updated each time the app is opened. It shows every APDU the locker reader sent and the response returned.

For more detail:

```bash
adb logcat -s HceService
```

Unknown APDUs (no match in transcript) indicate commands the reader sent that weren't captured during the badge scan.

## Findings — why this does not work

This app was built and tested against an XLock locker and Salto JustIN door readers. The HCE infrastructure works correctly (AID routing, service binding, APDU exchange all verified via logcat), but emulation fails at the protocol level for two distinct reasons.

### XLock locker — UID-based, ISO 14443-3 (layer 3)

The locker does not speak ISO-DEP at all. It operates at ISO 14443-3 (layer 3) and simply reads the card's hardware UID. Evidence: tapping the phone to the locker with Un-Locker in the foreground produces zero NFC activity — `HostEmulationManager` is never triggered, meaning the locker never initiates an ISO-DEP session.

This also explains why the locker accepts any NFC-enabled tag during enrolment: it stores the UID of whatever is presented and checks for it on subsequent taps. Android HCE operates exclusively at layer 4 (ISO-DEP) and cannot control the UID presented at layer 3 — Android randomizes it on every tap. There is no way to fix this on stock Android without rooting or custom NFC firmware.

### Salto JustIN door — cryptographic challenge-response

The door uses ISO-DEP and the Salto AID correctly, and our service is called. The exchange proceeds as follows on every tap:

1. `00 A4 04 00 10 A000000743CC843413925E20C59B0100` — SELECT Salto AID → we return the stored response (`9000`) ✓
2. `00 C2 00 00 0A <10-byte nonce>` — cryptographic challenge → we return `6F 00` ✗

The second command contains a fresh random nonce generated by the door on every tap. The real badge computes a signed/MACed response using a secret key stored in protected DeSFire memory (unreadable even with full card access). Without that key, the correct response cannot be computed, and the door rejects the transaction (blinks yellow and retries).

When JustIN Mobile handles the transaction instead, many more APDU exchanges occur successfully — Salto provisioned the app with the actual keys via their backend.

### Summary

| Reader | Protocol layer | Auth mechanism | HCE viable? |
|---|---|---|---|
| XLock locker | ISO 14443-3 (layer 3) | UID match | No — Android randomizes UID |
| Salto JustIN door | ISO-DEP (layer 4) | Crypto challenge-response | No — key not extractable from badge |

## Known limitations

- **UID emulation**: Android HCE presents a randomized hardware UID at the ISO 14443-3 layer. Readers that authenticate by UID only (like the XLock locker) cannot be emulated on stock Android.
- **Cryptographic authentication**: Salto SIDE (offline access control) stores keys on the card and uses DES/AES challenge-response. The static transcript replay cannot answer a fresh challenge without the key.
- **Screen-off HCE**: Requires the `requireDeviceScreenOn="false"` attribute in `hce_service.xml`, supported from Android 12 (API 31). The `minSdk` is set to 31.

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
