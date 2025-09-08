package com.example.tareamov.repository

import androidx.lifecycle.LiveData
import androidx.lifecycle.liveData
import com.example.tareamov.data.dao.TopicDao
import com.example.tareamov.data.entity.Topic
import kotlinx.coroutines.Dispatchers

class TopicRepository(private val topicDao: TopicDao) {

    // Change this to use liveData builder to convert suspend function to LiveData
    val allTopics: LiveData<List<Topic>> = liveData(Dispatchers.IO) {
        emit(topicDao.getAllTopics())
    }

    suspend fun getTopicsByIds(ids: List<Long>): List<Topic> {
        return topicDao.getTopicsByIds(ids)
    }

    suspend fun getTopicById(id: Long): Topic? {
        return topicDao.getTopicById(id)
    }

    suspend fun insert(topic: Topic): Long {
        return topicDao.insertTopic(topic)
    }

    suspend fun update(topic: Topic) {
        topicDao.updateTopic(topic)
    }

    suspend fun delete(topic: Topic) {
        topicDao.deleteTopic(topic.id)
    }
}