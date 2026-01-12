package com.example.tareamov.ui.compose

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.example.tareamov.service.TTSService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.launch

@Composable
fun EvaluativeReinforcementScreen(
    onCourseSelectionClick: () -> Unit
) {
    val context = LocalContext.current
    val ttsService = remember { TTSService.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    // Animation state for the robot (bobbing up and down)
    val infiniteTransition = rememberInfiniteTransition(label = "robotAnimation")
    val dy by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dy"
    )

    // Breathing animation (scale)
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Typewriter effect state
    var displayedText by remember { mutableStateOf("") }
    val fullText = "¡Hola amigo! Soy tu asistente."
    var isSpeaking by remember { mutableStateOf(false) }

    LaunchedEffect(key1 = fullText) {
        displayedText = ""
        isSpeaking = true
        fullText.forEach { char ->
            displayedText += char
            kotlinx.coroutines.delay(50) // Adjust speed here (lower is faster)
        }
        isSpeaking = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212)) // Dark background
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Speech Bubble with TTS
        Row(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .offset(y = dy.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f, fill = false)) {
                SpeechBubble(text = displayedText)
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            IconButton(
                onClick = { 
                    coroutineScope.launch {
                        ttsService.speak(fullText) 
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF40C4FF).copy(alpha = 0.2f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = "Leer mensaje",
                    tint = Color(0xFF40C4FF)
                )
            }
        }

        // Robot Character
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(scale)
                .offset(y = dy.dp),
            contentAlignment = Alignment.Center
        ) {
            CuteRobot(isSpeaking = isSpeaking)
        }

        Spacer(modifier = Modifier.weight(1f))

        // Continue Button
        Button(
            onClick = onCourseSelectionClick, // Navigate to course selection instead of going back
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF58CC02), // Green like the image
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 6.dp,
                pressedElevation = 2.dp
            )
        ) {
            Text(
                text = "CONTINUAR",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun SpeechBubble(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(
                    color = Color(0xFF1F222B), // Dark bubble background
                    shape = RoundedCornerShape(16.dp)
                )
                .border(
                    width = 2.dp,
                    color = Color(0xFF2B303B),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
        
        // Triangle tail
        val density = LocalDensity.current
        Canvas(modifier = Modifier.size(24.dp, 16.dp)) {
            val half = with(density) { 12.dp.toPx() }
            val path = Path().apply {
                moveTo(size.width / 2 - half, 0f)
                lineTo(size.width / 2 + half, 0f)
                lineTo(size.width / 2, size.height)
                close()
            }
            drawPath(path, color = Color(0xFF1F222B))
        }
    }
}

@Composable
fun CuteRobot(isSpeaking: Boolean) {
    val density = LocalDensity.current
    
    // Animation for mouth movement
    val infiniteTransition = rememberInfiniteTransition(label = "mouthAnim")
    val mouthOpenHeight by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(
            animation = tween(150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mouthOpenHeight"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2

        // Colors
        val robotColor = Color(0xFF40C4FF) // Light Blue
        val robotDarkColor = Color(0xFF0091EA)
        val eyeColor = Color.White
        val pupilColor = Color.Black

        // Precompute dp->px values using LocalDensity
        val px3 = with(density) { 3.dp.toPx() }
        val px4 = with(density) { 4.dp.toPx() }
        val px6 = with(density) { 6.dp.toPx() }
        val px8 = with(density) { 8.dp.toPx() }
        val px10 = with(density) { 10.dp.toPx() }
        val px18 = with(density) { 18.dp.toPx() }
        val px20 = with(density) { 20.dp.toPx() }
        val px25 = with(density) { 25.dp.toPx() }
        val px30 = with(density) { 30.dp.toPx() }
        val px50 = with(density) { 50.dp.toPx() }
        val px55 = with(density) { 55.dp.toPx() }
        val px60 = with(density) { 60.dp.toPx() }
        val px75 = with(density) { 75.dp.toPx() }
        val px80 = with(density) { 80.dp.toPx() }
        val px100 = with(density) { 100.dp.toPx() }
        val px120 = with(density) { 120.dp.toPx() }
        
        // Dynamic mouth height in px
        val mouthHeightPx = if (isSpeaking) with(density) { mouthOpenHeight.dp.toPx() } else px10

        // Body (Rounded Rect)
        drawRoundRect(
            color = robotColor,
            topLeft = Offset(cx - px60, cy - px50),
            size = Size(px120, px100),
            cornerRadius = CornerRadius(px30, px30)
        )

        // Antenna
        drawLine(
            color = Color.Gray,
            start = Offset(cx, cy - px80),
            end = Offset(cx, cy - px50),
            strokeWidth = px4
        )
        drawCircle(
            color = Color.Red,
            radius = px6,
            center = Offset(cx, cy - px80)
        )

        // Eyes (White circles)
        drawCircle(
            color = eyeColor,
            radius = px18,
            center = Offset(cx - px25, cy - px10)
        )
        drawCircle(
            color = eyeColor,
            radius = px18,
            center = Offset(cx + px25, cy - px10)
        )

        // Pupils (Black circles) - Looking slightly right
        drawCircle(
            color = pupilColor,
            radius = px8,
            center = Offset(cx - px20, cy - px10)
        )
        drawCircle(
            color = pupilColor,
            radius = px8,
            center = Offset(cx + px30, cy - px10)
        )

        // Mouth (Smile or Talking)
        if (isSpeaking) {
            // Draw an oval for talking mouth
            drawOval(
                color = Color.Black,
                topLeft = Offset(cx - px10, cy + px10),
                size = Size(px20, mouthHeightPx),
                style = Fill
            )
        } else {
            // Draw static smile
            drawArc(
                color = Color.Black,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(cx - px10, cy + px10),
                size = Size(px20, px10),
                style = Stroke(width = px3)
            )
        }

        // Arms (Waving)
        // Left arm (down)
        drawRoundRect(
            color = robotDarkColor,
            topLeft = Offset(cx - px75, cy),
            size = Size(px20, px50),
            cornerRadius = CornerRadius(px10, px10)
        )

        // Right arm (up/waving)
        withTransform({
            rotate(degrees = -30f, pivot = Offset(cx + px60, cy))
        }) {
            drawRoundRect(
                color = robotDarkColor,
                topLeft = Offset(cx + px55, cy - px30),
                size = Size(px20, px50),
                cornerRadius = CornerRadius(px10, px10)
            )
        }
    }
}

// Extension for modifier border
fun Modifier.border(width: androidx.compose.ui.unit.Dp, color: Color, shape: androidx.compose.ui.graphics.Shape): Modifier {
    return this.then(
        Modifier.background(color, shape).padding(width).background(Color.Transparent, shape) // simplified border logic
        // Actually standard BorderStroke is better but this is a quick custom impl if imports missing.
        // Let's use standard androidx.compose.foundation.border
    )
}
