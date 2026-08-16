# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

WatchRSS Phone is an Android companion app for the OPPO Watch RSS reader. The app supports a single formal connection flow: Bluetooth sync over Android public RFCOMM APIs. The phone imports webpages into a local article library, then manually syncs articles, favorites, and watch-later data with the already paired watch.

Do not reintroduce QR scanning, manual IP/port WiFi connection, acoustic-guided WiFi, or pure-sound data transfer as app connection modes unless explicitly requested.

## Technology Stack

- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose with Material3
- **Build System**: Gradle 8.13 with Kotlin DSL
- **Min SDK**: 30 (Android 11)
- **Target SDK**: 36 (Android 16)
- **Key Libraries**:
  - Android Bluetooth RFCOMM APIs for the phone-watch sync channel
  - Room for local article, favorites, and watch-later storage
  - OkHttp + Jsoup for webpage import and readable article extraction
  - Coil for image loading
  - ZXing Core for contact QR code generation
  - OPPO Push SDK (com.heytap.msp; console-downloaded aar in app/libs, maven 3.0.0 fallback) for OPPO system push

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug build to connected device
./gradlew installDebug

# Clean build
./gradlew clean

# Run lint checks
./gradlew lint
```

## Architecture

### Activity Flow

1. **MainActivity**: Entry point; handles share/view intents, requests Bluetooth permissions, and hosts the Compose main screen.
2. **MainScreen**: Provides webpage import and Bluetooth sync actions:
   - import a webpage into local favorites
   - import a webpage into local watch-later
   - run one manual bidirectional sync with the watch

### Bluetooth Sync Layer

- **PhoneBluetoothSyncManager** builds product actions, exports local articles, and merges watch responses.
- **PhoneBluetoothSyncClient** selects the bonded watch and exchanges one RFCOMM JSON request/response.
- **BluetoothSyncProtocol** defines the shared service UUID, action names, max frame size, and length-prefixed JSON frame format.
- Product action `syncLibrary` exchanges compressed article records plus per-list save state.

### Data Layer

- **PhoneCompanionRepository** is local-data focused:
  - imports webpages into `phone_articles`
  - observes local favorites and watch-later articles from Room
  - merges incoming Bluetooth article state with per-list last-writer-wins timestamps
- Article and saved item models live under `data/db` and `data/model`.

### UI Theme

- Custom `WatchRssPhoneTheme` with Material3.
- Chinese language is used in UI strings (app name: "腕上RSS").

## Code Conventions

- Package structure: `com.lightningstudio.watchrss.phone`
- Activities use Jetpack Compose exclusively (no XML layouts).
- Keep connection UI and ViewModel logic scoped to Bluetooth sync.
- Avoid adding camera, QR scanner, record-audio, WiFi hotspot, or direct watch HTTP client dependencies unless the product direction changes.

## Important Notes

- This is a phone companion app; the watch app runs separately on OPPO Watch.
- Communication uses already paired Bluetooth and a short-lived watch-side RFCOMM listener.
- Required runtime permission on Android 12+ is `BLUETOOTH_CONNECT`.
- The app is MIT-licensed. The official closed-source OPPO Push SDK (com.heytap.msp) is an approved exception for push notifications; do not add other closed-source dependencies without approval.
- Push credentials are wired via the `productionSetting()` mechanism (local.properties / gradle property / env var under `WATCHRSS_OPPO_PUSH_APP_KEY` and `WATCHRSS_OPPO_PUSH_APP_SECRET`); never hard-code them.
