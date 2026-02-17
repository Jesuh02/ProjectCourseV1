package com.example.tareamov.ui.compose

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.adapter.CourseAdapter
import com.example.tareamov.data.entity.Course

import androidx.compose.ui.draw.clipToBounds
import com.example.tareamov.service.TTSService
import androidx.compose.material.icons.filled.VolumeUp
import kotlinx.coroutines.launch

@Composable
fun CourseSelectionScreen(
    onBackClick: () -> Unit,
    onCourseSelected: (Course) -> Unit,
    onCreatorClick: (String) -> Unit = {},
    viewModel: CourseSelectionViewModel
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val enrolledCourses by viewModel.enrolledCourses.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val currentUsername by viewModel.currentUsername.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val subscriptionStatus by viewModel.subscriptionStatus.collectAsState()
    val refreshTrigger by viewModel.refreshTrigger.collectAsState()
    
    val context = LocalContext.current
    val ttsService = remember { TTSService.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Regresar",
                    tint = Color.White
                )
            }
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "¿En qué curso deseas",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "reforzar tus conocimientos?",
                    color = Color(0xFF40C4FF),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            IconButton(
                onClick = {
                    coroutineScope.launch {
                        val textToRead = "Lista de cursos para elegir.... " + 
                            if (enrolledCourses.isEmpty()) "No hay cursos disponibles." 
                            else enrolledCourses.joinToString(", ") { it.title }
                        ttsService.speak(textToRead)
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.VolumeUp,
                    contentDescription = "Leer lista de cursos",
                    tint = Color(0xFF40C4FF)
                )
            }
        }

        // Search Bar
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0x1AFFFFFF)
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Buscar",
                    tint = Color(0xFF40C4FF),
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { 
                        Text(
                            text = "Buscar por título o docente...",
                            color = Color(0xFF888888)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFF40C4FF)
                    ),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp
                    ),
                    singleLine = true
                )
            }
        }

        // Content
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF40C4FF))
            }
        } else if (enrolledCourses.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF666666),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No estás inscrito en ningún curso",
                        color = Color(0xFF888888),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Use AndroidView to embed RecyclerView with CourseAdapter
            // subscriptionStatus changes will trigger recomposition and update call
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
                factory = { ctx ->
                    RecyclerView(ctx).apply {
                        layoutManager = LinearLayoutManager(ctx)
                        // Add some bottom padding for the list
                        setPadding(0, 0, 0, 32)
                        clipToPadding = true 
                        
                        // Video Preview Logic
                        var currentPreviewPos = -1
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        
                        fun stopPreview() {
                            if (currentPreviewPos != -1) {
                                val holder = findViewHolderForAdapterPosition(currentPreviewPos) as? CourseAdapter.CourseViewHolder
                                holder?.stopPreview()
                                currentPreviewPos = -1
                            }
                        }
                        
                        val runnable = Runnable {
                            val lm = layoutManager as? LinearLayoutManager ?: return@Runnable
                            val first = lm.findFirstVisibleItemPosition()
                            val last = lm.findLastVisibleItemPosition()
                            if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return@Runnable
                            
                            val center = height / 2
                            var minDst = Int.MAX_VALUE
                            var centerPos = -1
                            
                            for (i in first..last) {
                                val v = lm.findViewByPosition(i) ?: continue
                                val vCenter = (v.top + v.bottom) / 2
                                val dst = Math.abs(center - vCenter)
                                if (dst < minDst) {
                                    minDst = dst
                                    centerPos = i
                                }
                            }
                            
                            if (centerPos != -1 && centerPos != currentPreviewPos) {
                                stopPreview()
                                val holder = findViewHolderForAdapterPosition(centerPos) as? CourseAdapter.CourseViewHolder
                                val adapter = adapter as? CourseAdapter
                                val course = adapter?.getItem(centerPos)
                                val uri = course?.localFilePath ?: course?.videoUri
                                if (!uri.isNullOrEmpty()) {
                                    holder?.playPreview(uri)
                                    currentPreviewPos = centerPos
                                }
                            }
                        }
                        
                        addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                    handler.postDelayed(runnable, 1000)
                                } else {
                                    handler.removeCallbacks(runnable)
                                    stopPreview()
                                }
                            }
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                if (Math.abs(dy) > 0) {
                                    handler.removeCallbacks(runnable)
                                    stopPreview()
                                }
                            }
                        })
                    }
                },
                update = { recyclerView ->
                    // Use refreshTrigger to force recomposition on subscription changes
                    @Suppress("UNUSED_VARIABLE")
                    val triggerRefresh = refreshTrigger
                    
                    // Check if adapter exists and update courses, or create new one
                    val existingAdapter = recyclerView.adapter as? CourseAdapter
                    if (existingAdapter != null) {
                        // Update existing adapter with new data
                        existingAdapter.updateCourses(enrolledCourses)
                        // Set current user ID for subscription logic
                        currentUserId?.let { existingAdapter.setCurrentUserId(it) }
                        // Force refresh to update subscription data from Supabase
                        existingAdapter.notifyDataSetChanged()
                    } else {
                        // Create new adapter
                        val adapter = CourseAdapter(
                            context = context,
                            courses = enrolledCourses,
                            onCourseClickListener = { course -> onCourseSelected(course) },
                            currentUsername = currentUsername,
                            onCreatorClickListener = { username -> onCreatorClick(username) },
                            onSubscriptionClickListener = { course, isCurrentlySubscribed ->
                                // Handle subscription click
                                viewModel.handleSubscriptionClick(course, isCurrentlySubscribed)
                            },
                            subscriptionStatus = subscriptionStatus,
                            showMoreOptions = false // Hide 3-dot menu in Course Selection screen
                        )
                        // Set current user ID for subscription logic
                        currentUserId?.let { adapter.setCurrentUserId(it) }
                        recyclerView.adapter = adapter
                    }
                }
            )
        }
    }
}
