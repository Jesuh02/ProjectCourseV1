package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Toast
import android.widget.TextView // ++ Add this import ++
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.viewmodel.SelectTopicViewModel // Import ViewModel
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.adapter.TopicSelectionAdapter
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Topic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectTopicFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = ""
    private lateinit var topicsRecyclerView: RecyclerView
    private lateinit var topicSelectionAdapter: TopicSelectionAdapter
    // private val topicsList = mutableListOf<Topic>() // Remove this - adapter handles the list
    private lateinit var viewModel: SelectTopicViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1)
            courseName = it.getString("courseName", "")
            Log.d("SelectTopicFragment", "Received courseId: $courseId, courseName: $courseName")
        }
        if (courseId == -1L) {
            Log.e("SelectTopicFragment", "Invalid courseId received.")
            Toast.makeText(context, "Error: ID de curso inválido", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp() // Go back if courseId is invalid
            return // Stop further execution in onCreate if ID is invalid
        }
        // Initialize ViewModel here - it's safer before view creation
        viewModel = ViewModelProvider(this)[SelectTopicViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_select_topic, container, false)

        topicsRecyclerView = view.findViewById(R.id.topicsRecyclerView) // Ensure this ID exists in XML
        val backButton = view.findViewById<ImageButton>(R.id.backButton) // Ensure this ID exists in XML
        // !! Ensure these IDs exist in fragment_select_topic.xml !!
        val courseTitleTextView = view.findViewById<TextView>(R.id.selectTopicCourseTitle)
        val noTopicsTextView = view.findViewById<TextView>(R.id.noTopicsSelectionTextView)

        courseTitleTextView.text = "Seleccionar Tema para Tarea en: ${courseName ?: "Curso"}" // This should work now

        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        setupRecyclerView() // Call setup function

        // Observe the topics LiveData from the ViewModel
        viewModel.topics.observe(viewLifecycleOwner) { topics ->
            // Update the adapter with the new list of topics
            topicSelectionAdapter.submitList(topics ?: emptyList()) // Use submitList (requires ListAdapter change below)

            // Show/hide 'no topics' message
            if (topics.isNullOrEmpty()) {
                Log.d("SelectTopicFragment", "No topics found for course $courseId")
                topicsRecyclerView.visibility = View.GONE // This should work now
                noTopicsTextView.visibility = View.VISIBLE
            } else {
                Log.d("SelectTopicFragment", "Displaying ${topics.size} topics")
                topicsRecyclerView.visibility = View.VISIBLE // This should work now
                noTopicsTextView.visibility = View.GONE
            }
        }

        // Fetch topics using the ViewModel
        viewModel.fetchTopicsForCourse(courseId)

        return view // Return the inflated view
    }

    private fun setupRecyclerView() {
        // ++ Initialize adapter passing only the click listener ++
        topicSelectionAdapter = TopicSelectionAdapter { selectedTopic ->
            // This lambda is executed when a topic is clicked in the adapter
            Log.d("SelectTopicFragment", "Topic selected: ID=${selectedTopic.id}, Name=${selectedTopic.name}")
            val bundle = Bundle().apply {
                putLong("courseId", courseId)
                putLong("topicId", selectedTopic.id) // Pass the selected topic's ID
                putLong("taskId", -1L) // Indicate creating a new task
            }
            findNavController().navigate(R.id.action_selectTopicFragment_to_courseTaskFragment, bundle)
        }

        // Configure the RecyclerView
        topicsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = topicSelectionAdapter // Assign the adapter instance
        }
    }
    // *** REMOVE the old loadTopics function ***
    /*
    private fun loadTopics() {
        if (courseId == -1L) return // Avoid loading if courseId is invalid

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val topics = db.topicDao().getTopicsForCourse(courseId)
                withContext(Dispatchers.Main) {
                    if (topics.isNotEmpty()) {
                        Log.d("SelectTopicFragment", "Loaded ${topics.size} topics for course $courseId")
                        topicsList.clear()
                        topicsList.addAll(topics)
                        topicSelectionAdapter.updateTopics(topicsList)
                    } else {
                        Log.d("SelectTopicFragment", "No topics found for course $courseId")
                        Toast.makeText(context, "No hay temas en este curso. Crea un tema primero.", Toast.LENGTH_LONG).show()
                        // Optionally navigate back or show a message
                         findNavController().navigateUp() // Go back if no topics exist
                    }
                }
            } catch (e: Exception) {
                Log.e("SelectTopicFragment", "Error loading topics", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Error al cargar los temas", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    */

    // Make sure any old function like loadTopics() or getTopicsForCourse()
    // defined directly within this Fragment is removed.
}