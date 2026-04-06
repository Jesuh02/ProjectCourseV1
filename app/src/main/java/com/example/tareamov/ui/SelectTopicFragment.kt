package com.example.tareamov.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.viewmodel.SelectTopicViewModel
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.adapter.TopicSelectionAdapter
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.launch

class SelectTopicFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = ""
    private var subjectId: Long = -1
    private var subjectName: String? = null
    private var autoOpenTasks: Boolean = false
    private var isCreatingTask: Boolean = false
    private var autoOpenedTasks: Boolean = false
    private lateinit var topicsRecyclerView: RecyclerView
    private lateinit var topicSelectionAdapter: TopicSelectionAdapter
    // private val topicsList = mutableListOf<Topic>() // Remove this - adapter handles the list
    private lateinit var viewModel: SelectTopicViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1)
            courseName = it.getString("courseName", "")
            subjectId = it.getLong("subjectId", -1)
            subjectName = it.getString("subjectName")
            autoOpenTasks = it.getBoolean("autoOpenTasks", false)
            isCreatingTask = it.getBoolean("isCreatingTask", false)
        }
        if (courseId == -1L) {
            Log.e("SelectTopicFragment", "Invalid courseId received.")
            Toast.makeText(context, "Error: ID de curso inválido", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp() // Go back if courseId is invalid
            return // Stop further execution in onCreate if ID is invalid
        }
        // Initialize ViewModel here - it's safer before view creation
        viewModel = ViewModelProvider(this)[SelectTopicViewModel::class.java]
        // If Supabase is configured, we will prefer remote topics.
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_select_topic, container, false)

        topicsRecyclerView = view.findViewById(R.id.topicsRecyclerView)
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        val courseTitleTextView = view.findViewById<TextView>(R.id.selectTopicCourseTitle)
        val noTopicsTextView = view.findViewById<TextView>(R.id.noTopicsSelectionTextView)
        val emptyStateLayout = view.findViewById<LinearLayout>(R.id.emptyStateLayout)
        val topBarCard = view.findViewById<CardView>(R.id.topBarCard)
        val courseTitleCard = view.findViewById<CardView>(R.id.courseTitleCard)
        val sectionHeader = view.findViewById<TextView>(R.id.sectionHeader)

        // Set course title
        courseTitleTextView.text = courseName ?: "Curso"

        // Back button with haptic feedback
        backButton.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            animateButtonPress(it)
            findNavController().navigateUp()
        }

        // Apply entrance animations
        applyEntranceAnimations(topBarCard, courseTitleCard, sectionHeader, topicsRecyclerView)

        setupRecyclerView()

        // If topics were already loaded before view creation, auto-open tasks immediately
        val preloaded = viewModel.topics.value
        if (!preloaded.isNullOrEmpty() && autoOpenTasks && !autoOpenedTasks) {
            val first = preloaded[0]
            val bundle = Bundle().apply {
                putLong("courseId", courseId)
                putString("courseName", courseName)
                putString("subjectName", subjectName)
                if (subjectId > 0) putLong("subjectId", subjectId)
                putLong("topicId", first.id)
                putLong("taskId", -1L)
            }
            autoOpenedTasks = true
            findNavController().navigate(R.id.action_selectTopicFragment_to_selectTaskFragment, bundle)
        }

        // Observe the topics LiveData from the ViewModel
        viewModel.topics.observe(viewLifecycleOwner) { topics ->
            // Update the adapter with the new list of topics
            topicSelectionAdapter.submitList(topics ?: emptyList())

            // Show/hide 'no topics' message with animation
            if (topics.isNullOrEmpty()) {
                Log.d("SelectTopicFragment", "No topics found for course $courseId")
                animateFadeOut(topicsRecyclerView)
                animateFadeIn(emptyStateLayout)
            } else {
                Log.d("SelectTopicFragment", "Displaying ${topics.size} topics")
                animateFadeOut(emptyStateLayout)
                animateFadeIn(topicsRecyclerView)
                // If we were asked to auto-open tasks, navigate to SelectTaskFragment for first topic
                if (autoOpenTasks && !autoOpenedTasks && !topics.isNullOrEmpty()) {
                    val first = topics[0]
                    val bundle = Bundle().apply {
                        putLong("courseId", courseId)
                        putString("courseName", courseName)
                        putString("subjectName", subjectName)
                        if (subjectId > 0) putLong("subjectId", subjectId)
                        putLong("topicId", first.id)
                        putLong("taskId", -1L)
                    }
                    autoOpenedTasks = true
                    findNavController().navigate(R.id.action_selectTopicFragment_to_selectTaskFragment, bundle)
                }
            }
        }

        lifecycleScope.launch {
            try {
                BackendApiService.initialize(requireContext())
                val result = if (subjectId > 0) BackendApiService.getTopicsBySubject(subjectId) else BackendApiService.getTopicsByCourse(courseId)
                when (result) {
                    is ApiResult.Success -> {
                        val remoteTopics = result.data
                        if (remoteTopics.isNotEmpty()) {
                            Log.d("SelectTopicFragment", "Loaded ${remoteTopics.size} topics from BackendApiService for course $courseId")
                            topicSelectionAdapter.submitList(remoteTopics)
                            animateFadeIn(topicsRecyclerView)
                            emptyStateLayout.visibility = View.GONE
                            // Auto-open tasks if requested
                            if (autoOpenTasks && !autoOpenedTasks) {
                                val firstTopic = remoteTopics[0]
                                val bundle = Bundle().apply {
                                    putLong("courseId", courseId)
                                    putString("courseName", courseName)
                                    putString("subjectName", subjectName)
                                    if (subjectId > 0) putLong("subjectId", subjectId)
                                    putLong("topicId", firstTopic.id)
                                    putLong("taskId", -1L)
                                }
                                autoOpenedTasks = true
                                findNavController().navigate(R.id.action_selectTopicFragment_to_selectTaskFragment, bundle)
                                return@launch
                            }
                        } else {
                            Log.d("SelectTopicFragment", "No topics from BackendApiService for subject $subjectId / course $courseId, falling back to ViewModel")
                            if (subjectId > 0) viewModel.fetchTopicsForSubject(subjectId) else viewModel.fetchTopicsForCourse(courseId)
                        }
                    }
                    is ApiResult.Error -> {
                        Log.w("SelectTopicFragment", "BackendApiService error: ${result.message}, falling back to ViewModel")
                        if (subjectId > 0) viewModel.fetchTopicsForSubject(subjectId) else viewModel.fetchTopicsForCourse(courseId)
                    }
                }
            } catch (e: Exception) {
                if (subjectId > 0) viewModel.fetchTopicsForSubject(subjectId) else viewModel.fetchTopicsForCourse(courseId)
            }
        }

        // --- TTS Button Injection ---
        val context = requireContext()
        val ttsService = com.example.tareamov.service.TTSService.getInstance(context)
        val wrapper = android.widget.FrameLayout(context)
        wrapper.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        // Remove view from its parent if necessary (though usually null from inflate)
        (view.parent as? ViewGroup)?.removeView(view)
        
        // Add original view to wrapper
        wrapper.addView(view, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        
        // Create and add FAB
        val fab = com.google.android.material.floatingactionbutton.FloatingActionButton(context)
        fab.setImageDrawable(androidx.core.content.ContextCompat.getDrawable(context, android.R.drawable.ic_lock_silent_mode_off))
        fab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#40C4FF"))
        fab.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        fab.compatElevation = 10f
        
        val params = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, 
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
        val margin = (16 * resources.displayMetrics.density).toInt()
        params.setMargins(margin, margin, margin, margin)
        
        fab.setOnClickListener {
             lifecycleScope.launch {
                 val topics = topicSelectionAdapter.currentList
                 val textToRead = "Lista de temas: " + 
                    if (topics.isEmpty()) "No hay temas disponibles." 
                    else topics.joinToString(", ") { it.name }
                 ttsService.speak(textToRead)
             }
        }
        
        wrapper.addView(fab, params)

        return wrapper // Return the wrapper containing both the layout and the FAB
    }

    private fun setupRecyclerView() {
        topicSelectionAdapter = TopicSelectionAdapter { selectedTopic ->
            Log.d("SelectTopicFragment", "Topic selected: ID=${selectedTopic.id}, Name=${selectedTopic.name}")
            val bundle = Bundle().apply {
                putLong("courseId", courseId)
                putString("courseName", courseName)
                putString("subjectName", subjectName)
                if (subjectId > 0) putLong("subjectId", subjectId)
                putLong("topicId", selectedTopic.id)
                putLong("taskId", -1L)
                putBoolean("isCreatingTask", isCreatingTask)
            }
            
            // Navegar al fragmento de selección de tarea o creación de tarea
            val destinationId = if (isCreatingTask) {
                R.id.action_selectTopicFragment_to_courseTaskFragment
            } else {
                R.id.action_selectTopicFragment_to_selectTaskFragment
            }
            findNavController().navigate(destinationId, bundle)
        }

        topicsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = topicSelectionAdapter
            // Smooth scrolling
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
    }

    // ============ ANIMATION FUNCTIONS -   Style ============

    private fun applyEntranceAnimations(vararg views: View?) {
        views.forEachIndexed { index, view ->
            view?.let {
                it.alpha = 0f
                it.translationY = 40f
                
                it.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(600)
                    .setStartDelay((index * 80).toLong())
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            }
        }
    }

    private fun animateFadeIn(view: View?) {
        view?.let {
            if (it.visibility == View.VISIBLE) return
            
            it.visibility = View.VISIBLE
            it.alpha = 0f
            it.scaleX = 0.95f
            it.scaleY = 0.95f
            
            it.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(OvershootInterpolator(0.5f))
                .start()
        }
    }

    private fun animateFadeOut(view: View?) {
        view?.let {
            if (it.visibility == View.GONE) return
            
            it.animate()
                .alpha(0f)
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    it.visibility = View.GONE
                }
                .start()
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }
}