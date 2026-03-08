package com.example.tareamov.ui.compose

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class DifficultyLevel(
    val displayName: String,
    val emoji: String,
    val description: String,
    val details: String,
    val color: Color,
    val gradientEnd: Color,
    val stars: Int
) {
    EASY(
        displayName = "Fácil",
        emoji = "🌱",
        description = "Reconocimiento y Memoria",
        details = "Preguntas básicas para recordar e identificar conceptos. Las respuestas son directas y se evalúa la memoria.",
        color = Color(0xFF2ECC71),
        gradientEnd = Color(0xFF27AE60),
        stars = 1
    ),
    INTERMEDIATE(
        displayName = "Intermedio",
        emoji = "⚡",
        description = "Comprensión y Aplicación",
        details = "Interpreta conceptos y aplícalos en situaciones concretas. Sin preguntas repetidas.",
        color = Color(0xFFF39C12),
        gradientEnd = Color(0xFFE67E22),
        stars = 2
    ),
    HARD(
        displayName = "Difícil",
        emoji = "🔥",
        description = "Análisis y Evaluación",
        details = "Analiza, compara y evalúa críticamente. Máxima exigencia sin repetición. Nivel máximo.",
        color = Color(0xFFE74C3C),
        gradientEnd = Color(0xFFC0392B),
        stars = 3
    ),
    FREE(
        displayName = "Libre",
        emoji = "🌌",
        description = "Aprendizaje Libre",
        details = "Preguntas sobre cualquier tema dentro del documento. Explora todo el contenido sin límites.",
        color = Color(0xFF7C4DFF),
        gradientEnd = Color(0xFF448AFF),
        stars = 0
    )
}

@Composable
fun DifficultySelectionScreen(
    courseName: String,
    taskName: String? = null,
    onDifficultySelected: (DifficultyLevel, Boolean) -> Unit,
    onBackClick: () -> Unit
) {
    var freeLearningEnabled by remember { mutableStateOf(false) }
    val infiniteTransition = rememberInfiniteTransition(label = "bgAnim")
    val robotDy by infiniteTransition.animateFloat(
        initialValue = -8f, targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "robotFloat"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "glow"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F0C29), Color(0xFF302B63), Color(0xFF24243E))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top bar ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Nivel de Dificultad",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(48.dp))
            }

            // ── Mascot robot ─────────────────────────────────────
            Box(
                modifier = Modifier
                    .offset(y = robotDy.dp)
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF7C4DFF).copy(alpha = glowAlpha),
                                Color(0xFF3F51B5).copy(alpha = 0.2f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "🤖", fontSize = 52.sp)
            }

            Spacer(Modifier.height(12.dp))

            // ── Title ─────────────────────────────────────────────
            Text(
                text = "¿Qué tan difícil\nquieres jugar?",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                lineHeight = 30.sp
            )
            Spacer(Modifier.height(4.dp))
            if (!taskName.isNullOrBlank()) {
                Text(
                    text = "📝 $taskName",
                    color = Color(0xFFB39DDB),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }

            Spacer(Modifier.height(24.dp))

            FreeLearningToggle(
                enabled = freeLearningEnabled,
                onToggle = { freeLearningEnabled = it }
            )

            Spacer(Modifier.height(20.dp))

            // ── Difficulty cards ──────────────────────────────────
            DifficultyLevel.entries
                .filter { it != DifficultyLevel.FREE }
                .forEachIndexed { idx, level ->
                    DifficultyCard(
                        level = level,
                        animationDelay = idx * 120L,
                        onClick = { onDifficultySelected(level, freeLearningEnabled) }
                    )
                    Spacer(Modifier.height(14.dp))
                }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Puedes cambiar el nivel en cualquier momento",
                color = Color(0xFF7986CB),
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DifficultyCard(
    level: DifficultyLevel,
    animationDelay: Long,
    onClick: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(animationDelay)
        visible = true
    }

    val scale by animateFloatAsState(
        targetValue = when {
            !visible -> 0.7f
            pressed -> 0.96f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "cardScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(350),
        label = "cardAlpha"
    )

    // Subtle pulse for the card border
    val infiniteTransition = rememberInfiniteTransition(label = "cardPulse${level.name}")
    val borderAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800 + level.stars * 200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ), label = "border"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        level.color.copy(alpha = 0.25f),
                        level.gradientEnd.copy(alpha = 0.15f)
                    )
                )
            )
            .border(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        level.color.copy(alpha = borderAlpha),
                        level.gradientEnd.copy(alpha = borderAlpha * 0.7f)
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable {
                pressed = true
                onClick()
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Emoji icon in colored circle
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(level.color.copy(alpha = 0.4f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text = level.emoji, fontSize = 36.sp)
            }

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Level badge + title row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(level.color.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "NIVEL ${level.stars}",
                            color = level.color,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    StarRow(count = level.stars, color = level.color)
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = level.displayName,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp
                )
                Text(
                    text = level.description,
                    color = level.color,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    text = level.details,
                    color = Color(0xFFBDBDBD),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            // Arrow indicator
            Text(
                text = "▶",
                color = level.color.copy(alpha = 0.8f),
                fontSize = 18.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun FreeLearningToggle(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    val infiniteTransition = rememberInfiniteTransition(label = "freeToggle")

    LaunchedEffect(Unit) {
        delay(400L)
        visible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "freeScale"
    )

    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "orbit"
    )

    val borderColor by animateColorAsState(
        targetValue = if (enabled) Color(0xFFB388FF) else Color(0xFF7C4DFF).copy(alpha = 0.4f),
        animationSpec = tween(400),
        label = "borderColor"
    )

    val bgAlpha by animateFloatAsState(
        targetValue = if (enabled) 0.25f else 0.10f,
        animationSpec = tween(400),
        label = "bgAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF7C4DFF).copy(alpha = bgAlpha),
                        Color(0xFF448AFF).copy(alpha = bgAlpha * 0.7f)
                    )
                )
            )
            .border(
                width = 2.dp,
                color = borderColor,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF7C4DFF).copy(alpha = if (enabled) 0.6f else 0.3f),
                                Color.Transparent
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🌌",
                    fontSize = 28.sp,
                    modifier = Modifier.offset(
                        x = (kotlin.math.cos(Math.toRadians(orbitAngle.toDouble())) * 2).dp,
                        y = (kotlin.math.sin(Math.toRadians(orbitAngle.toDouble())) * 2).dp
                    )
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Aprendizaje Libre",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (enabled) Color(0xFF7C4DFF).copy(alpha = 0.5f)
                                else Color(0xFF7C4DFF).copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (enabled) "✨ ACTIVO" else "OFF",
                            color = if (enabled) Color(0xFFB388FF) else Color(0xFF9E9E9E),
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    text = "Genera preguntas sobre todo el contenido del documento, no solo el título o descripción del tema y la tarea.",
                    color = Color(0xFFBDBDBD),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.width(8.dp))

            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFB388FF),
                    checkedTrackColor = Color(0xFF7C4DFF).copy(alpha = 0.5f),
                    uncheckedThumbColor = Color(0xFF9E9E9E),
                    uncheckedTrackColor = Color(0xFF424242)
                )
            )
        }
    }
}

@Composable
private fun StarRow(count: Int, color: Color, total: Int = 3) {
    Row {
        repeat(total) { idx ->
            Icon(
                imageVector = if (idx < count) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = null,
                tint = if (idx < count) color else color.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
