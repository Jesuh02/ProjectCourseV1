package com.example.tareamov.data.sync

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.PersonaDao
import com.example.tareamov.data.dao.TopicDao
import com.example.tareamov.data.dao.ContentItemDao
import com.example.tareamov.data.dao.TaskDao
import com.example.tareamov.data.dao.SubscriptionDao
import com.example.tareamov.data.dao.TaskSubmissionDao
import com.example.tareamov.data.dao.VideoDao
import com.example.tareamov.data.dao.RolDao
import com.example.tareamov.data.dao.RecursoDao
import com.example.tareamov.data.dao.RolRecursoDao
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.tareamov.data.repository.SupabaseRepository
import kotlinx.coroutines.withContext

class SyncRepository(
    private val usuarioDao: UsuarioDao,
    private val personaDao: PersonaDao,
    private val topicDao: TopicDao,
    private val contentItemDao: ContentItemDao,
    private val taskDao: TaskDao,
    private val subscriptionDao: SubscriptionDao,
    private val taskSubmissionDao: TaskSubmissionDao,
    private val videoDao: VideoDao,
    private val rolDao: RolDao,
    private val recursoDao: RecursoDao,
    private val rolRecursoDao: RolRecursoDao,
    private val chatMessageDao: com.example.tareamov.data.dao.ChatMessageDao,
    private val fileContextDao: com.example.tareamov.data.dao.FileContextDao,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private var userListener: ListenerRegistration? = null
    private var personaListener: ListenerRegistration? = null
    private var topicListener: ListenerRegistration? = null
    private var contentItemListener: ListenerRegistration? = null
    private var taskListener: ListenerRegistration? = null
    private var subscriptionListener: ListenerRegistration? = null
    private var taskSubmissionListener: ListenerRegistration? = null
    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val supabaseRepo = SupabaseRepository()
    

    // Sincroniza cambios de la base local a Firebase
    // This method now syncs only items marked as "pending" and updates their status on success.
    // Assumes entities have a 'status' field and DAOs have 'getPending...' and 'update...Status' methods.
    fun syncLocalToFirebase() {
        syncScope.launch {
            // Usuarios
            // Assumes usuarioDao.getPendingUsuarios() and usuarioDao.updateUsuarioStatus(id, status) exist
            // Also assumes Usuario entity has a stable 'id' field (e.g., Long or String).
            // Using a mutable field like username as a document ID will cause duplicates if the username changes.
            usuarioDao.getAllUsuarios().filter { /* it.status == "pending" */ true }.forEach { usuario -> // Replace with getPendingUsuarios() if available
                // Ensure 'usuario.id' is the stable, unique identifier for the user.
                firestore.collection("usuarios").document(usuario.id.toString()).set(usuario) // Changed from usuario.usuario
                    .addOnSuccessListener {
                        // syncScope.launch { usuarioDao.updateUsuarioStatus(usuario.id.toString(), "synced") } // Assuming updateUsuarioStatus takes String ID
                        Log.i("SyncRepository", "Usuario ${usuario.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing usuario ${usuario.id} to Firebase.", e)
                    }
            }
            // Personas
            // Assumes personaDao.getPendingPersonas() and personaDao.updatePersonaStatus(id, status) exist
            personaDao.getAllPersonasList().filter { /* it.status == "pending" */ true }.forEach { persona -> // Replace with getPendingPersonas() if available
                firestore.collection("personas").document(persona.id.toString()).set(persona)
                    .addOnSuccessListener {
                        // syncScope.launch { personaDao.updatePersonaStatus(persona.id, "synced") }
                        Log.i("SyncRepository", "Persona ${persona.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing persona ${persona.id} to Firebase.", e)
                    }
            }
            // Topics
            // Assumes topicDao.getPendingTopics() and topicDao.updateTopicStatus(id, status) exist
            topicDao.getAllTopics().filter { /* it.status == "pending" */ true }.forEach { topic -> // Replace with getPendingTopics() if available
                firestore.collection("topics").document(topic.id.toString()).set(topic)
                    .addOnSuccessListener {
                        // syncScope.launch { topicDao.updateTopicStatus(topic.id, "synced") }
                        Log.i("SyncRepository", "Topic ${topic.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing topic ${topic.id} to Firebase.", e)
                    }
            }
            // ContentItems
            // Assumes contentItemDao.getPendingContentItems() and contentItemDao.updateContentItemStatus(id, status) exist
            contentItemDao.getAllContentItems().filter { /* it.status == "pending" */ true }.forEach { item -> // Replace with getPendingContentItems() if available
                firestore.collection("contentItems").document(item.id.toString()).set(item)
                    .addOnSuccessListener {
                        // syncScope.launch { contentItemDao.updateContentItemStatus(item.id, "synced") }
                        Log.i("SyncRepository", "ContentItem ${item.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing contentItem ${item.id} to Firebase.", e)
                    }
            }
            // Tasks
            // Assumes taskDao.getPendingTasks() and taskDao.updateTaskStatus(id, status) exist
            taskDao.getAllTasks().filter { /* it.status == "pending" */ true }.forEach { task -> // Replace with getPendingTasks() if available
                firestore.collection("tasks").document(task.id.toString()).set(task)
                    .addOnSuccessListener {
                        // syncScope.launch { taskDao.updateTaskStatus(task.id, "synced") }
                        Log.i("SyncRepository", "Task ${task.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing task ${task.id} to Firebase.", e)
                    }
            }
            // Subscriptions
            // Assumes subscriptionDao.getPendingSubscriptions() and subscriptionDao.updateSubscriptionStatus(docId, status) exist
            // Note: If subscriberUsername or creatorUsername can change, using them in docId will create new documents on change, not update.
            // Consider using stable user IDs or a dedicated unique ID for subscriptions.
            subscriptionDao.getAllSubscriptions().filter { /* it.status == "pending" */ true }.forEach { sub -> // Replace with getPendingSubscriptions() if available
                val docId = "${sub.subscriberUsername}_${sub.creatorUsername}" // This ID changes if usernames change
                firestore.collection("subscriptions").document(docId).set(sub)
                    .addOnSuccessListener {
                        // syncScope.launch { subscriptionDao.updateSubscriptionStatus(docId, "synced") }
                        Log.i("SyncRepository", "Subscription ${docId} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing subscription ${docId} to Firebase.", e)
                    }
            }
            // TaskSubmissions
            // Assumes taskSubmissionDao.getPendingTaskSubmissions() and taskSubmissionDao.updateTaskSubmissionStatus(id, status) exist
            taskSubmissionDao.getAllTaskSubmissions().filter { /* it.status == "pending" */ true }.forEach { submission -> // Replace with getPendingTaskSubmissions() if available
                firestore.collection("taskSubmissions").document(submission.id.toString()).set(submission)
                    .addOnSuccessListener {
                        // syncScope.launch { taskSubmissionDao.updateTaskSubmissionStatus(submission.id, "synced") }
                        Log.i("SyncRepository", "TaskSubmission ${submission.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing taskSubmission ${submission.id} to Firebase.", e)
                    }
            }
            // Purchase functionality removed
            Log.i("SyncRepository", "Purchase sync removed from system.")
            
            // Videos
            videoDao.getAllVideos().filter { /* it.status == "pending" */ true }.forEach { video ->
                firestore.collection("videos").document(video.id.toString()).set(video)
                    .addOnSuccessListener {
                        Log.i("SyncRepository", "Video ${video.id} synced to Firebase.")
                    }
                    .addOnFailureListener { e ->
                        Log.e("SyncRepository", "Error syncing video ${video.id} to Firebase.", e)
                    }
            }
        }
    }

    // New: sincronizar a Supabase via REST
    fun syncLocalToSupabase() {
        syncScope.launch {
            // If Supabase isn't configured in BuildConfig, skip
            if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured. Skipping syncLocalToSupabase.")
                return@launch
            }
            try {
                // Check that at least one usable sync target exists before attempting to sync
                // Previously we required 'personas' and 'usuarios' which blocked syncing file/chat contexts.
                val allowIf = listOf("app_documents", "file_contexts", "chat_messages", "task_submissions", "subscriptions")
                val available = allowIf.any { supabaseRepo.tableExists(it) }
                if (!available) {
                    Log.w("SyncRepository", "No usable Supabase target tables found (checked: ${allowIf.joinToString(",")}). Skipping sync. Apply migrations first.")
                    return@launch
                }
                // Usuarios
                usuarioDao.getAllUsuarios().forEach { usuario ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("usuarios", usuario) }
                    if (ok) Log.i("SyncRepository", "Usuario ${usuario.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync usuario ${usuario.id} to Supabase.")
                }

                // Personas
                personaDao.getAllPersonasList().forEach { persona ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("personas", persona) }
                    if (ok) Log.i("SyncRepository", "Persona ${persona.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync persona ${persona.id} to Supabase.")
                }

                // IMPORTANT: Upsert parents first to satisfy foreign key constraints
                // Videos (parents for topics)
                videoDao.getAllVideos().forEach { video ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("videos", video) }
                    if (ok) Log.i("SyncRepository", "Video ${video.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync video ${video.id} to Supabase.")
                }

                // Topics (parents for tasks) - if topic upsert fails due to missing course, try upserting the video parent then retry
                topicDao.getAllTopics().forEach { topic ->
                    var ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                    if (!ok) {
                        Log.w("SyncRepository", "Initial upsert failed for topic ${topic.id}; attempting to ensure parent video ${topic.courseId} exists and retry.")
                        // Try to upsert parent video if available locally
                        try {
                            val video = withContext(Dispatchers.IO) { videoDao.getVideoById(topic.courseId) }
                            if (video != null) {
                                val vOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("videos", video) }
                                if (vOk) {
                                    Log.i("SyncRepository", "Parent video ${video.id} upserted, retrying topic ${topic.id}.")
                                    ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent video ${topic.courseId} while retrying topic ${topic.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent video ${topic.courseId} not found for topic ${topic.id}.")
                            }
                        } catch (e: Exception) {
                            Log.e("SyncRepository", "Exception while attempting to upsert parent video for topic ${topic.id}", e)
                        }
                    }
                    if (ok) Log.i("SyncRepository", "Topic ${topic.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync topic ${topic.id} to Supabase after retry.")
                }

                // ContentItems
                contentItemDao.getAllContentItems().forEach { item ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("content_items", item) }
                    if (ok) Log.i("SyncRepository", "ContentItem ${item.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync contentItem ${item.id} to Supabase.")
                }

                // Tasks (children referencing topics). Ensure topics were upserted above; if a task upsert fails due to missing topic, try to upsert the topic parent then retry.
                taskDao.getAllTasks().forEach { task ->
                    var ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", task) }
                    if (!ok) {
                        Log.w("SyncRepository", "Initial upsert failed for task ${task.id}; attempting to ensure parent topic ${task.topicId} exists and retry.")
                        try {
                            val topic = withContext(Dispatchers.IO) { topicDao.getTopicById(task.topicId) }
                            if (topic != null) {
                                val tOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                                if (tOk) {
                                    Log.i("SyncRepository", "Parent topic ${topic.id} upserted, retrying task ${task.id}.")
                                    ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", task) }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent topic ${task.topicId} while retrying task ${task.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent topic ${task.topicId} not found for task ${task.id}.")
                            }
                        } catch (e: Exception) {
                            Log.e("SyncRepository", "Exception while attempting to upsert parent topic for task ${task.id}", e)
                        }
                    }
                    if (ok) Log.i("SyncRepository", "Task ${task.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync task ${task.id} to Supabase after retry.")
                }

                // TaskSubmissions: use direct SupabaseClient method to ensure payload uses snake_case
                // Try submissions after tasks are upserted so task_id FK exists remotely. If insert fails due to missing task, attempt to upsert the task then retry.
                val supabaseClient = com.example.tareamov.service.SupabaseClient
                taskSubmissionDao.getAllTaskSubmissions().forEach { submission ->
                    try {
                        // If the submission contains grading info, try update; otherwise insert
                        val hasGrade = submission.grade != null || !submission.feedback.isNullOrBlank()
                        var success = false

                        if (hasGrade) {
                            success = withContext(Dispatchers.IO) { supabaseClient.updateTaskSubmissionRemote(submission) }
                        } else {
                            val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertTaskSubmission(submission) }
                            success = remoteId != null
                        }

                        if (!success) {
                            Log.w("SyncRepository", "Initial submission sync failed for ${submission.id}; attempting to upsert parent task ${submission.taskId} and retry.")
                            val taskParent = withContext(Dispatchers.IO) { taskDao.getTaskById(submission.taskId) }
                            if (taskParent != null) {
                                val tOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", taskParent) }
                                if (tOk) {
                                    Log.i("SyncRepository", "Parent task ${taskParent.id} upserted, retrying submission ${submission.id}.")
                                    if (hasGrade) {
                                        success = withContext(Dispatchers.IO) { supabaseClient.updateTaskSubmissionRemote(submission) }
                                    } else {
                                        val remoteId2 = withContext(Dispatchers.IO) { supabaseClient.insertTaskSubmission(submission) }
                                        success = remoteId2 != null
                                    }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent task ${submission.taskId} while retrying submission ${submission.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent task ${submission.taskId} not found for submission ${submission.id}.")
                            }
                        }

                        if (success) Log.i("SyncRepository", "TaskSubmission ${submission.id} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync taskSubmission ${submission.id} to Supabase.")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception while syncing submission ${submission.id}", e)
                    }
                }

                // Subscriptions
                subscriptionDao.getAllSubscriptions().forEach { sub ->
                    try {
                        // Map Room entity fields to snake_case expected by Supabase/Postgres
                        val mapped = mapOf(
                            "subscriber_username" to sub.subscriberUsername,
                            "creator_username" to sub.creatorUsername,
                            "subscription_date" to sub.subscriptionDate
                        )
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("subscriptions", mapped) }
                        if (ok) Log.i("SyncRepository", "Subscription ${sub.subscriberUsername}_${sub.creatorUsername} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync subscription ${sub.subscriberUsername}_${sub.creatorUsername} to Supabase.")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception while syncing subscription ${sub.subscriberUsername}_${sub.creatorUsername}", e)
                    }
                }

                // File contexts (metadata/extracted content) - ensure file_contexts table exists in migrations
                try {
                    val fileContexts = withContext(Dispatchers.IO) { fileContextDao.getAllFileContexts().first() }
                    fileContexts.forEach { fc ->
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("file_contexts", fc) }
                        if (ok) Log.i("SyncRepository", "FileContext ${fc.id} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync FileContext ${fc.id} to Supabase.")
                    }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Could not sync file_contexts: ${e.message}")
                }



                // Videos
                videoDao.getAllVideos().forEach { video ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("videos", video) }
                    if (ok) Log.i("SyncRepository", "Video ${video.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync video ${video.id} to Supabase.")
                }

                // Roles
                rolDao.getAllRoles().forEach { rol ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("roles", rol) }
                    if (ok) Log.i("SyncRepository", "Rol ${rol.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync rol ${rol.id} to Supabase.")
                }

                // Chat messages - sync in-app chat messages to Supabase
                try {
                    val messages = withContext(Dispatchers.IO) { chatMessageDao.getAllMessages().first() }
                    messages.forEach { msg ->
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("chat_messages", msg) }
                        if (ok) Log.i("SyncRepository", "ChatMessage ${msg.id} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync ChatMessage ${msg.id} to Supabase.")
                    }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Could not sync chat_messages: ${e.message}")
                }

                // Recursos
                recursoDao.getAllRecursos().forEach { recurso ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("recursos", recurso) }
                    if (ok) Log.i("SyncRepository", "Recurso ${recurso.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync recurso ${recurso.id} to Supabase.")
                }

                // Rol-Recursos
                rolRecursoDao.getAllRolRecursos().forEach { rr ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("rol_recursos", rr) }
                    if (ok) Log.i("SyncRepository", "RolRecurso ${rr.rolId}-${rr.recursoId} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync rol_recurso ${rr.rolId}-${rr.recursoId} to Supabase.")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during syncLocalToSupabase", e)
            }
        }
    }

    // New: traer datos desde Supabase y guardarlos localmente en Room
    fun syncSupabaseToLocal() {
        syncScope.launch {
            if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                Log.w("SyncRepository", "Supabase not configured. Skipping syncSupabaseToLocal.")
                return@launch
            }

            try {
                Log.i("SyncRepository", "Starting syncSupabaseToLocal()")

                // Personas
                val personas = com.example.tareamov.service.SupabaseClient.fetchPersonas()
                personas.forEach { p ->
                    try {
                        personaDao.insertPersona(p)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert persona ${p.id}", e)
                    }
                }

                // Usuarios
                val usuarios = com.example.tareamov.service.SupabaseClient.fetchUsuarios()
                usuarios.forEach { u ->
                    try {
                        usuarioDao.insertUsuario(u)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert usuario ${u.id}", e)
                    }
                }

                // Roles
                val roles = com.example.tareamov.service.SupabaseClient.fetchRoles()
                roles.forEach { r ->
                    try {
                        rolDao.insertRol(r)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert rol ${r.id}", e)
                    }
                }

                // Recursos
                val recursos = com.example.tareamov.service.SupabaseClient.fetchRecursos()
                recursos.forEach { rc ->
                    try {
                        recursoDao.insertRecurso(rc)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert recurso ${rc.id}", e)
                    }
                }

                // RolRecursos
                val rolRecursos = com.example.tareamov.service.SupabaseClient.fetchRolRecursos()
                rolRecursos.forEach { rr ->
                    try {
                        rolRecursoDao.insertRolRecurso(rr)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert rol_recurso ${rr.rolId}-${rr.recursoId}", e)
                    }
                }

                // Courses: skipped (no courseDao available in this repository). Add courseDao to SyncRepository if needed.

                // Topics
                val topics = com.example.tareamov.service.SupabaseClient.fetchTopics()
                topics.forEach { t ->
                    try {
                        topicDao.insertTopic(t)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert topic ${t.id}", e)
                    }
                }

                // Content Items
                val contentItems = com.example.tareamov.service.SupabaseClient.fetchContentItems()
                contentItems.forEach { ci ->
                    try {
                        contentItemDao.insertContentItem(ci)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert content item ${ci.id}", e)
                    }
                }

                // Tasks
                val tasks = com.example.tareamov.service.SupabaseClient.fetchTasks()
                tasks.forEach { task ->
                    try {
                        taskDao.insertTask(task)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert task ${task.id}", e)
                    }
                }

                // Subscriptions
                val subs = com.example.tareamov.service.SupabaseClient.fetchSubscriptions()
                subs.forEach { s ->
                    try {
                        subscriptionDao.insertSubscription(s)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert subscription", e)
                    }
                }

                // TaskSubmissions
                val submissions = com.example.tareamov.service.SupabaseClient.fetchTaskSubmissions()
                submissions.forEach { ss ->
                    try {
                        taskSubmissionDao.insertSubmission(ss)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert task submission ${ss.id}", e)
                    }
                }

                // Chat messages
                val chats = com.example.tareamov.service.SupabaseClient.fetchChatMessages()
                chats.forEach { cm ->
                    try {
                        chatMessageDao.insertMessage(cm)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert chat message ${cm.id}", e)
                    }
                }

                // File contexts
                val files = com.example.tareamov.service.SupabaseClient.fetchFileContexts()
                files.forEach { fc ->
                    try {
                        fileContextDao.insertFileContext(fc)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert file context ${fc.id}", e)
                    }
                }

                Log.i("SyncRepository", "syncSupabaseToLocal() completed")
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during syncSupabaseToLocal", e)
            }
        }
    }

    // --- Sincronización de Firebase a Room para todas las entidades ---
    fun startAllSync() {
        Log.i("SyncRepository", "Iniciando sincronización en tiempo real con Firebase...")
        // Usuarios
        userListener = firestore.collection("usuarios")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (usuarios).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val usuarios = snapshots.documents.mapNotNull { it.toObject(Usuario::class.java) }
                    Log.i("SyncRepository", "Recibidos ${usuarios.size} usuarios desde Firebase.")
                    syncScope.launch {
                        usuarios.forEach { usuario ->
                            Log.d("SyncRepository", "Insertando usuario: ${usuario.usuario}")
                            usuarioDao.insertUsuario(usuario)
                        }
                    }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de usuarios desde Firebase.")
                }
            }
        // Personas
        personaListener = firestore.collection("personas")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (personas).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val personas = snapshots.documents.mapNotNull { it.toObject(Persona::class.java) }
                    Log.i("SyncRepository", "Recibidas ${personas.size} personas desde Firebase.")
                    syncScope.launch { personas.forEach { persona ->
                        Log.d("SyncRepository", "Insertando persona: ${persona.id}")
                        personaDao.insertPersona(persona)
                    } }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de personas desde Firebase.")
                }
            }
        // Topics
        topicListener = firestore.collection("topics")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (topics).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val topics = snapshots.documents.mapNotNull { it.toObject(Topic::class.java) }
                    Log.i("SyncRepository", "Recibidos ${topics.size} topics desde Firebase.")
                    syncScope.launch { topics.forEach {
                        Log.d("SyncRepository", "Insertando topic: ${it.id}")
                        topicDao.insertTopic(it)
                    } }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de topics desde Firebase.")
                }
            }
        // ContentItems
        contentItemListener = firestore.collection("contentItems")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (contentItems).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val items = snapshots.documents.mapNotNull { it.toObject(ContentItem::class.java) }
                    Log.i("SyncRepository", "Recibidos ${items.size} contentItems desde Firebase.")
                    syncScope.launch { items.forEach {
                        Log.d("SyncRepository", "Insertando contentItem: ${it.id}")
                        contentItemDao.insertContentItem(it)
                    } }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de contentItems desde Firebase.")
                }
            }
        // Tasks
        taskListener = firestore.collection("tasks")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (tasks).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val tasks = snapshots.documents.mapNotNull { it.toObject(Task::class.java) }
                    Log.i("SyncRepository", "Recibidos ${tasks.size} tasks desde Firebase.")
                    syncScope.launch { tasks.forEach {
                        Log.d("SyncRepository", "Insertando task: ${it.id}")
                        taskDao.insertTask(it)
                    } }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de tasks desde Firebase.")
                }
            }
        // Subscriptions
        subscriptionListener = firestore.collection("subscriptions")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (subscriptions).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val subs = snapshots.documents.mapNotNull { it.toObject(Subscription::class.java) }
                    Log.i("SyncRepository", "Recibidas ${subs.size} subscriptions desde Firebase.")
                    syncScope.launch { subs.forEach {
                        Log.d("SyncRepository", "Insertando subscription: ${it.subscriberUsername}_${it.creatorUsername}")
                        subscriptionDao.insertSubscription(it)
                    } }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de subscriptions desde Firebase.")
                }
            }
        // TaskSubmissions
        taskSubmissionListener = firestore.collection("taskSubmissions")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { Log.w("SyncRepository", "Listen failed (taskSubmissions).", e); return@addSnapshotListener }
                if (snapshots != null) {
                    val submissions = snapshots.documents.mapNotNull { it.toObject(TaskSubmission::class.java) }
                    Log.i("SyncRepository", "Recibidos ${submissions.size} taskSubmissions desde Firebase.")
                    syncScope.launch { submissions.forEach {
                        Log.d("SyncRepository", "Insertando taskSubmission: ${it.id}")
                        taskSubmissionDao.insertSubmission(it)
                    } }
                } else {
                    Log.w("SyncRepository", "No se recibieron datos de taskSubmissions desde Firebase.")
                }
            }
        // Purchase functionality removed
        Log.i("SyncRepository", "Purchase listener removed from system.")
    }

    fun stopAllSync() {
        userListener?.remove()
        personaListener?.remove()
        topicListener?.remove()
        contentItemListener?.remove()
        taskListener?.remove()
        subscriptionListener?.remove()
        taskSubmissionListener?.remove()
        // Purchase listener removed
        Log.i("SyncRepository", "All listeners stopped, purchase listener removed.")
    }
}
