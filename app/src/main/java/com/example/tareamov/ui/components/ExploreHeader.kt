package com.example.tareamov.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val AccentPurple = Color(0xFFA259FF)

@Composable
fun ExploreHeader(
    totalCourses: Int,
    popularCourses: Int,
    newCourses: Int,
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    activeFilterName: String?,
    onFilterClicked: () -> Unit,
    onClearFilter: () -> Unit,
    onPopularCoursesClicked: () -> Unit,
    onNewCoursesClicked: () -> Unit,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit
) {
    val bgAlpha by animateFloatAsState(
        targetValue = if (isCollapsed) 0f else 1f,
        animationSpec = tween(400),
        label = "headerBgAlpha"
    )

    val expandedBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0x001F1F1F),
            Color(0xE61F1F1F),
            Color(0x001F1F1F)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = 1f }
            .drawBehind {
                if (bgAlpha > 0f) {
                    drawRect(brush = expandedBrush, alpha = bgAlpha)
                }
            }
            .padding(horizontal = 16.dp, vertical = if (isCollapsed) 10.dp else 16.dp)
    ) {
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                Text(
                    text = "Explorar Cursos",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "Descubre nuevos conocimientos y habilidades",
                    color = Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }

        SearchBar(
            searchText = searchText,
            onSearchTextChanged = onSearchTextChanged,
            activeFilterName = activeFilterName,
            onFilterClicked = onFilterClicked,
            onClearFilter = onClearFilter,
            isCollapsed = isCollapsed,
            onToggleCollapse = onToggleCollapse
        )

        AnimatedVisibility(
            visible = !isCollapsed,
            enter = fadeIn(animationSpec = tween(500)) + expandVertically(animationSpec = tween(500)),
            exit = fadeOut(animationSpec = tween(500)) + shrinkVertically(animationSpec = tween(500))
        ) {
            Column {
                StatsSection(
                    totalCourses = totalCourses,
                    popularCourses = popularCourses,
                    newCourses = newCourses,
                    onPopularCoursesClicked = onPopularCoursesClicked,
                    onNewCoursesClicked = onNewCoursesClicked
                )

                Text(
                    text = "Todos los Cursos",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
fun SearchBar(
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    activeFilterName: String?,
    onFilterClicked: () -> Unit,
    onClearFilter: () -> Unit,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit
) {
    val shape = RoundedCornerShape(28.dp)

    val shimmerAnim = remember { Animatable(-0.5f) }
    LaunchedEffect(Unit) {
        while (true) {
            shimmerAnim.animateTo(1.5f, tween(5000, easing = LinearEasing))
            shimmerAnim.snapTo(-0.5f)
        }
    }
    val shimmerProgress = shimmerAnim.value

    val glassIntensity by animateFloatAsState(
        targetValue = if (isCollapsed) 1f else 0.45f,
        animationSpec = tween(400),
        label = "glassIntensity"
    )

    val borderGlow by animateFloatAsState(
        targetValue = if (isCollapsed) 0.40f else 0.14f,
        animationSpec = tween(400),
        label = "borderGlow"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .drawBehind {
                val cr = CornerRadius(28.dp.toPx())

                if (glassIntensity > 0.7f) {
                    drawRoundRect(
                        color = AccentPurple.copy(alpha = 0.07f * glassIntensity),
                        cornerRadius = CornerRadius(30.dp.toPx()),
                        size = Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()),
                        topLeft = Offset(-4.dp.toPx(), -4.dp.toPx())
                    )
                }

                drawRoundRect(
                    color = Color(0xFF0D0D12).copy(alpha = 0.70f),
                    cornerRadius = cr
                )

                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.13f * glassIntensity),
                            Color.White.copy(alpha = 0.04f * glassIntensity),
                            Color.Transparent,
                            Color.White.copy(alpha = 0.03f * glassIntensity),
                            Color.White.copy(alpha = 0.09f * glassIntensity)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    cornerRadius = cr
                )

                val shimmerCenter = shimmerProgress * size.width
                val shimmerHalf = size.width * 0.3f
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.04f * glassIntensity),
                            Color.White.copy(alpha = 0.09f * glassIntensity),
                            Color.White.copy(alpha = 0.04f * glassIntensity),
                            Color.Transparent
                        ),
                        startX = shimmerCenter - shimmerHalf,
                        endX = shimmerCenter + shimmerHalf
                    ),
                    cornerRadius = cr
                )

                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f * glassIntensity),
                            Color.Transparent
                        ),
                        startY = 0f,
                        endY = 22f
                    ),
                    cornerRadius = cr
                )

                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            AccentPurple.copy(alpha = 0.06f * glassIntensity),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.75f),
                        radius = size.width * 0.35f
                    ),
                    cornerRadius = cr
                )
            }
            .border(
                (0.5f + 0.5f * glassIntensity).dp,
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = borderGlow),
                        Color.White.copy(alpha = borderGlow * 0.2f),
                        AccentPurple.copy(alpha = borderGlow * 0.6f),
                        Color.White.copy(alpha = borderGlow * 0.4f)
                    )
                ),
                shape
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search",
            tint = AccentPurple,
            modifier = Modifier.padding(start = 8.dp)
        )

        if (activeFilterName != null) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(30.dp)
                    .background(
                        Brush.horizontalGradient(listOf(AccentPurple, Color(0xFF7C3AED))),
                        RoundedCornerShape(15.dp)
                    )
                    .padding(start = 12.dp, end = 4.dp)
                    .clickable { onClearFilter() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activeFilterName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Clear Filter",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp).padding(start = 4.dp)
                )
            }
        }

        val focusRequester = remember { FocusRequester() }
        val keyboardController = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 12.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    focusRequester.requestFocus()
                    keyboardController?.show()
                },
            contentAlignment = Alignment.CenterStart
        ) {
            if (searchText.isEmpty()) {
                Text(
                    text = "Buscar cursos, instructores...",
                    color = Color(0xFF6E6E6E),
                    fontSize = 15.sp
                )
            }
            BasicTextField(
                value = searchText,
                onValueChange = onSearchTextChanged,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(AccentPurple),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        }

        IconButton(onClick = onFilterClicked) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "Filter",
                tint = AccentPurple
            )
        }

        EyeToggleButton(
            isCollapsed = isCollapsed,
            onToggle = onToggleCollapse
        )
    }
}

@Composable
private fun EyeToggleButton(
    isCollapsed: Boolean,
    onToggle: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val scaleAnim = remember { Animatable(1f) }
    val rotationAnim = remember { Animatable(0f) }
    val pulseScale = remember { Animatable(0f) }
    val pulseAlpha = remember { Animatable(0f) }

    val glowAlpha by animateFloatAsState(
        targetValue = if (isCollapsed) 0f else 0.30f,
        animationSpec = tween(400),
        label = "eyeGlow"
    )

    val iconTint by animateColorAsState(
        targetValue = if (isCollapsed) AccentPurple.copy(alpha = 0.50f) else AccentPurple,
        animationSpec = tween(350),
        label = "eyeTint"
    )

    Box(contentAlignment = Alignment.Center) {
        if (glowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AccentPurple.copy(alpha = glowAlpha),
                                AccentPurple.copy(alpha = glowAlpha * 0.25f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )
        }

        if (pulseAlpha.value > 0.01f) {
            Box(
                modifier = Modifier
                    .size((36 + 28 * pulseScale.value).dp)
                    .border(
                        (1.5f * (1f - pulseScale.value)).dp,
                        AccentPurple.copy(alpha = pulseAlpha.value * 0.45f),
                        CircleShape
                    )
            )
        }

        IconButton(
            onClick = {
                scope.launch {
                    launch {
                        scaleAnim.animateTo(0.55f, tween(90))
                        scaleAnim.animateTo(
                            1.22f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium
                            )
                        )
                        scaleAnim.animateTo(1f, tween(120))
                    }
                    launch {
                        val target = if (isCollapsed) 180f else -180f
                        rotationAnim.animateTo(
                            rotationAnim.value + target,
                            tween(380)
                        )
                    }
                    launch {
                        pulseScale.snapTo(0f)
                        pulseAlpha.snapTo(1f)
                        launch { pulseScale.animateTo(1f, tween(450)) }
                        launch { pulseAlpha.animateTo(0f, tween(450)) }
                    }
                }
                onToggle()
            }
        ) {
            Icon(
                imageVector = if (isCollapsed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Toggle Header",
                tint = iconTint,
                modifier = Modifier.graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    rotationZ = rotationAnim.value
                }
            )
        }
    }
}

@Composable
fun StatsSection(
    totalCourses: Int,
    popularCourses: Int,
    newCourses: Int,
    onPopularCoursesClicked: () -> Unit,
    onNewCoursesClicked: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatItem(count = totalCourses, label = "Cursos Disponibles")
        
        Divider()
        
        StatItem(
            count = popularCourses, 
            label = "Más Populares",
            onClick = onPopularCoursesClicked
        )
        
        Divider()
        
        StatItem(
            count = newCourses, 
            label = "Nuevos", 
            onClick = onNewCoursesClicked
        )
    }
}

@Composable
fun StatItem(count: Int, label: String, onClick: (() -> Unit)? = null) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) {
        Text(
            text = count.toString(),
            color = Color(0xFFA259FF),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color(0xFFCCCCCC),
            fontSize = 12.sp
        )
    }
}

@Composable
fun Divider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(40.dp)
            .background(Color(0xFF333333))
    )
}
