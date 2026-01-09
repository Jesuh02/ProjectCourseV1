package com.example.tareamov.ui.components

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tareamov.R

@Composable
fun ExploreHeader(
    totalCourses: Int,
    popularCourses: Int,
    newCourses: Int,
    purchasedCourses: Int,
    searchText: String,
    onSearchTextChanged: (String) -> Unit,
    activeFilterName: String?,
    onFilterClicked: () -> Unit,
    onClearFilter: () -> Unit,
    onPopularCoursesClicked: () -> Unit,
    onNewCoursesClicked: () -> Unit,
    onPurchasedCoursesClicked: () -> Unit,
    isCollapsed: Boolean,
    onToggleCollapse: () -> Unit
) {
    // Background Gradient: #001F1F1F -> #E61F1F1F -> #001F1F1F (Vertical)
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0x001F1F1F),
            Color(0xE61F1F1F),
            Color(0x001F1F1F)
        )
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundBrush)
            .padding(16.dp)
    ) {
        // Collapsible Title Section
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

        // Search Bar (Always Visible)
        SearchBar(
            searchText = searchText,
            onSearchTextChanged = onSearchTextChanged,
            activeFilterName = activeFilterName,
            onFilterClicked = onFilterClicked,
            onClearFilter = onClearFilter,
            isCollapsed = isCollapsed,
            onToggleCollapse = onToggleCollapse
        )

        // Collapsible Stats Section
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
                    purchasedCourses = purchasedCourses,
                    onPopularCoursesClicked = onPopularCoursesClicked,
                    onNewCoursesClicked = onNewCoursesClicked,
                    onPurchasedCoursesClicked = onPurchasedCoursesClicked
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
    // Liquid Glass Background
    val glassBrush = Brush.linearGradient(
        colors = listOf(
            Color(0x26FFFFFF),
            Color(0x1AFFFFFF),
            Color(0x05FFFFFF)
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x1A09090B)) // Solid base
            .background(glassBrush) // Gradient overlay
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(24.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Search",
            tint = Color(0xFFA259FF),
            modifier = Modifier.padding(start = 8.dp)
        )

        // Active Filter Chip
        if (activeFilterName != null) {
            Row(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .height(32.dp)
                    .background(Color(0xFFA259FF), RoundedCornerShape(16.dp))
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

        // Search Input
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
                    color = Color(0xFF888888),
                    fontSize = 16.sp
                )
            }
            BasicTextField(
                value = searchText,
                onValueChange = onSearchTextChanged,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(Color.White),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search
                ),
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

        // Filter Button
        IconButton(onClick = onFilterClicked) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = "Filter",
                tint = Color(0xFFA259FF)
            )
        }
        
        // Toggle Collapse Button (Eye icon)
        IconButton(onClick = onToggleCollapse) {
            Icon(
                imageVector = if (isCollapsed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                contentDescription = "Toggle Header",
                tint = Color(0xFFA259FF)
            )
        }
    }
}

@Composable
fun StatsSection(
    totalCourses: Int,
    popularCourses: Int,
    newCourses: Int,
    purchasedCourses: Int,
    onPopularCoursesClicked: () -> Unit,
    onNewCoursesClicked: () -> Unit,
    onPurchasedCoursesClicked: () -> Unit
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
        
        Divider()
        
        StatItem(
            count = purchasedCourses, 
            label = "Comprados", 
            onClick = onPurchasedCoursesClicked
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
