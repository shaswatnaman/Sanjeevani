# Sanjeevani — AR Emergency First-Responder

**iQOO Hackathon 2026** · Android · Kotlin + Jetpack Compose + MediaPipe

Sanjeevani is a real-time AR guide that detects medical emergencies through the camera and talks a bystander through the correct response — CPR, stroke (FAST), heart attack, and allergic reaction / anaphylaxis — until professional help arrives.

*Sanskrit: संजीवनी — the life-restoring herb from the Ramayana.*

---

## What it does

1. **Detect** — MediaPipe pose + hand landmarks classify the emergency from the camera feed
2. **Confirm** — one-tap triage screen lets user select or confirm the emergency type
3. **Guide** — deterministic FSM drives AR overlays + voice coaching specific to the emergency:
   - **CPR**: hand-placement correction circle → real-time compression rate (target 100–120 BPM)
   - **Stroke**: FAST face / arm / speech assessment with visual indicators
   - **Heart attack**: conscious patient protocol (sit, loosen, aspirin, monitor)
   - **Allergic reaction**: timed EpiPen sequence with AR injection site marker

## Architecture

```
Camera (CameraX 720p)
    │
    ▼
MediaPipeController        ← pose + hand landmarks, LIVE_STREAM mode
    │
    ▼
SanjeevaniEngine           ← per-frame coordinator
    ├── EmergencyClassifier    ← visual triage (lying/hand-to-chest/arm-drift)
    ├── EmergencyFSM           ← deterministic state machine, routes to module
    ├── SpatialReasoner        ← CPR hand-placement guidance
    ├── CompressionDetector    ← CPR rate via wrist Y-velocity peaks
    ├── PatientDetector        ← lying-flat detection
    ├── StrokeModule           ← FAST assessment
    ├── AllergicReactionModule ← EpiPen timing sequence
    └── HeartAttackModule      ← conscious patient protocol
    │
    ▼
GuidanceState (StateFlow)
    │
    ▼
AROverlayView (Canvas2D) + TTS voice
```

## Key files

| File | Purpose |
|------|---------|
| `model/Models.kt` | All data classes, enums, EmergencyModule interface |
| `perception/MediaPipeController.kt` | MediaPipe Tasks API wrapper |
| `engine/EmergencyClassifier.kt` | Visual triage — classifies emergency from pose |
| `engine/EmergencyFSM.kt` | State machine — IDLE → TRIAGE → per-emergency protocol |
| `engine/SanjeevaniEngine.kt` | Per-frame coordinator, dispatches to modules |
| `engine/SpatialReasoner.kt` | CPR: sternum target + hand correction vector |
| `engine/CompressionDetector.kt` | CPR: Hanning-filtered rate detection |
| `engine/StrokeModule.kt` | FAST: face/arm/speech assessment |
| `engine/AllergicReactionModule.kt` | Anaphylaxis: timed EpiPen protocol |
| `engine/HeartAttackModule.kt` | Heart attack: sit/loosen/aspirin/monitor |
| `ui/AROverlayView.kt` | Canvas2D AR overlay (skeleton, circles, arrows) |
| `ui/EmergencySelectionScreen.kt` | Compose triage screen — 4 emergency cards |
| `MainActivity.kt` | CameraX + Compose host, navigation |
| `SanjeevaniViewModel.kt` | Bridges engine to UI, TTS voice |

## Build

```bash
# Download MediaPipe models first (one-time, ~13MB total)
./download_models.sh

# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires: Android SDK API 35, JDK 17, device with Android 8+ (API 26)

## Safety design

- **No LLM in the decision path** — all guidance is deterministic FSM + MediaPipe geometry
- Medical protocols follow Indian Red Cross / AHA 2020 guidelines
- Emergency call is always shown; app never delays calling 112

## Branches

| Branch | Owner | Purpose |
|--------|-------|---------|
| `main` | — | Stable; CPR module complete and tested |
| `feature/person-a-engine` | Person A (Claude) | Multi-emergency engine modules |
| `feature/person-b-ui` | Person B (Codex) | Triage UI + overlay updates |

## Assets

MediaPipe models in `app/src/main/assets/` (not committed — run `download_models.sh`):
- `pose_landmarker_lite.task` (5.5 MB)
- `hand_landmarker.task` (7.5 MB)
