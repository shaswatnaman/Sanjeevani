# Sanjeevani — Claude Code Context

This file is read automatically by Claude Code. It contains everything needed to work on this project without re-reading all source files every session.

## Project

Android AR emergency first-responder app. Kotlin + Jetpack Compose + CameraX + MediaPipe Tasks API. No external AI/LLM at runtime — all guidance is deterministic. Built for iQOO Hackathon 2026.

Package: `com.sanjeevani` · compileSdk 35 · minSdk 26 · Kotlin 2.0.21 · AGP 8.7.0

## What is already built and working

The **CPR module is complete** (built in a prior session, APK verified):
- MediaPipe pose + hand detection at 30fps via CameraX 720p
- Patient detection (lying-flat pose formula from iOS source)
- Sternum target calculation + hand-placement correction arrows (AR overlay)
- Compression rate detection via Hanning-filtered wrist Y-velocity peaks
- Deterministic FSM: IDLE → SCENE_ASSESSMENT → RESPONSIVENESS_CHECK → CPR path
- Canvas2D AR overlay (skeleton, target circle, correction arrows)
- Android TTS voice guidance with 2500ms throttle

## What is NOT yet built (active tasks)

See GitHub issues for current status. Summary:
- `engine/EmergencyClassifier.kt` — visual triage from pose landmarks
- `engine/StrokeModule.kt` — FAST assessment protocol  
- `engine/AllergicReactionModule.kt` — anaphylaxis EpiPen sequence (port from iOS)
- `engine/HeartAttackModule.kt` — conscious patient protocol (Person B)
- `ui/EmergencySelectionScreen.kt` — 4-button triage Compose screen (Person B)
- `EmergencyFSM.kt` update — add triage + routing (Person A)
- `SanjeevaniEngine.kt` update — dispatch to modules (Person A)
- `AROverlayView.kt` update — multi-emergency overlays (Person B)
- `MainActivity.kt` update — navigation + PreviewView bug fix (Person B)

## Branch ownership

- `feature/person-a-engine` — Person A's branch (engine files)
- `feature/person-b-ui` — Person B's branch (UI files)
- Never commit to both branches from the same session without coordinating

## Interface contract (types both branches depend on)

Person A owns Models.kt. These types must be added there:

```kotlin
// Expand EmergencyType to:
enum class EmergencyType { CPR, FAST_STROKE, HEART_ATTACK, ALLERGIC_REACTION, UNKNOWN }

// Add to FSMState: TRIAGE_DETECTION, STROKE_FAST_TEST, HEART_ATTACK_CONSCIOUS, ALLERGIC_PROTOCOL

enum class TriageState { OBSERVING, CAMERA_SUGGESTS, USER_CONFIRMED }
enum class AllergicPhase { LAY_FLAT, RAISE_LEGS, SAFE_POSITION, EPIPEN_READY, EPIPEN_INJECT, MONITORING }

data class ClassifierSignal(
    val emergencyType: EmergencyType,
    val confidence: Float,
    val visualSignals: List<String>,
    val suggestedByCamera: Boolean
)
data class StrokeSignals(
    val faceAsymmetryScore: Float, val armDriftDetected: Boolean,
    val speechPrompted: Boolean, val positiveTestCount: Int
)
data class ModuleResult(val voiceText: String?, val overlay: AROverlaySpec, val isComplete: Boolean = false)
interface EmergencyModule { fun process(frame: PerceptionFrame, nowMs: Long): ModuleResult; fun reset() }

// Add to AROverlaySpec: emergencyType, phaseProgress, showEpiPenMarker, leftShoulderY, rightShoulderY
// Add to GuidanceState: triageState, classifierSignal
```

## Key algorithms — preserve exactly

**Sternum target** (SpatialReasoner.kt):
```kotlin
sternumY = shoulderMidY + (hipMidY - shoulderMidY) * 0.35f
```

**Lying-flat detection** (PatientDetector.kt — from iOS BodySkeleton.swift):
```kotlin
lying = shoulderSpanX > 0.12f && shoulderSpanX > shoulderSpanY / 0.7f
// Original iOS: horizontalComponent > verticalComponent * 0.7
```

**Spatial hysteresis** (SpatialReasoner.kt):
```kotlin
CORRECTION_THRESHOLD = 0.04f
HYSTERESIS = 0.01f
MIN_ACTION_CHANGE_MS = 500L
EMA alpha = 0.3f
```

**Compression detection** (CompressionDetector.kt):
```kotlin
BUFFER_SIZE = 90  // 3s at 30fps
MIN_EVENT_GAP_MS = 300L
RATE_WINDOW_MS = 10000L
Hanning window, peak prominence > 0.05f
```

## MediaPipe landmark indices

Pose (33 landmarks):
```
NOSE=0  L_SHOULDER=11  R_SHOULDER=12
L_ELBOW=13  R_ELBOW=14  L_WRIST=15  R_WRIST=16
L_HIP=23  R_HIP=24  L_KNEE=25  R_KNEE=26
```

Hands (21 landmarks per hand):
```
WRIST=0  THUMB_TIP=4  INDEX_MCP=5  MIDDLE_MCP=9  RING_MCP=13  PINKY_MCP=17
```

MediaPipe handedness flip: when MediaPipe says "Left" it means the camera-right hand (mirror flip). Already handled in MediaPipeController.kt.

Landmarks are NormalizedLandmark(x, y, z, visibility) in 0..1 screen coordinates.

## iOS source available for porting

`/Users/naman/Downloads/iqoo final/Halo/Halo-HackHarvard/SafeStepAR/`

Key files:
- `AllergicReactionView.swift` — exact timing sequence for AllergicReactionModule
- `BodySkeleton.swift` — posture detection (already ported to PatientDetector.kt)

**Security note**: iOS `Config.swift` contains an ElevenLabs API key. Do NOT use it. User must revoke it.

## Build commands

```bash
# From project root:
./gradlew assembleDebug            # build APK
./gradlew assembleDebug --info     # verbose
adb install app/build/outputs/apk/debug/app-debug.apk
```

SDK path is `/Users/naman/Library/Android/sdk` (set in local.properties — not committed).

## Safety rules

1. **No LLM calls in the decision path** — all guidance must be deterministic FSM + MediaPipe geometry
2. Never hardcode the ElevenLabs key from Config.swift
3. MediaPipe models are in assets/ — not committed to git (too large). Run `./download_models.sh`
4. All emergency protocols follow Indian Red Cross / AHA 2020 guidelines
5. Emergency number is 112 (India), not 911

## Known bugs to fix

- `MainActivity.kt`: `preview.setSurfaceProvider(previewView.surfaceProvider)` is not called — camera shows black screen. Person B fixes this.
- `SanjeevaniViewModel.kt`: `initialize()` is called in `init{}` before camera permission — should use `ensureInitialized()` pattern. Person B fixes this.

## Voice guidance rules

- Android TTS, voice throttle 2500ms for CPR, 5000ms for Allergic/HeartAttack
- Numbers spoken as words: "1 1 2" not "112", "100 to 120" not "100-120"
- Hindi/Hinglish not required for MVP but welcome in V2
