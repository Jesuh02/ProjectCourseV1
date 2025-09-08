package com.example.tareamov.service

import com.example.tareamov.data.model.OllamaRequest
import com.example.tareamov.data.model.OllamaResponse
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface OllamaApiService {
    
    @POST("api/generate")
    suspend fun generateResponse(@Body request: OllamaRequest): Response<OllamaResponse>
    
    @POST("api/chat")
    suspend fun generateChat(@Body request: OllamaRequest): Response<OllamaResponse>
    
    companion object {
        const val BASE_URL = "http://localhost:11435/"
        
        fun create(): OllamaApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)  // Increased for RAG queries
                .writeTimeout(120, TimeUnit.SECONDS)  // Increased for large RAG contexts
                .build()
            
            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            
            return retrofit.create(OllamaApiService::class.java)
        }
    }
}
