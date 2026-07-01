# Smart Auto Clicker

A smart Android auto-clicker that uses ML Kit OCR to **detect text on screen** and automatically tap it — no root required.

## Features

- **Display Over Apps** — draggable floating Start/Stop button overlaid on any app
- **Accessibility Service** — performs real screen taps via Android's GestureDescription API
- **Screen Capture** — uses MediaProjection to take screenshots every ~300ms
- **ML Kit Text Recognition** — detects configured text targets (e.g. "Via", "Homepage") in real time
- **Auto-coordinate** — calculates the center of the detected text bounding box and taps it
- **Configurable targets** — add/remove text targets with custom delays between taps

## How it works

```
Screenshot → ML Kit OCR → Find "Via" → Tap center → Wait → Find "Homepage" → Tap → repeat
```

## Setup

1. Install the APK
2. Open Smart Auto Clicker
3. Grant **Display Over Apps** permission
4. Enable **Smart Auto Clicker** in Accessibility Settings
5. Tap **Manage Targets** — add your text targets in order:
   - Label: `Via`, Text: `Via`
   - Label: `Homepage`, Text: `Homepage`
6. Tap **Start Service** — grant screen capture when prompted
7. The floating button appears on your screen
8. Press **START** on the floating button to begin detection
9. Press **STOP** to halt

## Permissions Required

| Permission | Purpose |
|---|---|
| `SYSTEM_ALERT_WINDOW` | Show floating button over other apps |
| Accessibility Service | Perform synthetic screen taps |
| `MEDIA_PROJECTION` | Capture screen for OCR analysis |
| `FOREGROUND_SERVICE` | Keep detection running in background |

## Building

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Requirements

- Android 8.0+ (API 26+)
- ~50MB storage (ML Kit model downloaded on first use)

## Architecture

```
MainActivity                  — Permission setup UI
FloatingOverlayService        — Foreground service + draggable overlay button
AutoClickAccessibilityService — Performs GestureDescription taps
ScreenCaptureManager          — MediaProjection → Bitmap frames
DetectionEngine               — ML Kit OCR → find text → return coordinates
TargetRepository              — SharedPreferences persistence for targets
```
