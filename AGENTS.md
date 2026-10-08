# Sanjeevani — AI Agent Context (OpenAI Codex / Cursor)

This file is read by OpenAI Codex and other AI coding agents. It provides everything needed to work on this project.

## Project overview

Android AR emergency first-responder app — Kotlin, Jetpack Compose, CameraX, MediaPipe.
**Package**: `com.sanjeevani` | **minSdk**: 26 | **compileSdk**: 35 | **Kotlin**: 2.0.21

The app guides bystanders through CPR, stroke (FAST), heart attack, and allergic reaction protocols using the phone camera + real-time AR overlays.

## Branch for Person B (UI / Codex)

**Work on branch**: `feature/person-b-ui`

```bash
git checkout feature/person-b-ui
```

## Files Person B owns — do NOT edit Person A's files

| You write | You must NOT edit |
|-----------|-------------------|
| `ui/EmergencySelectionScreen.kt` (new) | `model/Models.kt` |
| `engine/HeartAttackModule.kt` (new) | `engine/EmergencyClassifier.kt` |
| `ui/AROverlayView.kt` (add methods only) | `engine/StrokeModule.kt` |
| `MainActivity.kt` (add navigation) | `engine/AllergicReactionModule.kt` |
| `SanjeevaniViewModel.kt` (add 3 functions) | `engine/EmergencyFSM.kt` |
| | `engine/SanjeevaniEngine.kt` |

## Types Person B codes against (from Models.kt — Person A adds these)

If Person A hasn't merged yet, create stubs in a local `Stubs.kt` file in the same package — they compile fine:

```kotlin
// com/sanjeevani/model/Stubs.kt (delete after Person A merges)
package com.sanjeevani.model

// Already in Models.kt — these are the NEW additions:
enum class TriageState { OBSERVING, CAMERA_SUGGESTS, USER_CONFIRMED }
enum class AllergicPhase { LAY_FLAT, RAISE_LEGS, SAFE_POSITION, EPIPEN_READY, EPIPEN_INJECT, MONITORING }
data class ClassifierSignal(val emergencyType: EmergencyType, val confidence: Float, val visualSignals: List<String>, val suggestedByCamera: Boolean)
data class ModuleResult(val voiceText: String?, val overlay: AROverlaySpec, val isComplete: Boolean = false)
interface EmergencyModule { fun process(frame: PerceptionFrame, nowMs: Long): ModuleResult; fun reset() }
// EmergencyType already has: CPR, FAST_STROKE, CHOKING, UNKNOWN — Person A will change CHOKING→HEART_ATTACK and add ALLERGIC_REACTION
// FSMState will have: TRIAGE_DETECTION, STROKE_FAST_TEST, HEART_ATTACK_CONSCIOUS, ALLERGIC_PROTOCOL added by Person A
// AROverlaySpec will gain: emergencyType, phaseProgress, showEpiPenMarker, leftShoulderY, rightShoulderY
// GuidanceState will gain: triageState, classifierSignal
```

## Task B1 — EmergencySelectionScreen.kt

File: `app/src/main/java/com/sanjeevani/ui/EmergencySelectionScreen.kt`

Fullscreen Compose triage screen. Called from MainActivity when `fsmState == FSMState.TRIAGE_DETECTION`.

```kotlin
@Composable
fun EmergencySelectionScreen(
    classifierSuggestion: EmergencyType?,
    classifierConfidence: Float,
    onEmergencySelected: (EmergencyType) -> Unit
)
```

- Dark semi-transparent background (Color(0xD9000000))
- Title: "WHAT IS HAPPENING?"
- Optional banner when classifierSuggestion != null && confidence > 0.4f: "Camera detects: [name]" with LinearProgressIndicator
- 2×2 grid of 4 emergency cards (LazyVerticalGrid, Fixed(2))
- Each card: emoji + name + one-line description, border highlights if it matches suggestion
- Colors: CPR=0xFFff6b6b, STROKE=0xFFa78bfa, HEART_ATTACK=0xFFf97316, ALLERGIC=0xFF4ecca8
- Card content: CPR(❤️,"Heart Stopped","Not breathing or unconscious"), FAST_STROKE(🧠,"Stroke","Face droop, arm weakness, slurred speech"), HEART_ATTACK(🫀,"Heart Attack","Chest pain, still conscious"), ALLERGIC_REACTION(💉,"Allergic Reaction","Severe allergy / EpiPen needed")

## Task B2 — HeartAttackModule.kt

File: `app/src/main/java/com/sanjeevani/engine/HeartAttackModule.kt`

Implements `EmergencyModule`. Time-based protocol using `nowMs` parameter (no coroutines, no Handlers).

Phases (elapsed seconds from first process() call):
- 0–10s: SIT_DOWN — "Sit or lie down. Do not walk."
- 10–20s: LOOSEN_CLOTHING — "Loosen collar, belt, chest clothing."
- 20–35s: ASPIRIN_PROMPT — "Give 300mg aspirin to chew if not allergic."
- 35s+: MONITORING — "Help is on the way. Keep them calm."

Auto-escalate: if pose landmarks show patient now lying flat (L/R shoulder spanX > spanY/0.7), return `isComplete=true` with voice "They have become unresponsive. Begin CPR immediately."

Speak voice only when phase changes (track `lastVoicePhase`). Numbers spoken as words.

## Task B3 — AROverlayView.kt additions

File: `app/src/main/java/com/sanjeevani/ui/AROverlayView.kt`

Add 4 new private drawing methods. Call them at the START of `onDraw()`, before all existing CPR code. Do NOT remove or change any existing drawing code.

New AROverlaySpec fields to use (Person A adds to Models.kt):
- `emergencyType: EmergencyType` — for badge color
- `phaseProgress: Float` — 0..1 progress bar
- `showEpiPenMarker: Boolean` — pulsing orange circle at sternumTarget
- `leftShoulderY: Float`, `rightShoulderY: Float` — stroke asymmetry bars

Methods to add:
1. `drawEmergencyBadge(canvas)` — top-left pill, color per emergency type, only for non-CPR/non-UNKNOWN
2. `drawEpiPenMarker(canvas)` — pulsing orange circle at sternumTarget when showEpiPenMarker=true; calls postInvalidateOnAnimation()
3. `drawPhaseProgressBar(canvas)` — thin bar at y=height*0.85, width*phaseProgress filled
4. `drawStrokeAsymmetryBars(canvas)` — two vertical lines at shoulder X positions when FAST_STROKE

Emergency colors: FAST_STROKE=Color(0xFFa78bfa), HEART_ATTACK=Color(0xFFf97316), ALLERGIC_REACTION=Color(0xFF4ecca8)

## Task B4 — MainActivity.kt

File: `app/src/main/java/com/sanjeevani/MainActivity.kt`

Critical bug fix + new UI states:

**BUG FIX (required)**: In `startCamera()`, add this line before `bindToLifecycle()`:
```kotlin
val preview = Preview.Builder().build().also {
    it.setSurfaceProvider(previewView.surfaceProvider)  // THIS IS MISSING — add it
}
```

New Compose UI (in the main Box):
1. Collect `val guidance by viewModel.guidanceState.collectAsState()`
2. When `guidance.fsmState == FSMState.TRIAGE_DETECTION`: show `EmergencySelectionScreen` on top of everything, dismiss automatically when state changes
3. When `guidance.fsmState == FSMState.STROKE_FAST_TEST`: show two pill buttons — "✓ Speech Clear" (green, calls `viewModel.onStrokeSpeechResult(true)`) and "✗ Speech Slurred" (red, calls `viewModel.onStrokeSpeechResult(false)`)
4. Change any `initialize()` in `onCreate` to `viewModel.ensureInitialized()` called after permission confirmed

## Task B5 — SanjeevaniViewModel.kt

File: `app/src/main/java/com/sanjeevani/SanjeevaniViewModel.kt`

Add 3 things only — do not change any existing logic:

```kotlin
// 1. Guard initialize() with isInitialized flag; expose ensureInitialized()
private var isInitialized = false
fun ensureInitialized() { if (!isInitialized) { isInitialized = true; initialize() } }

// 2. New delegation functions
fun onEmergencySelected(type: EmergencyType) { engine.setUserSelectedEmergency(type) }
fun onStrokeSpeechResult(positive: Boolean) { engine.onStrokeSpeechResult(positive) }

// 3. Dynamic voice throttle
val voiceInterval = when (_guidanceState.value.emergencyType) {
    EmergencyType.ALLERGIC_REACTION, EmergencyType.HEART_ATTACK -> 5000L
    else -> 2500L
}
```

## Build commands

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Important rules

1. Emergency number is 112 (India), not 911
2. Voice text uses words not digits: "1 1 2", "100 to 120 per minute"
3. No LLM calls at runtime — all guidance must be deterministic
4. MediaPipe models are in `app/src/main/assets/` — NOT in git. Run `./download_models.sh` to get them
5. Landmarks are NormalizedLandmark(x, y, z, visibility) in 0..1 screen coords
6. Key pose indices: L_SHOULDER=11, R_SHOULDER=12, L_WRIST=15, R_WRIST=16, L_HIP=23, R_HIP=24

## Commit style

```
feat(ui): add emergency selection screen with 4-card triage grid
fix(camera): call setSurfaceProvider before bindToLifecycle
feat(engine): add heart attack conscious patient module
```
