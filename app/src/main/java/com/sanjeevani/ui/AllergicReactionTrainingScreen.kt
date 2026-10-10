package com.sanjeevani.ui

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sanjeevani.model.AllergicPhase
import kotlinx.coroutines.delay
import java.util.Locale

// Phase start times in seconds (mirrors AllergicReactionModule timing)
private val PHASE_START_SECS = intArrayOf(0, 17, 38, 48, 51, 66)
private val PHASE_DURATIONS   = intArrayOf(17, 21, 10, 3, 15, 60)

private data class AllergicStep(
    val phase: AllergicPhase,
    val title: String,
    val instruction: String,
    val note: String,
    val emoji: String,
    val color: Color
)

private val STEPS = listOf(
    AllergicStep(
        AllergicPhase.LAY_FLAT,
        "Lay Flat",
        "Lay them flat on the ground. Do not let them stand up.",
        "Lying flat counters the drop in blood pressure during anaphylaxis.",
        "🔴", Color(0xFFDC2626)
    ),
    AllergicStep(
        AllergicPhase.RAISE_LEGS,
        "Raise Legs",
        "Raise their legs above heart level. This helps blood flow to the brain.",
        "Elevated legs improve blood return to the heart and brain. Use a bag or rolled jacket.",
        "🟠", Color(0xFFEA580C)
    ),
    AllergicStep(
        AllergicPhase.SAFE_POSITION,
        "Safe Position",
        "They are stable. Move them to the recovery position if they remain conscious.",
        "Recovery position protects the airway and is appropriate if the person is conscious.",
        "🟢", Color(0xFF16A34A)
    ),
    AllergicStep(
        AllergicPhase.EPIPEN_READY,
        "EpiPen Ready",
        "Get the EpiPen ready. Remove the blue safety cap.",
        "Act quickly — epinephrine is the first-line treatment for anaphylaxis. Never delay.",
        "⚡", Color(0xFFD97706)
    ),
    AllergicStep(
        AllergicPhase.EPIPEN_INJECT,
        "Inject EpiPen",
        "Inject EpiPen into the outer mid-thigh. Hold firmly for 10 seconds.",
        "You can inject through clothing. A second dose may be given 5–15 min later if symptoms persist.",
        "💉", Color(0xFFDC2626)
    ),
    AllergicStep(
        AllergicPhase.MONITORING,
        "Monitor",
        "Monitor breathing and stay with them. Help is on the way. Call 1 1 2 now if not already done.",
        "Watch for improvement or worsening. Do not leave them alone until emergency services arrive.",
        "👁️", Color(0xFF0891B2)
    )
)

@Composable
fun AllergicReactionTrainingScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // Timer: elapsed seconds from session start
    var elapsedSecs by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }

    // TTS initialization — disposed when screen leaves composition
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance?.language = Locale.US
                instance?.setSpeechRate(0.95f)
                tts = instance
            }
        }
        onDispose { instance?.stop(); instance?.shutdown(); tts = null }
    }

    // Derive current step index from elapsed time
    val stepIndex = remember(elapsedSecs) {
        (PHASE_START_SECS.indexOfLast { elapsedSecs >= it }).coerceAtLeast(0)
    }
    val isLastStep = stepIndex == STEPS.lastIndex
    val step = STEPS[stepIndex]

    // Speak each phase instruction exactly once, after TTS is ready
    val spoken = remember { mutableSetOf<Int>() }
    LaunchedEffect(stepIndex, tts) {
        val t = tts ?: return@LaunchedEffect
        if (started && stepIndex !in spoken) {
            spoken.add(stepIndex)
            t.speak(step.instruction, TextToSpeech.QUEUE_FLUSH, null, "phase_$stepIndex")
        }
    }

    // Auto-start timer and speak first instruction when TTS becomes ready
    LaunchedEffect(Unit) {
        delay(300L)
        started = true
        while (true) {
            delay(1000L)
            elapsedSecs++
        }
    }

    // Speak first instruction once TTS initializes (may race with timer LaunchedEffect above)
    LaunchedEffect(tts) {
        val t = tts ?: return@LaunchedEffect
        if (0 !in spoken) {
            spoken.add(0)
            t.speak(STEPS[0].instruction, TextToSpeech.QUEUE_FLUSH, null, "phase_0")
        }
    }

    // Current phase progress
    val phaseElapsed = elapsedSecs - PHASE_START_SECS[stepIndex]
    val phaseDuration = PHASE_DURATIONS[stepIndex]
    val phaseRemaining = (phaseDuration - phaseElapsed).coerceAtLeast(0)
    val phaseProgress = (phaseElapsed.toFloat() / phaseDuration).coerceIn(0f, 1f)

    // Animated accent color transitions between phases
    val accentColor by animateColorAsState(
        targetValue = step.color,
        animationSpec = tween(600),
        label = "phase_accent"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080E1C))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { tts?.stop(); onBack() }) {
                Text("← Back", color = Color(0xFF7A8899), fontSize = 14.sp)
            }
            Text(
                "Allergic Reaction Training",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            TextButton(onClick = {
                tts?.stop()
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
                )
            }) {
                Text("📞 112", color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Phase dots
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            STEPS.forEachIndexed { i, _ ->
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(if (i == stepIndex) 12.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                i < stepIndex -> Color(0xFF4ADE80)
                                i == stepIndex -> accentColor
                                else -> Color(0xFF1E2840)
                            }
                        )
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        // Main instruction card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = accentColor.copy(alpha = 0.12f)),
            border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(step.emoji, fontSize = 30.sp)
                    Column {
                        Text(
                            "STEP ${stepIndex + 1} OF ${STEPS.size}",
                            fontSize = 10.sp,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            step.title,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
                Text(
                    step.instruction,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = Color(0xFFD1D5DB),
                    fontWeight = FontWeight.Medium
                )
                // Safety note box
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A1020), RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text("ℹ️", fontSize = 13.sp)
                    Text(
                        step.note,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFF7A8899)
                    )
                }
            }
        }

        // Phase timer bar (hidden on last step which has no fixed end)
        if (!isLastStep) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Next step in", fontSize = 12.sp, color = Color(0xFF5A6580))
                    Text(
                        "${phaseRemaining}s",
                        fontSize = 13.sp,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
                LinearProgressIndicator(
                    progress = { phaseProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = accentColor,
                    trackColor = Color(0xFF1E2840)
                )
            }
        }

        // Step checklist
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1526), RoundedCornerShape(16.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "PROTOCOL",
                fontSize = 10.sp,
                color = Color(0xFF3A4560),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp
            )
            STEPS.forEachIndexed { i, s ->
                val done   = i < stepIndex
                val active = i == stepIndex
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    done   -> Color(0xFF4ADE80).copy(alpha = 0.2f)
                                    active -> accentColor.copy(alpha = 0.2f)
                                    else   -> Color(0xFF0A1020)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (done) "✓" else "${i + 1}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                done   -> Color(0xFF4ADE80)
                                active -> accentColor
                                else   -> Color(0xFF2E3A50)
                            }
                        )
                    }
                    Text(
                        s.title,
                        fontSize = 14.sp,
                        color = when {
                            done   -> Color(0xFF4ADE80)
                            active -> Color.White
                            else   -> Color(0xFF2E3A50)
                        },
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                    )
                    if (active) {
                        Text(
                            "← now",
                            fontSize = 11.sp,
                            color = accentColor.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // End Training button (visible only on last step)
        if (isLastStep) {
            Button(
                onClick = { tts?.stop(); onBack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C4B4)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    "End Training",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF080E1C)
                )
            }
        }

        // Disclaimer
        Text(
            "Training simulation only. Always call 112 in a real emergency. " +
            "Do not replace professional first-aid training.",
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = Color(0xFF2E3A50),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
