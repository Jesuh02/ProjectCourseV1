package com.example.tareamov.ui.compose

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import com.bumptech.glide.Glide
import de.hdodenhof.circleimageview.CircleImageView
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextAlign // Fix Unresolved reference: TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import com.example.tareamov.util.SessionManager
import com.example.tareamov.service.SupabaseClient
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request

@Composable
fun ReinforcementLearningScreen(
    courseName: String,
    instructorName: String,
    creatorUsername: String? = null,
    creatorAvatarUrl: String? = null,
    onBackClick: () -> Unit,
    onStartClick: () -> Unit,
    viewModel: ReinforcementLearningViewModel? = null // Optional for now to keep compatibility
) {
    // Log screen entry (Debugging)
    LaunchedEffect(courseName) {
        android.util.Log.d("ReinforcementScreen", "🚀 Screen initialized for course: $courseName")
    }
    
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // Fix Unresolved reference for collectAsState
    val uiState by (viewModel?.uiState?.collectAsState() ?: remember { mutableStateOf(ReinforcementState.Initial) })
    val currentScore by (viewModel?.currentScore?.collectAsState() ?: remember { mutableStateOf(0) })
    // ViewModel does not expose selectedTopicName/selectedTaskName flows; keep local nullable state
    val selectedTopic by (viewModel?.selectedTopicName?.collectAsState() ?: remember { mutableStateOf<String?>(null) })
    val selectedTask by (viewModel?.selectedTaskName?.collectAsState() ?: remember { mutableStateOf<String?>(null) })
    val analyzedFiles by (viewModel?.analyzedFiles?.collectAsState() ?: remember { mutableStateOf(emptyList<AnalyzedFile>()) })
    
    var historySaved by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    // Local state for quiz flow
    var isQuizActive by remember { mutableStateOf(false) }
    var currentQuestionIndex by remember { mutableStateOf(0) }
    var showExplanation by remember { mutableStateOf(false) }
    var selectedOptionIndex by remember { mutableStateOf(-1) }
    
    // Robot Animation
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
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    // Helper to reset quiz state
    fun startQuiz() {
        isQuizActive = true
        currentQuestionIndex = 0
        showExplanation = false
        selectedOptionIndex = -1
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header (Always visible)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B303B)),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("<", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isQuizActive) "Pregunta ${currentQuestionIndex + 1}" else courseName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )

                // Creator row: avatar + username (falls back to instructorName)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    if (!creatorAvatarUrl.isNullOrEmpty()) {
                        AndroidView(factory = { ctx: Context ->
                            CircleImageView(ctx).apply {
                                // size will be controlled by Modifier
                                borderWidth = 0
                                setPadding(0,0,0,0)
                            }
                        }, update = { view ->
                            Glide.with(view.context).load(creatorAvatarUrl).circleCrop().into(view)
                        }, modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Text(
                        text = creatorUsername ?: instructorName,
                        color = if (isQuizActive) Color(0xFF58CC02) else Color.Gray,
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
            }
            
            // Info Button (3 dots)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0xFF2B303B), RoundedCornerShape(12.dp))
                    .clickable { showInfoDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "...",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.offset(y = (-6).dp)
                )
            }
        }
        
        // Context Info Dialog
        if (showInfoDialog) {
            Dialog(onDismissRequest = { showInfoDialog = false }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                     Box(
                        modifier = Modifier
                            .background(Color(0xD91F222B)) // Dark translucent similar to liquid glass
                            .padding(20.dp)
                     ) {
                         Column(horizontalAlignment = Alignment.Start) {
                             Text(
                                 text = "Contexto de Estudio",
                                 color = Color.White,
                                 fontSize = 18.sp,
                                 fontWeight = FontWeight.Bold,
                                 modifier = Modifier.fillMaxWidth()
                             )
                             Spacer(modifier = Modifier.height(16.dp))
                             
                             Text(
                                 text = "Tema Seleccionado:",
                                 color = Color(0xFFAAAAAA),
                                 fontSize = 12.sp,
                                 fontWeight = FontWeight.Bold
                             )
                             Text(
                                 text = selectedTopic ?: "No seleccionado (General)",
                                 color = Color.White,
                                 fontSize = 14.sp,
                                 modifier = Modifier.padding(bottom = 12.dp)
                             )
                             
                             Text(
                                 text = "Tarea Seleccionada:",
                                 color = Color(0xFFAAAAAA),
                                 fontSize = 12.sp,
                                 fontWeight = FontWeight.Bold
                             )
                             Text(
                                 text = selectedTask ?: "No seleccionada (General)",
                                 color = Color.White,
                                 fontSize = 14.sp
                             )
                             
                             Spacer(modifier = Modifier.height(16.dp))
                             
                             Text(
                                 text = "Archivos Analizados (${analyzedFiles.size}):",
                                 color = Color(0xFFAAAAAA),
                                 fontSize = 12.sp,
                                 fontWeight = FontWeight.Bold
                             )
                             
                             if (analyzedFiles.isEmpty()) {
                                 Text(
                                     text = "Ninguno",
                                     color = Color.Gray,
                                     fontSize = 14.sp,
                                     fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                 )
                             } else {
                                 analyzedFiles.forEach { file ->
                                     Text(
                                         text = "📄 ${file.name}",
                                         color = Color(0xFF40C4FF),
                                         fontSize = 14.sp,
                                         modifier = Modifier
                                             .padding(vertical = 4.dp)
                                             .clickable {
                                                 file.url?.let { url ->
                                                     try {
                                                         uriHandler.openUri(url)
                                                     } catch (e: Exception) {
                                                         android.util.Log.e("ReinforcementScreen", "Could not open URL: $url", e)
                                                     }
                                                 }
                                             }
                                     )
                                 }
                             }
                             
                             Spacer(modifier = Modifier.height(24.dp))
                             
                             Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                 Box(
                                     modifier = Modifier
                                         .background(Color.Transparent, RoundedCornerShape(8.dp))
                                         .border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(8.dp))
                                         .clickable { showInfoDialog = false }
                                         .padding(horizontal = 16.dp, vertical = 8.dp)
                                 ) {
                                     Text("Cerrar", color = Color.White, fontSize = 14.sp)
                                 }
                             }
                         }
                     }
                }
            }
        }
        
        if (!isQuizActive) {
            // Welcome / Loading / Error State
            WelcomeView(
                uiState = uiState,
                courseName = courseName,
                dy = dy,
                scale = scale,
                onGenerateClick = { onStartClick() },
                onStartQuizClick = { startQuiz() }
            )
            // When questions have been generated by the viewModel/backend, save them in Supabase
            val context = LocalContext.current
            LaunchedEffect(uiState) {
                if (uiState is ReinforcementState.Success && !historySaved) {
                    try {
                        val state = uiState as ReinforcementState.Success
                        val questions = state.questions
                        // Obtain current user id via SessionManager + SupabaseClient
                        val sessionManager = SessionManager.getInstance(context)
                        val username = sessionManager.getUsername()
                        var userId: Long? = null
                        if (!username.isNullOrBlank()) {
                            userId = withContext(Dispatchers.IO) { SupabaseClient.getUserIdFromUsername(username) }
                        }

                        // Try to resolve courseId by searching courses by title
                        var courseIdResolved: Long? = null
                        if (courseName.isNotBlank()) {
                            val found = withContext(Dispatchers.IO) { SupabaseClient.searchCourses(courseName, 5) }
                            val exact = found.firstOrNull { it.title?.trim()?.equals(courseName.trim(), true) == true }
                            courseIdResolved = exact?.id ?: found.firstOrNull()?.id
                        }

                        if (userId != null && courseIdResolved != null) {
                            // Backend already saves generated questions.
                            // Client-side save removed to prevent duplicates.
                            historySaved = true
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ReinforcementScreen", "Error saving reinforcement history", e)
                    }
                }
            }
        } else {
            // Quiz Active State
            val state = uiState
            if (state is ReinforcementState.Success) {
                if (currentQuestionIndex < state.questions.size) {
                    val question = state.questions[currentQuestionIndex]
                    
                    QuizView(
                        question = question,
                        selectedOptionIndex = selectedOptionIndex,
                        showExplanation = showExplanation,
                        dy = dy,
                        scale = scale,
                        onOptionSelected = { index ->
                            if (!showExplanation) {
                                selectedOptionIndex = index
                                showExplanation = true
                                if (index == question.correctIndex) {
                                    viewModel?.addScore(10)
                                }
                            }
                        },
                        onNextClick = {
                            if (currentQuestionIndex < state.questions.size - 1) {
                                currentQuestionIndex++
                                showExplanation = false
                                selectedOptionIndex = -1
                            } else {
                                // Finished
                                isQuizActive = false
                                // Could navigate to results summary here
                            }
                        }
                    )
                } else {
                    // Completed
                    Text("¡Felicidades! Has completado el refuerzo.", color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { 
                            // Reset state to initial to trigger regeneration
                            isQuizActive = false
                            onStartClick() // This calls viewModel.loadQuestions which sets state to Loading -> fetches NEW questions
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58CC02)),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) { 
                        Text("COMENZAR CUESTIONARIO NUEVO", fontSize = 18.sp, fontWeight = FontWeight.Bold) 
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { isQuizActive = false }) { Text("Volver") }
                }
            } else {
                // Fallback if state lost
                Text("Error de estado", color = Color.Red)
            }
        }
    }
}

@Composable
fun WelcomeView(
    uiState: ReinforcementState,
    courseName: String,
    dy: Float,
    scale: Float,
    onGenerateClick: () -> Unit,
    onStartQuizClick: () -> Unit
) {
    var displayedText by remember { mutableStateOf("") }
    var isSpeaking by remember { mutableStateOf(false) }
    val fullText = "¡Bienvenido! Estoy listo para ayudarte con \"$courseName\"."
    
    // State handling text
    val robotText = when (uiState) {
        is ReinforcementState.Loading -> "Generando preguntas personalizadas..."
        is ReinforcementState.Error -> "Ups, hubo un error al conectar con tu tutor."
        is ReinforcementState.Success -> "¡Preguntas listas! ¿Empezamos?"
        else -> fullText
    }

    LaunchedEffect(robotText) {
        displayedText = ""
        isSpeaking = true
        robotText.forEachIndexed { index, char ->
            displayedText += char
            delay(30)
        }
        isSpeaking = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))
        
        // Speech Bubble
        Box(modifier = Modifier.padding(bottom = 24.dp).offset(y = dy.dp)) {
            SpeechBubble(text = displayedText)
        }

        // Robot
        Box(
            modifier = Modifier.size(200.dp).scale(scale).offset(y = dy.dp),
            contentAlignment = Alignment.Center
        ) {
                    CuteRobot(isSpeaking = isSpeaking)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (uiState is ReinforcementState.Loading) {
            CircularProgressIndicator(color = Color(0xFF58CC02))
        } else if (uiState is ReinforcementState.Error) {
             Text((uiState as ReinforcementState.Error).message, color = Color.Red, fontSize = 14.sp)
             Spacer(modifier = Modifier.height(16.dp))
             Button(onClick = onGenerateClick) { Text("Reintentar") } // Logic to retry load needed
        } else if (uiState is ReinforcementState.Success) {
            Button(
                onClick = onStartQuizClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58CC02)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("COMENZAR CUESTIONARIO", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // Initial state: Start Button triggers loading
             Button(
                onClick = onGenerateClick,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58CC02)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("GENERAR PREGUNTAS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun QuizView(
    question: QuizQuestion,
    selectedOptionIndex: Int,
    showExplanation: Boolean,
    dy: Float,
    scale: Float,
    onOptionSelected: (Int) -> Unit,
    onNextClick: () -> Unit
) {
    val context = LocalContext.current
    
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Scrollable Content (Weight 1f)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Robot asking the question
            Box(modifier = Modifier.heightIn(min = 180.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(100.dp).scale(scale * 0.8f).offset(y = dy.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CuteRobot(isSpeaking = false)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1F222B), RoundedCornerShape(16.dp))
                            .border(2.dp, Color(0xFF2B303B), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                            .weight(1f)
                    ) {
                        Text(text = question.question, color = Color.White, fontSize = 16.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Options
            question.options.forEachIndexed { index, option ->
                val isSelected = selectedOptionIndex == index
                val isCorrect = index == question.correctIndex

                // Shake animation for incorrect selection
                val shakeOffset = remember { Animatable(0f) }
                
                LaunchedEffect(showExplanation, isSelected) {
                    if (showExplanation && isSelected && !isCorrect) {
                        // Vibrate logic
                        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                        if (vibrator?.hasVibrator() == true) {
                             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                 vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                             } else {
                                 @Suppress("DEPRECATION")
                                 vibrator.vibrate(200)
                             }
                        }

                        // Shake logic: right, left, right, left, center
                        shakeOffset.animateTo(10f, animationSpec = tween(50, easing = LinearEasing))
                        shakeOffset.animateTo(-10f, animationSpec = tween(50, easing = LinearEasing))
                        shakeOffset.animateTo(10f, animationSpec = tween(50, easing = LinearEasing))
                        shakeOffset.animateTo(-10f, animationSpec = tween(50, easing = LinearEasing))
                        shakeOffset.animateTo(0f, animationSpec = tween(50, easing = LinearEasing))
                    }
                }

                val targetBackgroundColor = when {
                    showExplanation && isCorrect -> Color(0xFF4CAF50) // Brighter Green
                    showExplanation && isSelected && !isCorrect -> Color(0xFFF44336) // Brighter Red
                    isSelected -> Color(0xFF40C4FF) // Blue
                    else -> Color(0xFF2B303B) // Dark
                }

                val backgroundColor by animateColorAsState(
                    targetValue = targetBackgroundColor,
                    animationSpec = tween(durationMillis = 300),
                    label = "optionColorAnimation"
                )

                val scale by animateFloatAsState(
                    targetValue = when {
                        showExplanation && isCorrect -> 1.05f
                        showExplanation && isSelected && !isCorrect -> 0.95f // Slightly shrink incorrect
                        else -> 1.0f
                    },
                    animationSpec = tween(durationMillis = 300),
                    label = "optionScaleAnimation"
                )

                Button(
                    onClick = { onOptionSelected(index) },
                    enabled = !showExplanation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .offset(x = shakeOffset.value.dp) // Apply shake
                        .scale(scale),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = backgroundColor,
                        disabledContainerColor = backgroundColor,
                        contentColor = Color.White,
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = if (showExplanation && (isCorrect || (isSelected && !isCorrect))) {
                         BorderStroke(2.dp, Color.White.copy(alpha = 0.5f))
                    } else null
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Start) {
                        Text(
                            text = option,
                            color = Color.White,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
            
            if (showExplanation) {
                Spacer(modifier = Modifier.height(16.dp))
                
                val isCorrectSelection = selectedOptionIndex == question.correctIndex
                val feedbackText = if (isCorrectSelection) "¡Correcto! 🎉" else "¡Incorrecto! ❌"
                val feedbackColor = if (isCorrectSelection) Color(0xFF4CAF50) else Color(0xFFF44336)
                
                Text(
                    text = feedbackText,
                    color = feedbackColor,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                )
                
                Text(
                    text = "Explicación: ${question.explanation}",
                    color = Color(0xFFDDDDDD),
                    fontSize = 14.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Fixed Footer Button (Only when explanation is shown)
        if (showExplanation) {
            Button(
                onClick = onNextClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF40C4FF)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Siguiente Pregunta", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
