package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.tareamov.R
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.StudentProgressManager
import android.widget.TextView
import com.example.tareamov.util.CourseProgressManager
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StudentProgressFragment : Fragment() {

    private var courseId: Long = -1L
    private lateinit var sessionManager: SessionManager
    private lateinit var progressManager: StudentProgressManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1L)
        }
        sessionManager = SessionManager.getInstance(requireContext())
        progressManager = StudentProgressManager(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.component_student_progress, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val username = sessionManager.getUsername()
        if (username != null && courseId != -1L) {
            // Only show if not the course creator
            CoroutineScope(Dispatchers.Main).launch {
                val db = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
                val course = withContext(Dispatchers.IO) { db.videoDao().getVideoById(courseId) }
                if (course?.username != username) {
                    progressManager.loadStudentProgress(
                        courseId,
                        username,
                        view
                    )
                } else {
                    view.visibility = View.GONE
                }
            }
        } else {
            view.visibility = View.GONE
        }
    }

    companion object {
        fun newInstance(courseId: Long): StudentProgressFragment {
            return StudentProgressFragment().apply {
                arguments = Bundle().apply {
                    putLong("courseId", courseId)
                }
            }
        }
    }
}