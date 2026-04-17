package com.example.tareamov.ui.compose

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import com.bumptech.glide.Glide
import de.hdodenhof.circleimageview.CircleImageView
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign // Fix Unresolved reference: TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import com.example.tareamov.util.SessionManager
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.TTSService
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reglas de dependencia lógica para validación flexible de ordering.
 * Pair(prerequisitoRegex, dependienteRegex): el dependiente DEBE ir DESPUÉS del prerequisito.
 */
private val ORDERING_DEPENDENCY_RULES: List<Pair<Regex, Regex>> = listOf(
    // Verificar convergencia ANTES de aplicar fórmula
    Regex("verificar.*convergencia|comprobar.*convergencia|condici[oó]n.*convergencia|\\|r\\|\\s*<\\s*1", RegexOption.IGNORE_CASE) to
        Regex("aplicar.*f[oó]rmula|calcular.*serie|usar.*f[oó]rmula|sumar.*t[eé]rminos|sustituir.*f[oó]rmula", RegexOption.IGNORE_CASE),
    // Verificar dimensiones ANTES de operar matrices
    Regex("verificar.*dimension|comprobar.*dimension|verificar.*compatib|comprobar.*compatib|verificar.*matrices.*mismo|verificar.*dimensiones.*(?:coincid|igual|sean)|comprobar.*dimensiones.*(?:coincid|igual|sean)", RegexOption.IGNORE_CASE) to
        Regex("sumar.*matri|restar.*matri|multiplicar.*matri|operar.*matri|calcular.*(?:suma|resta|multiplicaci|operaci)|sumar.*(?:elementos|componentes)", RegexOption.IGNORE_CASE),
    // Alinear ANTES de operar
    Regex("alinear|colocar.*columna|organizar.*vertical", RegexOption.IGNORE_CASE) to
        Regex("\\bsumar\\b|\\brestar\\b|\\bmultiplicar\\b|\\bdividir\\b|\\boperar\\b|\\bcalcular\\b", RegexOption.IGNORE_CASE),
    // Operar ANTES de escribir resultado final
    Regex("\\bsumar\\b|\\brestar\\b|\\bmultiplicar\\b|\\bdividir\\b|\\bcalcular\\b|\\boperar\\b|\\bresolver\\b", RegexOption.IGNORE_CASE) to
        Regex("escribir.*resultado\\s+final|anotar.*resultado\\s+final", RegexOption.IGNORE_CASE),
    // Identificar datos ANTES de calcular
    Regex("identificar.*datos|leer.*problema|analizar.*enunciado|extraer.*datos", RegexOption.IGNORE_CASE) to
        Regex("\\bcalcular\\b|\\boperar\\b|\\bresolver\\b|aplicar.*f[oó]rmula", RegexOption.IGNORE_CASE),
    // Calcular ANTES de verificar resultado
    Regex("calcular|obtener.*resultado|determinar.*resultado", RegexOption.IGNORE_CASE) to
        Regex("verificar.*resultado|comprobar.*resultado|revisar.*resultado", RegexOption.IGNORE_CASE),
    // Resultado provisional ANTES de identificar dígito de redondeo
    Regex("resultado.*provisional|resultado.*con.*decimales|resultado.*sin.*redonde", RegexOption.IGNORE_CASE) to
        Regex("identificar.*d[ií]gito.*redond|aplicar.*redondeo|redonde(ar|o)\\s+(el\\s+)?resultado", RegexOption.IGNORE_CASE),
    // Identificar dígito ANTES de aplicar redondeo
    Regex("identificar.*d[ií]gito|mirar.*d[ií]gito|observar.*d[ií]gito", RegexOption.IGNORE_CASE) to
        Regex("aplicar.*redondeo|redonde(ar|o)\\s+hacia", RegexOption.IGNORE_CASE),
    // Llevar acarreo ANTES de continuar sumando
    Regex("llevar.*acarreo|registrar.*acarreo", RegexOption.IGNORE_CASE) to
        Regex("continuar.*sumando|pasar.*siguiente.*columna|seguir.*sumando", RegexOption.IGNORE_CASE),
    // Convertir a decimal ANTES de alinear
    Regex("convertir.*decimal|pasar.*decimal|expandir.*notaci[oó]n|notaci[oó]n.*a.*decimal", RegexOption.IGNORE_CASE) to
        Regex("alinear|colocar.*columna", RegexOption.IGNORE_CASE),
    // Plantear ecuación ANTES de resolver
    Regex("plantear.*ecuaci[oó]n|escribir.*ecuaci[oó]n|formular.*ecuaci[oó]n", RegexOption.IGNORE_CASE) to
        Regex("resolver.*ecuaci[oó]n|despejar|simplificar.*ecuaci[oó]n", RegexOption.IGNORE_CASE),
    // Derivar ANTES de igualar a cero
    Regex("calcular.*derivada|derivar.*funci[oó]n|obtener.*derivada", RegexOption.IGNORE_CASE) to
        Regex("igualar.*cero|puntos?.*cr[ií]tic", RegexOption.IGNORE_CASE),
    // Operar ANTES de redondear
    Regex("\\bsumar\\b|\\brestar\\b|\\bcalcular\\b|\\boperar\\b|obtener.*resultado", RegexOption.IGNORE_CASE) to
        Regex("redonde(ar|o)\\s+(el\\s+resultado|a\\s+\\d+\\s+decimal)", RegexOption.IGNORE_CASE),
    // Calcular/sumar ANTES de escribir resultado provisional
    Regex("sumar.*columna|calcular.*total|realizar.*(suma|operaci[oó]n)", RegexOption.IGNORE_CASE) to
        Regex("resultado.*provisional|resultado.*con.*todos.*decimales", RegexOption.IGNORE_CASE),
    // Leer/analizar problema ANTES de plantear ecuación
    Regex("leer.*problema|analizar.*enunciado|comprender.*problema", RegexOption.IGNORE_CASE) to
        Regex("plantear.*ecuaci[oó]n|formular.*ecuaci[oó]n", RegexOption.IGNORE_CASE),
    // Factorizar ANTES de encontrar raíces
    Regex("factorizar|simplificar.*expresi[oó]n", RegexOption.IGNORE_CASE) to
        Regex("encontrar.*ra[ií]ces?|hallar.*ra[ií]ces?|valores?\\s+de\\s+x", RegexOption.IGNORE_CASE),
    // Identificar datos ANTES de plantear ecuación
    Regex("identificar.*datos|extraer.*datos|leer.*problema", RegexOption.IGNORE_CASE) to
        Regex("plantear.*ecuaci[oó]n|escribir.*ecuaci[oó]n|formular.*ecuaci[oó]n", RegexOption.IGNORE_CASE),
)

/** Valida un ordering por dependencias lógicas — acepta cualquier orden que respete todas las dependencias. */
private fun isOrderingCorrectByDependencies(userTexts: List<String>, items: List<String>): Boolean {
    if (userTexts.isEmpty() || userTexts.size != items.size) return false
    for (i in userTexts.indices) {
        for (j in (i + 1) until userTexts.size) {
            for ((prereq, dependent) in ORDERING_DEPENDENCY_RULES) {
                if (dependent.containsMatchIn(userTexts[i]) && prereq.containsMatchIn(userTexts[j])) return false
            }
        }
    }
    return true
}

@Composable
fun ReinforcementLearningScreen(
    courseName: String,
    instructorName: String,
    creatorUsername: String? = null,
    creatorAvatarUrl: String? = null,
    subjectName: String? = null,
    // Debugging / Tracking IDs
    taskId: Long? = null,
    topicId: Long? = null,
    contentItemId: Long? = null,
    difficulty: String = "HARD",
    onBackClick: () -> Unit,
    onStartClick: () -> Unit,
    viewModel: ReinforcementLearningViewModel? = null
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
    
    // DEBUG: Log Context IDs to verify flow
    LaunchedEffect(courseName, taskId, topicId, contentItemId) {
        android.util.Log.d("ReinforcementScreen", "🚀 Screen Load - IDs: Course=$courseName, Task=$taskId, Topic=$topicId, ContentItem=$contentItemId")
        if (taskId == null) android.util.Log.w("ReinforcementScreen", "⚠️ Warning: TaskID is null. RAG Optimization might fail.")
        if (topicId == null) android.util.Log.w("ReinforcementScreen", "⚠️ Warning: TopicID is null.")
    }
    
    var historySaved by remember { mutableStateOf(false) }
    var showInfoDialog by remember { mutableStateOf(false) }
    
    // Local state for quiz flow
    var isQuizActive by remember { mutableStateOf(false) }
    var currentQuestionIndex by remember { mutableStateOf(0) }
    var showExplanation by remember { mutableStateOf(false) }
    var selectedOptionIndex by remember { mutableStateOf(-1) }
    var quizStartTimeMs by remember { mutableStateOf(0L) }
    // State for fill_in_blank exercises
    var fillAnswer by remember { mutableStateOf("") }
    var fillSubmitted by remember { mutableStateOf(false) }
    var fillCorrect by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }
    // State for ordering exercises
    var userOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var orderSubmitted by remember { mutableStateOf(false) }
    var orderCorrect by remember { mutableStateOf(false) }
    
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
        fillAnswer = ""
        fillSubmitted = false
        fillCorrect = false
        showHint = false
        userOrder = emptyList()
        orderSubmitted = false
        orderCorrect = false
        quizStartTimeMs = System.currentTimeMillis()
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
                    text = if (isQuizActive) "Ejercicio ${currentQuestionIndex + 1}" else courseName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                // Difficulty badge
                val (diffEmoji, diffLabel, diffColor) = when (difficulty) {
                    "EASY"         -> Triple("🌱", "Fácil",      androidx.compose.ui.graphics.Color(0xFF2ECC71))
                    "INTERMEDIATE" -> Triple("⚡", "Intermedio", androidx.compose.ui.graphics.Color(0xFFF39C12))
                    else           -> Triple("🔥", "Difícil",    androidx.compose.ui.graphics.Color(0xFFE74C3C))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(diffColor.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = diffEmoji, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(text = diffLabel, color = diffColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }

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
                                 text = "Materia:",
                                 color = Color(0xFFAAAAAA),
                                 fontSize = 12.sp,
                                 fontWeight = FontWeight.Bold
                             )
                             Text(
                                 text = subjectName ?: "No seleccionada (General)",
                                 color = Color(0xFF40C4FF),
                                 fontSize = 14.sp,
                                 fontWeight = FontWeight.Medium,
                                 modifier = Modifier.padding(bottom = 12.dp)
                             )
                             
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
                        // Obtain current user id via SessionManager + BackendApiService
                        val sessionManager = SessionManager.getInstance(context)
                        val username = sessionManager.getUsername()
                        var userId: Long? = null
                        if (!username.isNullOrBlank()) {
                            BackendApiService.initialize(context)
                            val userResult = withContext(Dispatchers.IO) { BackendApiService.getUserByUsername(username) }
                            if (userResult is ApiResult.Success) {
                                userId = userResult.data?.id
                            }
                        }

                        // Try to resolve courseId by searching courses by title
                        var courseIdResolved: Long? = null
                        if (courseName.isNotBlank()) {
                            val searchResult = withContext(Dispatchers.IO) { BackendApiService.searchCourses(courseName) }
                            if (searchResult is ApiResult.Success) {
                                val found = searchResult.data ?: emptyList()
                                val exact = found.firstOrNull { it.title.trim().equals(courseName.trim(), true) }
                                courseIdResolved = exact?.id ?: found.firstOrNull()?.id
                            }
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
                // Log questions shown in the UI when quiz starts
                LaunchedEffect(state) {
                    android.util.Log.d("ReinforcementScreen", "═══ preguntas mostradas en la interfaz (${state.questions.size}) ═══")
                    state.questions.forEachIndexed { idx, q ->
                        android.util.Log.d("ReinforcementScreen", "  [${idx + 1}] type=${q.getEffectiveType()} | ${q.question.take(100)}")
                    }
                    android.util.Log.d("ReinforcementScreen", "═══════════════════════════════════════════════════")
                }
                if (currentQuestionIndex < state.questions.size) {
                    val question = state.questions[currentQuestionIndex]
                    val exerciseType = question.getEffectiveType()

                    // Initialize ordering state when entering a new ordering question
                    LaunchedEffect(currentQuestionIndex, exerciseType) {
                        if (exerciseType == "ordering" && userOrder.isEmpty() && !question.items.isNullOrEmpty()) {
                            userOrder = question.items
                        }
                    }
                    
                    when (exerciseType) {
                        "fill_in_blank" -> FillInBlankView(
                            question = question,
                            fillAnswer = fillAnswer,
                            fillSubmitted = fillSubmitted,
                            fillCorrect = fillCorrect,
                            showHint = showHint,
                            showExplanation = showExplanation,
                            dy = dy,
                            scale = scale,
                            onAnswerChange = { fillAnswer = it },
                            onToggleHint = { showHint = !showHint },
                            onSubmit = {
                                if (!fillSubmitted && fillAnswer.isNotBlank()) {
                                    fillSubmitted = true
                                    val given = fillAnswer.trim()
                                    val expected = question.correctAnswer?.trim() ?: ""
                                    val correct = try {
                                        val givenDec = java.math.BigDecimal(given.replace(",", "").replace(" ", ""))
                                        val expectedDec = java.math.BigDecimal(expected.replace(",", "").replace(" ", ""))
                                        givenDec.compareTo(expectedDec) == 0
                                    } catch (_: Exception) {
                                        given.equals(expected, ignoreCase = true)
                                    }
                                    fillCorrect = correct
                                    showExplanation = true
                                    if (correct) viewModel?.addScore(10)
                                }
                            },
                            onNextClick = {
                                currentQuestionIndex++
                                showExplanation = false
                                fillAnswer = ""
                                fillSubmitted = false
                                fillCorrect = false
                                showHint = false
                            }
                        )
                        "ordering" -> OrderingView(
                            question = question,
                            userOrder = userOrder,
                            orderSubmitted = orderSubmitted,
                            orderCorrect = orderCorrect,
                            showExplanation = showExplanation,
                            dy = dy,
                            scale = scale,
                            onMoveItem = { fromIdx, toIdx ->
                                if (!orderSubmitted) {
                                    val mutable = userOrder.toMutableList()
                                    val item = mutable.removeAt(fromIdx)
                                    mutable.add(toIdx, item)
                                    userOrder = mutable
                                }
                            },
                            onSubmit = {
                                if (!orderSubmitted && userOrder.isNotEmpty()) {
                                    orderSubmitted = true
                                    val correctOrder = question.correctOrder
                                    val items = question.items ?: emptyList()
                                    val correct = if (correctOrder != null && items.isNotEmpty()) {
                                        val expectedOrder = correctOrder.mapNotNull { items.getOrNull(it) }
                                        // Comparación estricta primero
                                        if (userOrder == expectedOrder) true
                                        // Si falla, validar por dependencias lógicas
                                        else isOrderingCorrectByDependencies(userOrder, items)
                                    } else false
                                    orderCorrect = correct
                                    showExplanation = true
                                    if (correct) viewModel?.addScore(10)
                                }
                            },
                            onNextClick = {
                                currentQuestionIndex++
                                showExplanation = false
                                userOrder = emptyList()
                                orderSubmitted = false
                                orderCorrect = false
                            }
                        )
                        else -> QuizView(
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
                                currentQuestionIndex++
                                showExplanation = false
                                selectedOptionIndex = -1
                            }
                        )
                    }
                } else {
                    // Completed View
                    val totalQuestions = state.questions.size
                    val correctAnswers = currentScore / 10
                    val incorrectAnswers = totalQuestions - correctAnswers
                    val grade = if (totalQuestions > 0) (correctAnswers.toFloat() / totalQuestions * 10f) else 0f
                    val durationSeconds = if (quizStartTimeMs > 0) ((System.currentTimeMillis() - quizStartTimeMs) / 1000).toInt() else 0

                    var resultSaved by remember { mutableStateOf(false) }
                    val context = LocalContext.current

                    LaunchedEffect(Unit) {
                        if (!resultSaved) {
                            resultSaved = true
                            try {
                                val sessionManager = SessionManager.getInstance(context)
                                val username = sessionManager.getUsername()
                                var userId: Long? = null
                                if (!username.isNullOrBlank()) {
                                    BackendApiService.initialize(context)
                                    val userResult = withContext(Dispatchers.IO) { BackendApiService.getUserByUsername(username) }
                                    if (userResult is ApiResult.Success) userId = userResult.data?.id
                                }
                                var courseIdResolved: Long? = null
                                if (courseName.isNotBlank()) {
                                    val searchResult = withContext(Dispatchers.IO) { BackendApiService.searchCourses(courseName) }
                                    if (searchResult is ApiResult.Success) {
                                        val found = searchResult.data ?: emptyList()
                                        courseIdResolved = found.firstOrNull { it.title.trim().equals(courseName.trim(), true) }?.id ?: found.firstOrNull()?.id
                                    }
                                }
                                val resolvedUserId = userId
                                val resolvedCourseId = courseIdResolved
                                if (resolvedUserId != null && resolvedCourseId != null) {
                                    withContext(Dispatchers.IO) {
                                        BackendApiService.saveReinforcementResult(
                                            userId = resolvedUserId,
                                            courseId = resolvedCourseId,
                                            totalQuestions = totalQuestions,
                                            correctAnswers = correctAnswers,
                                            difficulty = difficulty,
                                            topicId = topicId,
                                            taskId = taskId,
                                            durationSeconds = durationSeconds
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.w("ReinforcementScreen", "Error saving result", e)
                            }
                        }
                    }
                    
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "¡Cuestionario Completado!",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // Robot Happy
                        Box(
                            modifier = Modifier.size(150.dp).scale(scale).offset(y = dy.dp),
                            contentAlignment = Alignment.Center
                        ) {
                             CuteRobot(isSpeaking = false)
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        // Stats Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B303B)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Correctas:", color = Color.White, fontSize = 18.sp)
                                    Text("$correctAnswers", color = Color(0xFF4CAF50), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Incorrectas:", color = Color.White, fontSize = 18.sp)
                                    Text("$incorrectAnswers", color = Color(0xFFF44336), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Nota:", color = Color.White, fontSize = 18.sp)
                                    Text(
                                        String.format("%.1f / 10", grade),
                                        color = if (grade >= 6f) Color(0xFF4CAF50) else Color(0xFFF44336),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
// Puntaje removed per request
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        Button(
                            onClick = { 
                                // Return to start
                                isQuizActive = false
                                historySaved = false
                                currentQuestionIndex = 0
                                showExplanation = false
                                selectedOptionIndex = -1
                                fillAnswer = ""
                                fillSubmitted = false
                                fillCorrect = false
                                showHint = false
                                userOrder = emptyList()
                                orderSubmitted = false
                                orderCorrect = false
                                viewModel?.resetToInitial()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF58CC02)),
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("FINALIZAR REVISIÓN", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
    val context = LocalContext.current
    val ttsService = remember { TTSService.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()
    
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
                        ttsService.speak(robotText) 
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

        // Robot
        Box(
            modifier = Modifier.size(200.dp).scale(scale).offset(y = dy.dp),
            contentAlignment = Alignment.Center
        ) {
                    CuteRobot(isSpeaking = isSpeaking)
        }

        Spacer(modifier = Modifier.weight(1f))

        if (uiState is ReinforcementState.Loading) {
            TicTacToeGame()
        } else if (uiState is ReinforcementState.Error) {
             // Friendly handling for errors: avoid showing raw "respuesta vacia" and
             // perform automatic exponential-backoff retries while keeping the UI calm.
             val errorState = uiState as ReinforcementState.Error
             val isEmptyResponse = errorState.message.isNullOrBlank() || errorState.message.contains("respuesta vacia", true)

             var retryCount by remember { mutableStateOf(0) }
             val maxRetries = 5

             // Auto-retry with exponential backoff to avoid leaving the user stuck.
             LaunchedEffect(errorState.message, retryCount) {
                 if (retryCount < maxRetries) {
                     val delayMs = (1000L * Math.pow(2.0, retryCount.toDouble())).toLong().coerceAtMost(10000L)
                     delay(delayMs)
                     retryCount++
                     try {
                         onGenerateClick()
                     } catch (e: Exception) {
                         android.util.Log.w("ReinforcementScreen", "Auto-retry failed", e)
                     }
                 }
             }

             if (isEmptyResponse) {
                 Text("Esperando respuesta del servidor... Reintentando (${retryCount}/${maxRetries})", color = Color.Gray, fontSize = 14.sp)
             } else {
                 Text("Ocurrió un problema: ${errorState.message}", color = Color.Yellow, fontSize = 14.sp)
             }

             Spacer(modifier = Modifier.height(12.dp))

             Row(horizontalArrangement = Arrangement.Start) {
                 Button(onClick = {
                     // manual immediate retry
                     retryCount = 0
                     onGenerateClick()
                 }) { Text("Reintentar ahora") }

                 Spacer(modifier = Modifier.width(8.dp))

                 Button(onClick = {
                     // allow user to remain in initial state or cancel
                 }) { Text("Cancelar") }
             }
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
    val coroutineScope = rememberCoroutineScope()
    
    // TTS state
    var isSpeaking by remember { mutableStateOf(false) }
    val ttsService = remember { TTSService.getInstance(context) }
    
    // Build full text with question and options for TTS
    fun buildQuestionWithOptions(): String {
        val optionLetters = listOf("A", "B", "C", "D", "E", "F")
        val optionsText = question.options.mapIndexed { index, option ->
            val letter = optionLetters.getOrElse(index) { "${index + 1}" }
            "Opción $letter: $option"
        }.joinToString(". ")
        
        return "${question.question}. Las opciones son: $optionsText"
    }
    
    // Stop TTS when leaving
    DisposableEffect(Unit) {
        onDispose {
            ttsService.stopPlayback()
        }
    }
    
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
                        CuteRobot(isSpeaking = isSpeaking)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1F222B), RoundedCornerShape(16.dp))
                                .border(2.dp, Color(0xFF2B303B), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Text(text = question.question, color = Color.White, fontSize = 16.sp)
                        }
                        
                        // TTS Button Row - Reads question AND options when pressed
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Speaker Button - Immediate response with visual feedback
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(
                                        if (isSpeaking) Color(0xFF58CC02) else Color(0xFF2B303B),
                                        CircleShape
                                    )
                                    .clickable {
                                        if (isSpeaking) {
                                            // Stop immediately
                                            ttsService.stopPlayback()
                                            isSpeaking = false
                                        } else {
                                            // Set speaking state IMMEDIATELY for instant visual feedback
                                            isSpeaking = true
                                            val fullText = buildQuestionWithOptions()
                                            
                                            // Launch TTS on Main for IMMEDIATE native TTS playback
                                            coroutineScope.launch {
                                                try {
                                                    ttsService.speakImmediate(
                                                        text = fullText,
                                                        onStart = { 
                                                            // Already showing as speaking
                                                            android.util.Log.d("TTS", "🔊 TTS started playing IMMEDIATELY")
                                                        },
                                                        onComplete = { 
                                                            // Update state on main thread
                                                            isSpeaking = false
                                                        },
                                                        onError = { error ->
                                                            android.util.Log.e("TTS", "TTS error: $error")
                                                            isSpeaking = false
                                                        }
                                                    )
                                                } catch (e: Exception) {
                                                    android.util.Log.e("TTS", "TTS exception: ${e.message}")
                                                    isSpeaking = false
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                                    contentDescription = if (isSpeaking) "Detener" else "Escuchar pregunta y opciones",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            
                            Spacer(modifier = Modifier.width(8.dp))
                            
                            Text(
                                text = if (isSpeaking) "Reproduciendo..." else "Escuchar pregunta",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
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
                // State for explanation TTS
                var isExplanationSpeaking by remember { mutableStateOf(false) }
                
                // Stop question TTS when explanation appears
                LaunchedEffect(showExplanation) {
                    if (showExplanation) {
                        ttsService.stopPlayback()
                        isSpeaking = false
                    }
                }
                
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
                
                // Explanation with TTS button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = "Explicación: ${question.getExplanationSafe()}",
                        color = Color(0xFFDDDDDD),
                        fontSize = 14.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // TTS Button for explanation - Immediate response when pressed
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(
                                if (isExplanationSpeaking) Color(0xFF58CC02) else Color(0xFF2B303B),
                                CircleShape
                            )
                            .clickable {
                                if (isExplanationSpeaking) {
                                    ttsService.stopPlayback()
                                    isExplanationSpeaking = false
                                } else {
                                    val feedbackVoice = if (isCorrectSelection) "¡Correcto!" else "Incorrecto"
                                    val fullExplanation = "$feedbackVoice. ${question.getExplanationSafe()}"
                                    // Set speaking state IMMEDIATELY for instant visual feedback
                                    isExplanationSpeaking = true
                                    
                                    // Launch TTS on Main for IMMEDIATE native TTS playback
                                    coroutineScope.launch {
                                        try {
                                            ttsService.speakImmediate(
                                                text = fullExplanation,
                                                onStart = { 
                                                    android.util.Log.d("TTS", "🔊 Explanation TTS started IMMEDIATELY")
                                                },
                                                onComplete = { 
                                                    isExplanationSpeaking = false
                                                },
                                                onError = { error ->
                                                    android.util.Log.e("TTS", "Explanation TTS error: $error")
                                                    isExplanationSpeaking = false
                                                }
                                            )
                                        } catch (e: Exception) {
                                            android.util.Log.e("TTS", "Explanation TTS exception: ${e.message}")
                                            isExplanationSpeaking = false
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isExplanationSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp,
                            contentDescription = if (isExplanationSpeaking) "Detener" else "Escuchar explicación",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
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

@Composable
fun FillInBlankView(
    question: QuizQuestion,
    fillAnswer: String,
    fillSubmitted: Boolean,
    fillCorrect: Boolean,
    showHint: Boolean,
    showExplanation: Boolean,
    dy: Float,
    scale: Float,
    onAnswerChange: (String) -> Unit,
    onToggleHint: () -> Unit,
    onSubmit: () -> Unit,
    onNextClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ttsService = remember { TTSService.getInstance(context) }
    var isSpeaking by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { ttsService.stopPlayback() } }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Exercise type badge
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✍️ Completar", color = Color(0xFFFFD60A), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFFFFD60A).copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }

            // Robot + Question
            Box(modifier = Modifier.heightIn(min = 160.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(100.dp).scale(scale * 0.8f).offset(y = dy.dp), contentAlignment = Alignment.Center) {
                        CuteRobot(isSpeaking = isSpeaking)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.background(Color(0xFF1F222B), RoundedCornerShape(16.dp)).border(2.dp, Color(0xFF2B303B), RoundedCornerShape(16.dp)).padding(16.dp)) {
                            Text(text = question.question, color = Color.White, fontSize = 16.sp)
                        }
                        // TTS button
                        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(36.dp)
                                    .background(if (isSpeaking) Color(0xFF58CC02) else Color(0xFF2B303B), CircleShape)
                                    .clickable {
                                        if (isSpeaking) { ttsService.stopPlayback(); isSpeaking = false }
                                        else {
                                            isSpeaking = true
                                            coroutineScope.launch {
                                                try { ttsService.speakImmediate(question.question, onStart = {}, onComplete = { isSpeaking = false }, onError = { isSpeaking = false }) }
                                                catch (_: Exception) { isSpeaking = false }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = if (isSpeaking) "Reproduciendo..." else "Escuchar pregunta", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Input field
            val borderColor = when {
                fillSubmitted && fillCorrect -> Color(0xFF4CAF50)
                fillSubmitted && !fillCorrect -> Color(0xFFF44336)
                else -> Color(0xFF2B303B)
            }
            Box(
                modifier = Modifier.fillMaxWidth()
                    .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                    .background(Color(0xFF1F222B), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = fillAnswer,
                        onValueChange = { if (!fillSubmitted) onAnswerChange(it) },
                        enabled = !fillSubmitted,
                        modifier = Modifier.weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (fillAnswer.isEmpty()) {
                                Text("Escribe tu respuesta...", color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    )
                    // Hint button
                    if (!question.hint.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier.size(32.dp)
                                .background(if (showHint) Color(0xFFFFD60A).copy(alpha = 0.2f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onToggleHint() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Lightbulb, contentDescription = "Pista", tint = if (showHint) Color(0xFFFFD60A) else Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // Hint text
            if (showHint && !question.hint.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "💡 ${question.hint}",
                    color = Color(0xFFFFD60A).copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFFFFD60A).copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(10.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Submit button
            if (!fillSubmitted) {
                Button(
                    onClick = onSubmit,
                    enabled = fillAnswer.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verificar respuesta", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Result feedback
            if (fillSubmitted) {
                Spacer(modifier = Modifier.height(16.dp))
                val feedbackText = if (fillCorrect) "¡Correcto! 🎉" else "¡Incorrecto! ❌"
                val feedbackColor = if (fillCorrect) Color(0xFF4CAF50) else Color(0xFFF44336)
                Text(feedbackText, color = feedbackColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                if (!fillCorrect) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Respuesta correcta: ${question.correctAnswer ?: "N/A"}",
                        color = Color(0xFF4CAF50).copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF4CAF50).copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Explicación: ${question.getExplanationSafe()}",
                    color = Color(0xFFDDDDDD),
                    fontSize = 14.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Next button
        if (showExplanation) {
            Button(
                onClick = onNextClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF40C4FF)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Siguiente Pregunta", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun OrderingView(
    question: QuizQuestion,
    userOrder: List<String>,
    orderSubmitted: Boolean,
    orderCorrect: Boolean,
    showExplanation: Boolean,
    dy: Float,
    scale: Float,
    onMoveItem: (fromIdx: Int, toIdx: Int) -> Unit,
    onSubmit: () -> Unit,
    onNextClick: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ttsService = remember { TTSService.getInstance(context) }
    var isSpeaking by remember { mutableStateOf(false) }

    val items = question.items ?: emptyList()
    val correctOrder = question.correctOrder ?: emptyList()
    val expectedOrder = correctOrder.mapNotNull { items.getOrNull(it) }

    DisposableEffect(Unit) { onDispose { ttsService.stopPlayback() } }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Exercise type badge
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔢 Ordenar", color = Color(0xFFBF5AF2), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Color(0xFFBF5AF2).copy(alpha = 0.12f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
            }

            // Robot + Question
            Box(modifier = Modifier.heightIn(min = 160.dp).fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(100.dp).scale(scale * 0.8f).offset(y = dy.dp), contentAlignment = Alignment.Center) {
                        CuteRobot(isSpeaking = isSpeaking)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(modifier = Modifier.background(Color(0xFF1F222B), RoundedCornerShape(16.dp)).border(2.dp, Color(0xFF2B303B), RoundedCornerShape(16.dp)).padding(16.dp)) {
                            Text(text = question.question, color = Color.White, fontSize = 16.sp)
                        }
                        Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(36.dp)
                                    .background(if (isSpeaking) Color(0xFF58CC02) else Color(0xFF2B303B), CircleShape)
                                    .clickable {
                                        if (isSpeaking) { ttsService.stopPlayback(); isSpeaking = false }
                                        else {
                                            isSpeaking = true
                                            coroutineScope.launch {
                                                try { ttsService.speakImmediate(question.question, onStart = {}, onComplete = { isSpeaking = false }, onError = { isSpeaking = false }) }
                                                catch (_: Exception) { isSpeaking = false }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = if (isSpeaking) Icons.Default.Stop else Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = if (isSpeaking) "Reproduciendo..." else "Escuchar pregunta", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Usa las flechas para reordenar los elementos:", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))

            // Ordering items
            userOrder.forEachIndexed { idx, itemText ->
                val isItemCorrect = if (orderSubmitted && idx < expectedOrder.size) itemText == expectedOrder[idx] else false
                // If the overall ordering was accepted (strict or dependency-based), mark ALL green
                val isItemWrong = orderSubmitted && !orderCorrect && !isItemCorrect

                val itemBorderColor = when {
                    orderSubmitted && orderCorrect -> Color(0xFF4CAF50)  // All green when accepted
                    isItemCorrect -> Color(0xFF4CAF50)
                    isItemWrong -> Color(0xFFFF9F0A)   // Amber instead of red for misplaced
                    else -> Color(0xFF2B303B)
                }
                val itemBg = when {
                    orderSubmitted && orderCorrect -> Color(0xFF4CAF50).copy(alpha = 0.08f)
                    isItemCorrect -> Color(0xFF4CAF50).copy(alpha = 0.08f)
                    isItemWrong -> Color(0xFFFF9F0A).copy(alpha = 0.06f)
                    else -> Color(0xFF1F222B)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .border(1.dp, itemBorderColor, RoundedCornerShape(12.dp))
                        .background(itemBg, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Number badge
                    Box(
                        modifier = Modifier.size(26.dp).background(Color(0xFFBF5AF2).copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${idx + 1}", color = Color(0xFFBF5AF2), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(itemText, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, modifier = Modifier.weight(1f))

                    if (!orderSubmitted) {
                        // Arrow buttons
                        Column {
                            IconButton(
                                onClick = { if (idx > 0) onMoveItem(idx, idx - 1) },
                                enabled = idx > 0,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Subir", tint = if (idx > 0) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f), modifier = Modifier.size(18.dp))
                            }
                            IconButton(
                                onClick = { if (idx < userOrder.size - 1) onMoveItem(idx, idx + 1) },
                                enabled = idx < userOrder.size - 1,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Bajar", tint = if (idx < userOrder.size - 1) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.15f), modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        // Indicator: green check for correct/accepted, amber arrow for misplaced
                        val indicatorText = if (orderCorrect || isItemCorrect) "✓" else "↕"
                        val indicatorColor = if (orderCorrect || isItemCorrect) Color(0xFF4CAF50) else Color(0xFFFF9F0A)
                        Text(indicatorText, color = indicatorColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Submit button
            if (!orderSubmitted) {
                Button(
                    onClick = onSubmit,
                    enabled = userOrder.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Verificar orden", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Result feedback
            if (orderSubmitted) {
                Spacer(modifier = Modifier.height(16.dp))
                val feedbackText = if (orderCorrect) "¡Correcto! 🎉" else "¡Incorrecto! ❌"
                val feedbackColor = if (orderCorrect) Color(0xFF4CAF50) else Color(0xFFF44336)
                Text(feedbackText, color = feedbackColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

                if (!orderCorrect && expectedOrder.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(Color(0xFF4CAF50).copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0xFF4CAF50).copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text("Orden correcto:", color = Color(0xFF4CAF50), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        expectedOrder.forEachIndexed { i, item ->
                            Text("${i + 1}. $item", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Explicación: ${question.getExplanationSafe()}",
                    color = Color(0xFFDDDDDD),
                    fontSize = 14.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Next button
        if (showExplanation) {
            Button(
                onClick = onNextClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF40C4FF)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Siguiente Pregunta", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun TicTacToeGame() {
    // ── Board state ──────────────────────────────────────────────
    var board by remember { mutableStateOf(List(9) { "" }) }
    var gameStatus by remember { mutableStateOf("PLAYING") }
    var isBotTurn by remember { mutableStateOf(false) }
    var winningCells by remember { mutableStateOf<List<Int>>(emptyList()) }
    var moveCounter by remember { mutableStateOf(0) }
    var userScore by remember { mutableStateOf(0) }
    var botScore by remember { mutableStateOf(0) }
    var drawCount by remember { mutableStateOf(0) }
    var isBotThinking by remember { mutableStateOf(false) }

    // ── Win patterns ─────────────────────────────────────────────
    val winPatterns = remember {
        listOf(
            listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8),
            listOf(0, 3, 6), listOf(1, 4, 7), listOf(2, 5, 8),
            listOf(0, 4, 8), listOf(2, 4, 6)
        )
    }

    fun getWinningPattern(b: List<String>): List<Int>? {
        for (p in winPatterns) {
            if (b[p[0]].isNotEmpty() && b[p[0]] == b[p[1]] && b[p[1]] == b[p[2]]) {
                return p
            }
        }
        return null
    }

    fun checkWinner(b: List<String>): String? {
        val pattern = getWinningPattern(b)
        if (pattern != null) return b[pattern[0]]
        if (b.none { it.isEmpty() }) return "DRAW"
        return null
    }

    // ── Minimax with Alpha-Beta Pruning (faster & perfect) ──────
    fun minimax(b: MutableList<String>, depth: Int, isMaximizing: Boolean, alpha: Int, beta: Int): Int {
        val winner = checkWinner(b)
        if (winner == "O") return 1000 - depth
        if (winner == "X") return depth - 1000
        if (winner == "DRAW") return 0

        var a = alpha
        var be = beta

        if (isMaximizing) {
            var bestScore = Int.MIN_VALUE
            for (i in 0 until 9) {
                if (b[i].isEmpty()) {
                    b[i] = "O"
                    val score = minimax(b, depth + 1, false, a, be)
                    b[i] = ""
                    bestScore = maxOf(bestScore, score)
                    a = maxOf(a, score)
                    if (be <= a) break
                }
            }
            return bestScore
        } else {
            var bestScore = Int.MAX_VALUE
            for (i in 0 until 9) {
                if (b[i].isEmpty()) {
                    b[i] = "X"
                    val score = minimax(b, depth + 1, true, a, be)
                    b[i] = ""
                    bestScore = minOf(bestScore, score)
                    be = minOf(be, score)
                    if (be <= a) break
                }
            }
            return bestScore
        }
    }

    // ── Strategic move evaluation (Minimax + positional bonus) ──
    fun getBestMove(b: List<String>): Int {
        val mutableBoard = b.toMutableList()
        val emptyIndices = b.indices.filter { b[it].isEmpty() }

        // 1) Optimization: If clear board, take center (best start)
        if (emptyIndices.size == 9) return 4
        
        // 2) Optimization: If only one move made, and it's not center, take center
        if (emptyIndices.size == 8 && b[4].isEmpty()) return 4

        // 3) Run Minimax for perfect play
        // We use a small random factor for equal-score moves to reduce deterministic repetition
        var bestScore = Int.MIN_VALUE
        var bestMoves = mutableListOf<Int>()

        for (i in emptyIndices) {
            mutableBoard[i] = "O"
            val score = minimax(mutableBoard, 0, false, Int.MIN_VALUE, Int.MAX_VALUE)
            mutableBoard[i] = ""

            if (score > bestScore) {
                bestScore = score
                bestMoves.clear()
                bestMoves.add(i)
            } else if (score == bestScore) {
                bestMoves.add(i)
            }
        }
        
        return bestMoves.randomOrNull() ?: -1
    }

    // ── Bot turn logic ───────────────────────────────────────────
    LaunchedEffect(isBotTurn) {
        if (gameStatus == "PLAYING" && isBotTurn) {
            isBotThinking = true
            // Dynamic delay based on game stage to feel more natural
            val thinkingTime = maxOf(600L, (1000L - (moveCounter * 100))) 
            delay(thinkingTime) 
            
            val move = getBestMove(board)
            if (move != -1) {
                val newBoard = board.toMutableList()
                newBoard[move] = "O"
                board = newBoard
                moveCounter++
            }
            isBotThinking = false
            isBotTurn = false
        }
    }

    // ── Winner check ─────────────────────────────────────────────
    LaunchedEffect(board) {
        val w = checkWinner(board)
        if (w != null && gameStatus == "PLAYING") {
            winningCells = getWinningPattern(board) ?: emptyList()
            gameStatus = if (w == "DRAW") "DRAW" else "WIN_$w"
            
            when (gameStatus) {
                "WIN_X" -> {
                    userScore++
                    // If user wins (impossible against perfect bot), reset score as a "New Game" prestige
                    delay(2000)
                    userScore = 0
                    botScore = 0
                    drawCount = 0
                }
                "WIN_O" -> botScore++
                "DRAW" -> drawCount++
            }
            
            if (gameStatus != "WIN_X") {
                delay(2000) // Standard delay for restart
            }
            
            // "Juego de 0" logic: clear board for next round
            board = List(9) { "" }
            winningCells = emptyList()
            gameStatus = "PLAYING"
            isBotTurn = false
            moveCounter = 0
        }
    }

    // ── Animations ───────────────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "tttAnims")

    val titleGlow by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "titleGlow"
    )

    val thinkingDots by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "thinkingDots"
    )

    val winPulse by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "winPulse"
    )

    val boardGradientShift by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Reverse),
        label = "boardGradient"
    )

    // ── Status text ──────────────────────────────────────────────
    val statusText = when {
        gameStatus == "WIN_X" -> "🎉 ¡Increíble, Ganaste!"
        gameStatus == "WIN_O" -> "🤖 ¡Gana el Robot!"
        gameStatus == "DRAW" -> "🤝 ¡Empate!"
        isBotThinking -> "🤖 Pensando${".".repeat(thinkingDots.toInt())}"
        else -> "🎮 ¡Tu turno!"
    }

    val statusColor by animateColorAsState(
        targetValue = when {
            gameStatus == "WIN_X" -> Color(0xFFFFD700)
            gameStatus == "WIN_O" -> Color(0xFFFF6B6B)
            gameStatus == "DRAW" -> Color(0xFF40C4FF)
            isBotThinking -> Color(0xFFFF9800)
            else -> Color(0xFF58CC02)
        },
        animationSpec = tween(400),
        label = "statusColor"
    )

    // ── UI ───────────────────────────────────────────────────────
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Text(
            text = "🎯 Tic-Tac-Toe",
            color = Color(0xFF40C4FF).copy(alpha = titleGlow),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))

        // Scoreboard
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(Color(0xFF1A1D23), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tú", color = Color(0xFF40C4FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("$userScore", color = Color(0xFF40C4FF), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Empate", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                Text("$drawCount", color = Color(0xFFAAAAAA), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Robot", color = Color(0xFF58CC02), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("$botScore", color = Color(0xFF58CC02), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Status
        val statusScale by animateFloatAsState(
            targetValue = if (gameStatus != "PLAYING") 1.1f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "statusScale"
        )
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.scale(statusScale)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Board
        val boardBorderColor = androidx.compose.ui.graphics.lerp(
            Color(0xFF58CC02), Color(0xFF40C4FF), boardGradientShift
        )

        Box(
            modifier = Modifier
                .size(270.dp)
                .border(3.dp, boardBorderColor, RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1D23), RoundedCornerShape(20.dp))
                .padding(10.dp)
        ) {
            Column {
                for (r in 0..2) {
                    Row(modifier = Modifier.weight(1f)) {
                        for (c in 0..2) {
                            val idx = r * 3 + c
                            val isWinCell = idx in winningCells
                            val cellScale by animateFloatAsState(
                                targetValue = if (isWinCell) winPulse else 1f,
                                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                                label = "cellScale$idx"
                            )
                            val cellBg by animateColorAsState(
                                targetValue = when {
                                    isWinCell && board[idx] == "X" -> Color(0xFF1A3A5C)
                                    isWinCell && board[idx] == "O" -> Color(0xFF1A3C1A)
                                    isBotThinking && board[idx].isEmpty() -> Color(0xFF252830)
                                    else -> Color(0xFF22252E)
                                },
                                animationSpec = tween(300),
                                label = "cellBg$idx"
                            )
                            val cellBorder by animateColorAsState(
                                targetValue = when {
                                    isWinCell && board[idx] == "X" -> Color(0xFF40C4FF)
                                    isWinCell && board[idx] == "O" -> Color(0xFF58CC02)
                                    else -> Color(0xFF2E323D)
                                },
                                animationSpec = tween(300),
                                label = "cellBorder$idx"
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .padding(4.dp)
                                    .scale(cellScale)
                                    .border(
                                        width = if (isWinCell) 2.dp else 1.dp,
                                        color = cellBorder,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .background(cellBg, RoundedCornerShape(12.dp))
                                    .clickable(
                                        enabled = gameStatus == "PLAYING" && !isBotTurn && board[idx].isEmpty()
                                    ) {
                                        val newBoard = board.toMutableList()
                                        newBoard[idx] = "X"
                                        board = newBoard
                                        moveCounter++
                                        isBotTurn = true
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                when (board[idx]) {
                                    "X" -> AnimatedDrawX(isWinning = isWinCell)
                                    "O" -> AnimatedDrawO(isWinning = isWinCell)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Juega mientras se generan tus preguntas ✨",
            color = Color(0xFF666666),
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

// ── Animated X with entrance & glow ─────────────────────────────
@Composable
fun AnimatedDrawX(isWinning: Boolean = false) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(350, easing = FastOutSlowInEasing)
        )
    }
    val scaleAnim by animateFloatAsState(
        targetValue = if (isWinning) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "xScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isWinning) 0.5f else 0f,
        animationSpec = tween(400),
        label = "xGlow"
    )

    Box(contentAlignment = Alignment.Center) {
        // Glow layer
        if (glowAlpha > 0f) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(52.dp)) {
                drawCircle(
                    color = Color(0xFF40C4FF).copy(alpha = glowAlpha),
                    radius = size.minDimension / 2
                )
            }
        }
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(44.dp)
                .scale(scaleAnim)
        ) {
            val stroke = 7f
            val pad = 12f
            val p = progress.value
            // First line
            val end1X = pad + (size.width - pad * 2) * minOf(p * 2, 1f)
            val end1Y = pad + (size.height - pad * 2) * minOf(p * 2, 1f)
            drawLine(
                color = Color(0xFF40C4FF),
                start = androidx.compose.ui.geometry.Offset(pad, pad),
                end = androidx.compose.ui.geometry.Offset(end1X, end1Y),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            // Second line
            if (p > 0.5f) {
                val p2 = (p - 0.5f) * 2
                val end2X = (size.width - pad) - (size.width - pad * 2) * p2
                val end2Y = pad + (size.height - pad * 2) * p2
                drawLine(
                    color = Color(0xFF40C4FF),
                    start = androidx.compose.ui.geometry.Offset(size.width - pad, pad),
                    end = androidx.compose.ui.geometry.Offset(end2X, end2Y),
                    strokeWidth = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            }
        }
    }
}

// ── Animated O with entrance & glow ─────────────────────────────
@Composable
fun AnimatedDrawO(isWinning: Boolean = false) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            animationSpec = tween(400, easing = FastOutSlowInEasing)
        )
    }
    val scaleAnim by animateFloatAsState(
        targetValue = if (isWinning) 1.2f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "oScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isWinning) 0.5f else 0f,
        animationSpec = tween(400),
        label = "oGlow"
    )

    Box(contentAlignment = Alignment.Center) {
        // Glow layer
        if (glowAlpha > 0f) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(52.dp)) {
                drawCircle(
                    color = Color(0xFF58CC02).copy(alpha = glowAlpha),
                    radius = size.minDimension / 2
                )
            }
        }
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .size(44.dp)
                .scale(scaleAnim)
        ) {
            val pad = 12f
            val sweepAngle = 360f * progress.value
            drawArc(
                color = Color(0xFF58CC02),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = 7f,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                ),
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(
                    size.width - pad * 2,
                    size.height - pad * 2
                )
            )
        }
    }
}

