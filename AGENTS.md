# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

WatchRSS Phone is an Android companion app for the OPPO Watch RSS reader. The app supports a single formal connection flow: Bluetooth sync over Android public RFCOMM APIs. The watch opens a short-lived RFCOMM server and the phone connects to the already paired watch to exchange the local article library, favorites, and watch-later data.

Do not reintroduce QR scanning, manual IP/port WiFi connection, acoustic-guided WiFi, or pure-sound data transfer as app connection modes unless explicitly requested.

## Technology Stack

- **Language**: Kotlin 2.0.21
- **UI Framework**: Jetpack Compose with Material3
- **Build System**: Gradle 8.13 with Kotlin DSL
- **Min SDK**: 30 (Android 11)
- **Target SDK**: 34 (Android 14)
- **Key Libraries**:
  - Android Bluetooth RFCOMM APIs for the phone-watch sync channel
  - Room for local article, favorites, and watch-later storage
  - OkHttp + Jsoup for phone-side webpage import and readable article extraction
  - Coil for image loading
  - ZXing Core for contact QR code generation

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

1. **MainActivity**: Entry point; requests Bluetooth permissions and hosts the Compose main screen.
2. **MainScreen**: Provides webpage import and Bluetooth sync actions:
   - import a webpage into local favorites
   - import a webpage into local watch-later
   - run one manual bidirectional sync with the watch

### Bluetooth Sync Layer

- **PhoneBluetoothSyncManager** builds product actions, exports local articles, and merges watch responses.
- **PhoneBluetoothSyncClient** selects the bonded watch and exchanges one RFCOMM JSON request/response.
- **BluetoothSyncProtocol** defines the shared service UUID, action names, max frame size, and length-prefixed JSON frame format.
- Product actions:
  - `remoteInput` - phone sends the RSS URL entered on the phone
  - `pullSavedItems` - phone pulls favorites or watch-later items from the watch
  - `syncLibrary` - phone and watch exchange compressed article records plus per-list save state

### Data Layer

- **PhoneCompanionRepository** is local-data focused:
  - imports webpages into `phone_articles`
  - observes local favorites and watch-later articles from Room
  - merges incoming Bluetooth article state with per-list last-writer-wins timestamps
- Saved item models live under `data/db` and `data/model`.

### UI Theme

- Custom `WatchRssPhoneTheme` with Material3.
- Chinese language is used in UI strings (app name: "腕上RSS").

### Inbound File Handling and Error Visibility

The app registers `application/octet-stream` in its intent-filters so that the system delivers unsupported files (e.g. `.apk.1`) to `HomeActivity`. A two-layer validation model is used:

1. **Manifest layer** — broad MIME acceptance (`text/*`, `application/epub+zip`, `application/octet-stream`) so the Intent reaches the app.
2. **Code layer** — `isSupportedLocalContent` in `HomeActivity` and `LocalContentImporter.importFile` apply a strict whitelist (`.txt`, `.epub`, or matching MIME) and reject everything else.

**Critical design rule**: errors from inbound (external) intents must use `MainViewModel.showContentError`, not `showError`. `showContentError` emits via `_toastEvent` (a `SharedFlow` collected by a top-level `LaunchedEffect` in `HomeActivity`) which renders a Toast that is visible regardless of which `HorizontalPager` page is currently composed. `showError` only writes to `sessionState.error`, which is rendered exclusively inside `ImportActionsCard` on the Imports page — invisible on cold start when the user lands on the Dashboard page (page 0) and `HorizontalPager` does not compose off-screen pages.

| Error source | Correct method | Why |
|---|---|---|
| External intent (cold start / share) | `showContentError` | Toast is page-independent |
| In-app file picker (`importLocalContentLauncher`) | `showError` | User is already on the Imports page |
| In-app file picker read failure | `showError` | Same as above |

## Code Conventions

- Package structure: `com.lightningstudio.watchrss.phone`
- Activities use Jetpack Compose exclusively (no XML layouts).
- Keep connection UI and ViewModel logic scoped to Bluetooth sync.
- Avoid adding camera, QR scanner, record-audio, WiFi hotspot, or direct watch HTTP client dependencies unless the product direction changes.

## Important Notes

- This is a phone companion app; the watch app runs separately on OPPO Watch.
- Communication uses already paired Bluetooth and a short-lived watch-side RFCOMM listener.
- Required runtime permission on Android 12+ is `BLUETOOTH_CONNECT`.
- Do not add OPPO/HeyTap closed SDK dependencies; this is a GPLv3-compatible public-API implementation.
