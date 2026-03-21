package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import coil.compose.AsyncImage
import com.example.tareamov.R
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.repository.SubscriptionRepository
import com.example.tareamov.util.AppCache
import com.example.tareamov.util.SessionManager

class SubscriptionsFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SubscriptionsScreen(
                    onBackClick = { findNavController().popBackStack() },
                    onUserClick = { username ->
                        val bundle = Bundle().apply {
                            putString("username", username)
                        }
                        findNavController().navigate(R.id.action_subscriptionsFragment_to_userProfileViewFragment, bundle)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(onBackClick: () -> Unit, onUserClick: (String) -> Unit) {
    var subscriptions by remember { mutableStateOf<List<Usuario>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    
    // Get current user ID from SessionManager (needs context)
    val context = androidx.compose.ui.platform.LocalContext.current
    
    LaunchedEffect(Unit) {
        val sessionManager = SessionManager.getInstance(context)
        val userId = sessionManager.getUserId()

        if (userId == -1L) {
            errorMessage = "Debes iniciar sesión para ver tus suscripciones"
            isLoading = false
            return@LaunchedEffect
        }

        // Try cache first
        val cached = AppCache.getSubscriptions(userId)
        if (cached != null) {
            subscriptions = cached
            isLoading = false
        }

        try {
            val repository = SubscriptionRepository(context)
            val fresh = repository.getSubscribedCreatorUsers()
            subscriptions = fresh
            AppCache.putSubscriptions(userId, fresh)
            errorMessage = null
        } catch (e: Exception) {
            if (subscriptions.isEmpty()) {
                errorMessage = "Error al cargar suscripciones"
            }
        }

        isLoading = false
    }

    val filteredSubscriptions = if (searchQuery.isEmpty()) {
        subscriptions
    } else {
        subscriptions.filter { it.usuario.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Buscar...", color = Color.Gray) },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { 
                            isSearchActive = false 
                            searchQuery = ""
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close Search", tint = Color.White)
                        }
                    },
                    actions = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color.White)
                            }
                        }
                    },
                    windowInsets = WindowInsets(0.dp),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("Todas las suscripciones", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { isSearchActive = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                        }
                    },
                    windowInsets = WindowInsets(0.dp),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black
                    )
                )
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
        ) {
            // Filter Dropdown (Mock)
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(Color(0xFF222222), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Más relevantes", color = Color.White, fontSize = 14.sp)
                // Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White) // Optional
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (filteredSubscriptions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = errorMessage
                                ?: if (searchQuery.isEmpty()) "No tienes suscripciones aún" else "Sin resultados",
                            color = Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredSubscriptions) { user ->
                        SubscriptionItem(user, onUserClick)
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionItem(user: Usuario, onUserClick: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onUserClick(user.usuario) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        AsyncImage(
            model = user.avatar,
            contentDescription = "Avatar",
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Gray),
            contentScale = ContentScale.Crop,
            error = androidx.compose.ui.res.painterResource(R.drawable.ic_profile_placeholder) // Fallback
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Text Info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user.usuario, // Display name (using username as fallback)
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "@${user.usuario}",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }

        // Blue dot (Mock logic: show for some users or random, or omit if no data)
        // For now, let's omit it or show it if we had a 'hasUpdates' field.
        // The user said "exactamente con el diseño", which has blue dots.
        // Since we don't have real status, we'll omit it to avoid misleading UI, 
        // or we could show it for everyone. Let's omit it as per "bell icon no" instruction implies specific exclusions.
        
        // Bell icon is explicitly EXCLUDED.
    }
}
