package com.example.tareamov.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
//
data class PseTransactionRequest(
    @SerializedName("bankCode") val bankCode: String,
    @SerializedName("bankInterface") val bankInterface: String = "0",
    @SerializedName("returnURL") val returnURL: String,
    @SerializedName("reference") val reference: String,
    @SerializedName("description") val description: String,
    @SerializedName("language") val language: String = "es",
    @SerializedName("payer") val payer: Payer,
    @SerializedName("payment") val payment: Payment,
    @SerializedName("ipAddress") val ipAddress: String,
    @SerializedName("userAgent") val userAgent: String
)

data class Payer(
    @SerializedName("documentType") val documentType: String,
    @SerializedName("document") val document: String,
    @SerializedName("name") val name: String,
    @SerializedName("surname") val surname: String,
    @SerializedName("company") val company: String? = null,
    @SerializedName("emailAddress") val emailAddress: String,
    @SerializedName("mobile") val mobile: String? = null
)

data class Payment(
    @SerializedName("reference") val reference: String,
    @SerializedName("description") val description: String,
    @SerializedName("amount") val amount: Amount
)

data class Amount(
    @SerializedName("currency") val currency: String = "COP",
    @SerializedName("total") val total: Double
)

data class PseTransactionResponse(
    @SerializedName("transactionResponse") val transactionResponse: TransactionResponse
)

data class TransactionResponse(
    @SerializedName("orderId") val orderId: Int,
    @SerializedName("transactionId") val transactionId: Int,
    @SerializedName("transactionState") val transactionState: String,
    @SerializedName("paymentNetworkResponseCode") val paymentNetworkResponseCode: String,
    @SerializedName("trazabilityCode") val trazabilityCode: String,
    @SerializedName("responseCode") val responseCode: String,
    @SerializedName("responseMessage") val responseMessage: String,
    @SerializedName("urlBankPayment") val urlBankPayment: String
)

interface PaymentApi {
    @POST("payments-api/4.0/service.cgi")
    suspend fun createPseTransaction(
        @Header("Content-Type") contentType: String = "application/json",
        @Header("Accept") accept: String = "application/json",
        @Header("Authorization") authorization: String,
        @Header("x-merchant-id") merchantId: String,
        @Body request: PseTransactionRequest
    ): Response<PseTransactionResponse>

    companion object {
        fun create(baseUrl: String, apiKey: String, apiLogin: String, merchantId: String): PaymentApi {
            val interceptor = okhttp3.logging.HttpLoggingInterceptor().apply { level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY }
            val client = okhttp3.OkHttpClient.Builder()
                .addInterceptor(interceptor)
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