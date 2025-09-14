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
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.TaskSubmission
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            try {
                // Check that the target table exists before attempting to sync
                val requiredTable = "app_documents"
                if (!supabaseRepo.tableExists(requiredTable)) {
                    Log.w("SyncRepository", "Required Supabase table '$requiredTable' not found. Skipping sync. Apply migrations first.")
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

                // Topics
                topicDao.getAllTopics().forEach { topic ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                    if (ok) Log.i("SyncRepository", "Topic ${topic.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync topic ${topic.id} to Supabase.")
                }

                // ContentItems
                contentItemDao.getAllContentItems().forEach { item ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("content_items", item) }
                    if (ok) Log.i("SyncRepository", "ContentItem ${item.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync contentItem ${item.id} to Supabase.")
                }

                // Tasks
                taskDao.getAllTasks().forEach { task ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", task) }
                    if (ok) Log.i("SyncRepository", "Task ${task.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync task ${task.id} to Supabase.")
                }

                // Subscriptions
                subscriptionDao.getAllSubscriptions().forEach { sub ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("subscriptions", sub) }
                    if (ok) Log.i("SyncRepository", "Subscription synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync subscription to Supabase.")
                }

                // TaskSubmissions
                taskSubmissionDao.getAllTaskSubmissions().forEach { submission ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("task_submissions", submission) }
                    if (ok) Log.i("SyncRepository", "TaskSubmission ${submission.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync taskSubmission ${submission.id} to Supabase.")
                }

                // Videos
                videoDao.getAllVideos().forEach { video ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("videos", video) }
                    if (ok) Log.i("SyncRepository", "Video ${video.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync video ${video.id} to Supabase.")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during syncLocalToSupabase", e)
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
