package com.example.tareamov.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// Models for Backend communication

data class PSEBank(
    @SerializedName("id") val id: String,
    @SerializedName("description") val description: String,
    @SerializedName("code") val code: String? = null
)

data class PaymentInitiationRequest(
    @SerializedName("courseId") val courseId: Long,
    @SerializedName("userId") val userId: Long,
    @SerializedName("amount") val amount: Double,
    @SerializedName("bankCode") val bankCode: String,
    @SerializedName("payerEmail") val payerEmail: String,
    @SerializedName("payerName") val payerName: String,
    @SerializedName("payerDocType") val payerDocType: String,
    @SerializedName("payerDocNumber") val payerDocNumber: String,
    @SerializedName("ipAddress") val ipAddress: String,
    @SerializedName("userAgent") val userAgent: String
)

data class PaymentInitiationResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("urlBankPayment") val urlBankPayment: String?,
    @SerializedName("transactionId") val transactionId: String?,
    @SerializedName("message") val message: String?
)

data class PaymentStatusResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("status") val status: String?, // successful, pending, failed
    @SerializedName("reference") val reference: String?
)

interface PaymentApi {
    
    @GET("api/payment/banks")
    suspend fun getBanks(): Response<List<PSEBank>>

    @POST("api/payment/initiate")
    suspend fun initiatePayment(
        @Body request: PaymentInitiationRequest
    ): Response<PaymentInitiationResponse>
    
    @GET("api/payment/status/{reference}")
    suspend fun getTransactionStatus(
        @retrofit2.http.Path("reference") reference: String
    ): Response<PaymentStatusResponse>

    companion object {
        val BASE_URL: String
            get() = com.example.tareamov.BuildConfig.BACKEND_URL.let { if (it.endsWith("/")) it else "$it/" }

        fun create(baseUrl: String): PaymentApi {
            // Security: Use Level.NONE for production to avoid logging PII
            // Use Level.BODY only during development/debugging
            val interceptor = okhttp3.logging.HttpLoggingInterceptor().apply { 
                level = okhttp3.logging.HttpLoggingInterceptor.Level.NONE 
            }
            // Add long timeouts because payment creation might take a few seconds
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            return retrofit2.Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
                .create(PaymentApi::class.java)
        }
    }
}
