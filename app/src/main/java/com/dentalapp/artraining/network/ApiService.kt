package com.dentalapp.artraining.network

import com.dentalapp.artraining.data.models.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import com.dentalapp.artraining.BuildConfig
import com.dentalapp.artraining.data.Project
import com.dentalapp.artraining.data.ProjectResponse
import com.dentalapp.artraining.data.TrainingSession

interface DentalApiInterface {

    @GET("projects/{id}")
    suspend fun getProject(@Path("id") projectId: String): ProjectResponse

    @GET("projects")
    suspend fun getProjects(): List<ProjectResponse>

    @POST("sessions")
    suspend fun createSession(@Body session: TrainingSession): SessionResponse

    @PUT("sessions/{id}")
    suspend fun updateSession(
        @Path("id") sessionId: String,
        @Body session: TrainingSession
    ): SessionResponse

    @POST("reports")
    suspend fun generateReport(@Body request: ReportRequest): ReportResponse

    @GET("models/{project_id}/bracket.glb")
    suspend fun getBracketModel(@Path("project_id") projectId: String): okhttp3.ResponseBody
}

class ApiService {

    companion object {
        private const val BASE_URL = "https://api.dentaltraining.app/" // Replace with actual URL
        private const val TIMEOUT_SECONDS = 30L
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) {
                val loggingInterceptor = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                }
                addInterceptor(loggingInterceptor)
            }
        }
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "DentalAR-Android/1.0")
                // Add auth header if needed
                // .addHeader("Authorization", "Bearer $token")
                .build()
            chain.proceed(request)
        }
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api = retrofit.create(DentalApiInterface::class.java)

    suspend fun getProject(projectId: String): Project {
        return api.getProject(projectId).toProject()
    }

    suspend fun getAvailableProjects(): List<Project> {
        return api.getProjects().map { it.toProject() }
    }

    suspend fun createSession(session: TrainingSession): String {
        val response = api.createSession(session)
        return response.sessionId
    }

    suspend fun uploadSession(session: TrainingSession): Boolean {
        return try {
            api.updateSession(session.id, session)
            true
        } catch (e: Exception) {
            android.util.Log.e("ApiService", "Failed to upload session", e)
            false
        }
    }

    suspend fun generateReport(sessionId: String, format: ReportFormat): String {
        val request = ReportRequest(sessionId = sessionId, format = format)
        val response = api.generateReport(request)
        return response.downloadUrl
    }

    suspend fun downloadBracketModel(projectId: String): ByteArray? {
        return try {
            val response = api.getBracketModel(projectId)
            response.bytes()
        } catch (e: Exception) {
            android.util.Log.e("ApiService", "Failed to download bracket model", e)
            null
        }
    }
}

// Response models
data class SessionResponse(
    val sessionId: String,
    val status: String
)

data class ReportRequest(
    val sessionId: String,
    val format: ReportFormat
)

data class ReportResponse(
    val reportId: String,
    val downloadUrl: String,
    val expiresAt: Long
)

enum class ReportFormat {
    PDF, CSV, JSON
}