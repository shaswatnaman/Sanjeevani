package com.sanjeevani.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class TrainingModule(
    val id: String,
    val title: String,
    val subtitle: String,
    val emoji: String,
    val available: Boolean
)

private val modules = listOf(
    TrainingModule("cpr",      "CPR",               "Cardiopulmonary Resuscitation",    "❤️",  true),
    TrainingModule("allergy",  "Allergic Reaction", "Anaphylaxis & EpiPen Protocol",    "💉",  true),
    TrainingModule("aed",      "AED",               "Automated External Defibrillator", "⚡",  false),
    TrainingModule("choking",  "Choking",           "First Aid for Choking",             "🫁",  false),
    TrainingModule("recovery", "Recovery Position", "Safe Lateral Position",             "🛌",  false),
)

@Composable
fun TrainingHomeScreen(
    onStartCpr: () -> Unit,
    onStartAllergicReaction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF080E1C))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        AppHeader()
        SectionLabel("Training Modules")
        ModuleGrid(onStartCpr, onStartAllergicReaction)
        DisclaimerText()
    }
}

@Composable
private fun AppHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "🩺",
                fontSize = 28.sp
            )
            Column {
                Text(
                    text = "Sanjeevani",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "AI-Powered Emergency Skills",
                    fontSize = 13.sp,
                    color = Color(0xFF00C4B4),
                    letterSpacing = 0.3.sp
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF5A6580),
        letterSpacing = 1.2.sp
    )
}

@Composable
private fun ModuleGrid(
    onStartCpr: () -> Unit,
    onStartAllergicReaction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        modules.chunked(2).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { module ->
                    val action: (() -> Unit)? = when (module.id) {
                        "cpr"     -> onStartCpr
                        "allergy" -> onStartAllergicReaction
                        else      -> null
                    }
                    TrainingCard(
                        module = module,
                        onStart = action,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrainingCard(
    module: TrainingModule,
    onStart: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121929)),
        border = if (module.available)
            BorderStroke(1.dp, Color(0xFF00C4B4).copy(alpha = 0.35f))
        else
            BorderStroke(1.dp, Color(0xFF1E2840))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(module.emoji, fontSize = 30.sp)

            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = module.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (module.available) Color.White else Color(0xFF3E4A60)
                )
                Text(
                    text = module.subtitle,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if (module.available) Color(0xFF7A8899) else Color(0xFF2E3A50)
                )
            }

            if (module.available && onStart != null) {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C4B4)),
                    shape = RoundedCornerShape(9.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = "Start Training",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF080E1C)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .background(Color(0xFF0E1624), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Coming Soon",
                        fontSize = 12.sp,
                        color = Color(0xFF2E3A50),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun DisclaimerText() {
    Text(
        text = "Training simulations do not replace certified first-aid courses. Always call 112 in a real emergency.",
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = Color(0xFF3A4560),
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
