package com.example.novel_summary.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.novel_summary.BuildConfig
import com.example.novel_summary.data.model.Chapter
import com.example.novel_summary.data.model.Novel
import com.example.novel_summary.data.model.Volume
import com.example.novel_summary.data.network.ChatCompletionRequest
import com.example.novel_summary.data.network.Message
import com.example.novel_summary.data.repository.SummaryRepository
import com.example.novel_summary.utils.SummaryPrompts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import retrofit2.HttpException

class RateLimitException(message: String) : Exception(message)

data class ApiProvider(
    val name: String,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val maxChars: Int,
    val maxChunks: Int = 5,        // Reduced for rate limit safety
    val chunkDelayMs: Long = 1000  // Delay between chunks (ms)
)

class SummaryViewModel(application: Application) : AndroidViewModel(application) {

    private val summaryRepository: SummaryRepository
    private val TAG = "SummaryViewModel"

    init {
        val database = com.example.novel_summary.App.database
        summaryRepository = SummaryRepository(
            database.novelDao(),
            database.volumeDao(),
            database.chapterDao()
        )
    }

    // ============= CRUD OPERATIONS =============
    fun getAllNovels() = summaryRepository.getAllNovels()
    fun getVolumesByNovelId(novelId: Long): Flow<List<Volume>> = summaryRepository.getVolumesByNovelId(novelId)
    fun getChaptersByVolumeId(volumeId: Long): Flow<List<Chapter>> = summaryRepository.getChaptersByVolumeId(volumeId)
    suspend fun getNovelByName(name: String): Novel? = summaryRepository.getNovelByName(name)
    suspend fun getVolumeByName(novelId: Long, volumeName: String): Volume? = summaryRepository.getVolumeByName(novelId, volumeName)
    suspend fun getChapterByName(volumeId: Long, chapterName: String): Chapter? = summaryRepository.getChapterByName(volumeId, chapterName)
    suspend fun insertNovel(novel: Novel): Long = summaryRepository.insertNovel(novel)
    suspend fun insertVolume(volume: Volume): Long = summaryRepository.insertVolume(volume)
    suspend fun insertChapter(chapter: Chapter): Long = summaryRepository.insertChapter(chapter)
    fun updateNovel(novel: Novel) { viewModelScope.launch { summaryRepository.updateNovel(novel) } }
    fun updateVolume(volume: Volume) { viewModelScope.launch { summaryRepository.updateVolume(volume) } }
    fun updateChapter(chapter: Chapter) { viewModelScope.launch { summaryRepository.updateChapter(chapter) } }
    fun deleteNovel(novel: Novel) { viewModelScope.launch { summaryRepository.deleteNovel(novel) } }
    fun deleteVolume(volume: Volume) { viewModelScope.launch { summaryRepository.deleteVolume(volume) } }
    fun deleteChapter(chapter: Chapter) { viewModelScope.launch { summaryRepository.deleteChapter(chapter) } }

    // ============= 3-TIER FALLBACK SYSTEM =============
    private fun getAvailableProviders(): List<ApiProvider> {
        val providers = mutableListOf<ApiProvider>()

        // ✅ PRIMARY: Cerebras (Ultra-fast, reliable)
        if (BuildConfig.CEREBRAS_API_KEY.isNotBlank()) {
            providers.add(
                ApiProvider(
                    name = "Cerebras",
                    apiKey = BuildConfig.CEREBRAS_API_KEY,
                    model = BuildConfig.CEREBRAS_MODEL,
                    baseUrl = BuildConfig.CEREBRAS_BASE_URL,
                    maxChars = BuildConfig.MAX_CONTENT_CEREBRAS,
                    maxChunks = 6,
                    chunkDelayMs = 500  // Cerebras is VERY fast
                )
            )
        }



        // FALLBACK: Groq Primary

        if (BuildConfig.GROQ_API_KEY_PRIMARY.isNotBlank()) {
            providers.add(
                ApiProvider(
                    name = "Groq Primary (70B)",
                    apiKey = BuildConfig.GROQ_API_KEY_PRIMARY,
                    model = BuildConfig.GROQ_MODEL_PRIMARY,
                    baseUrl = BuildConfig.GROQ_BASE_URL,
                    maxChars = BuildConfig.MAX_CONTENT_PRIMARY,
                    maxChunks = 3,
                    chunkDelayMs = 3000
                )
            )
        }

        // FALLBACK: Groq Fallback
        if (BuildConfig.GROQ_API_KEY_FALLBACK.isNotBlank()) {
            providers.add(
                ApiProvider(
                    name = "Groq Fallback (8B)",
                    apiKey = BuildConfig.GROQ_API_KEY_FALLBACK,
                    model = BuildConfig.GROQ_MODEL_FALLBACK,
                    baseUrl = BuildConfig.GROQ_BASE_URL,
                    maxChars = BuildConfig.MAX_CONTENT_FALLBACK,
                    maxChunks = 4,
                    chunkDelayMs = 3000
                )
            )
        }

        return providers
    }
    /**
     * PROPER SOLUTION: Hierarchical chunking for complete summaries
     * 1. If content fits in one request → send it directly
     * 2. If too large → chunk, summarize each chunk, then combine summaries
     */
    suspend fun generateSummary(content: String, summaryType: String): Result<String> {


        val providers = getAvailableProviders()

        if (providers.isEmpty()) {
            return Result.failure(Exception("No API keys configured"))
        }

        Log.i(TAG, "Starting summary generation with ${providers.size} providers")
        Log.i(TAG, "Original content length: ${content.length} chars")

        // Try each provider
        for ((index, provider) in providers.withIndex()) {
            Log.i(TAG, "Provider ${index + 1}/${providers.size}: ${provider.name}")

            val result = if (content.length <= provider.maxChars) {
                // Content fits in one request - send directly
                Log.i(TAG, "Content fits in single request (${content.length} <= ${provider.maxChars})")
                trySingleSummary(provider, content, summaryType)
            } else {
                // Content too large - use hierarchical chunking
                Log.i(TAG, "Content too large, using chunking strategy")
                tryChunkedSummary(provider, content, summaryType)
            }

            if (result.isSuccess) {
                Log.i(TAG, "✓ Success with ${provider.name}")
                return result
            }

            val error = result.exceptionOrNull()
            Log.w(TAG, "${provider.name} failed: ${error?.message}")

            // Continue to next provider on auth/rate limit errors
            if (!shouldContinueToNextProvider(error) && index == providers.size - 1) {
                return result
            }
        }

        return Result.failure(Exception("All providers failed"))
    }

    /**
     * Try to summarize in a single request
     */
    private suspend fun trySingleSummary(
        provider: ApiProvider,
        content: String,
        summaryType: String
    ): Result<String> {
        return tryGenerateWithConfig(
            provider = provider,
            content = content,
            summaryType = summaryType,
            chunkInfo = null
        )
    }

    /**
     * Hierarchical chunking strategy:
     * 1. Split content into chunks
     * 2. Summarize each chunk
     * 3. Combine chunk summaries into final summary
     */
    private suspend fun tryChunkedSummary(
        provider: ApiProvider,
        content: String,
        summaryType: String
    ): Result<String> {
        // Calculate chunk size (leave room for prompt overhead)
        val chunkSize = (provider.maxChars * 0.85).toInt()
        val chunks = splitIntoChunks(content, chunkSize)

        Log.i(TAG, "Split into ${chunks.size} chunks (max ${provider.maxChunks} allowed)")

        if (chunks.size > provider.maxChunks) {
            Log.w(TAG, "Too many chunks (${chunks.size}), reducing content...")
            // Reduce content instead of skipping parts
            val reducedContent = reduceContentAggressive(content, provider.maxChars * provider.maxChunks)
            val reducedChunks = splitIntoChunks(reducedContent, chunkSize)
            return processChunks(provider, reducedChunks, reducedChunks.size, summaryType)
        }

        return processChunks(provider, chunks, chunks.size, summaryType)
    }

    /**
     * Aggressively reduce content while keeping most important parts
     */
    private fun reduceContentAggressive(content: String, targetSize: Int): String {
        if (content.length <= targetSize) return content

        Log.i(TAG, "Reducing content from ${content.length} to ~$targetSize chars")

        // Take first 70% and last 30% (important info usually at start and end)
        val startSize = (targetSize * 0.7).toInt()
        val endSize = targetSize - startSize

        val start = content.take(startSize)
        val end = content.takeLast(endSize)

        return "$start\n\n... [content reduced] ...\n\n$end"
    }

    /**
     * Process chunks: summarize each, then combine
     */
    private suspend fun processChunks(
        provider: ApiProvider,
        chunks: List<String>,
        totalChunks: Int,
        summaryType: String
    ): Result<String> {
        val chunkSummaries = mutableListOf<String>()

        // Step 1: Summarize each chunk
        for ((index, chunk) in chunks.withIndex()) {
            Log.i(TAG, "Processing chunk ${index + 1}/${chunks.size} (${chunk.length} chars)")

            val chunkResult = tryGenerateWithConfig(
                provider = provider,
                content = chunk,
                summaryType = "detailed", // Always use detailed for chunks
                chunkInfo = "Part ${index + 1} of $totalChunks"
            )

            if (chunkResult.isFailure) {
                Log.e(TAG, "Failed to summarize chunk ${index + 1}: ${chunkResult.exceptionOrNull()?.message}")
                return chunkResult
            }

            chunkSummaries.add(chunkResult.getOrNull() ?: "")

            // Rate limit protection - use provider-specific delay
            if (index < chunks.size - 1) {
                Log.d(TAG, "Waiting ${provider.chunkDelayMs}ms before next chunk...")
                delay(provider.chunkDelayMs)
            }
        }

        // Step 2: Combine chunk summaries into final summary
        Log.i(TAG, "Combining ${chunkSummaries.size} chunk summaries")
        val combinedContent = chunkSummaries.joinToString("\n\n---\n\n")

        // Add delay before final combination request
        delay(provider.chunkDelayMs)

        val finalResult = tryGenerateWithConfig(
            provider = provider,
            content = combinedContent,
            summaryType = summaryType,
            chunkInfo = "Final combined summary"
        )

        return finalResult
    }

    /**
     * Split content into chunks at natural boundaries
     */
    private fun splitIntoChunks(content: String, chunkSize: Int): List<String> {
        if (content.length <= chunkSize) return listOf(content)

        val chunks = mutableListOf<String>()
        var remaining = content

        while (remaining.isNotEmpty()) {
            if (remaining.length <= chunkSize) {
                chunks.add(remaining)
                break
            }

            // Find natural break point (paragraph, sentence)
            var breakPoint = chunkSize

            // Try to break at paragraph first
            val paragraphBreak = remaining.lastIndexOf("\n\n", breakPoint)
            if (paragraphBreak > chunkSize * 0.6) {
                breakPoint = paragraphBreak + 2
            } else {
                // Try to break at sentence
                val sentenceBreak = maxOf(
                    remaining.lastIndexOf(". ", breakPoint),
                    remaining.lastIndexOf("! ", breakPoint),
                    remaining.lastIndexOf("? ", breakPoint),
                    remaining.lastIndexOf("。", breakPoint),  // Chinese punctuation
                    remaining.lastIndexOf("！", breakPoint),
                    remaining.lastIndexOf("？", breakPoint)
                )
                if (sentenceBreak > chunkSize * 0.6) {
                    breakPoint = sentenceBreak + 2
                }
            }

            chunks.add(remaining.substring(0, breakPoint).trim())
            remaining = remaining.substring(breakPoint).trim()
        }

        return chunks.filter { it.isNotBlank() }
    }

    /**
     * Try to generate summary with provider
     */
    private suspend fun tryGenerateWithConfig(
        provider: ApiProvider,
        content: String,
        summaryType: String,
        chunkInfo: String?
    ): Result<String> {
        if (provider.apiKey.isBlank()) {
            return Result.failure(IllegalArgumentException("API key is blank"))
        }

        if (content.isEmpty()) {
            return Result.failure(IllegalArgumentException("Content is empty"))
        }

        val prompt = when (summaryType) {
            "short" -> SummaryPrompts.getShortSummaryPrompt(content)
            "detailed" -> SummaryPrompts.getDetailedSummaryPrompt(content)
            "very_detailed" -> SummaryPrompts.getVeryDetailedSummaryPrompt(content)
            else -> SummaryPrompts.getDetailedSummaryPrompt(content)
        }

        val finalPrompt = if (chunkInfo != null) {
            "[$chunkInfo]\n\n$prompt"
        } else {
            prompt
        }

        val service = createService(provider.apiKey, provider.baseUrl, provider.name)

        // ✅ FIX: Dynamic max_tokens based on content size
        val baseTokens = when (summaryType) {
            "short" -> 800
            "detailed" -> 2000
            "very_detailed" -> 4000
            else -> 1500
        }

        val maxTokens = if (content.length > 100000) {
            // For large content, increase token limit
            minOf((baseTokens * 1.5).toInt(), 4096)
        } else {
            baseTokens
        }

        var attempt = 0
        val maxAttempts = 3

        while (attempt < maxAttempts) {
            try {
                val request = ChatCompletionRequest(
                    model = provider.model,
                    messages = listOf(
                        Message(role = "system", content = "You are a helpful assistant that summarizes webnovel chapters concisely and accurately."),
                        Message(role = "user", content = finalPrompt)
                    ),
                    temperature = 0.7f,
                    max_tokens = maxTokens
                )

                Log.d(TAG, "Request to ${provider.name}: ${content.length} chars" +
                        if (chunkInfo != null) " ($chunkInfo)" else "")

                val response = service.createChatCompletion(request)

                // ✅ FIX: Better response parsing for different API formats
                val summary = response.choices.firstOrNull()?.message?.content

                // Debug: Log full response for GLM
                // In tryGenerateWithConfig, after getting response:
                // Debug log for GLM
                if (provider.name.contains("GLM", ignoreCase = true)) {
                    Log.d(TAG, "=== GLM DEBUG ===")
                    Log.d(TAG, "Response ID: ${response.id}")
                    Log.d(TAG, "Model: ${response.model}")
                    Log.d(TAG, "Choices count: ${response.choices.size}")
                    response.choices.forEachIndexed { index, choice ->
                        Log.d(TAG, "Choice $index: message=${choice.message.content?.take(100)}")
                    }
                    Log.d(TAG, "Usage: ${response.usage}")
                    Log.d(TAG, "=================")
                }

                if (summary.isNullOrBlank()) {
                    // Try to get error message from response
                    val errorMsg = try {
                        response.toString()
                    } catch (e: Exception) {
                        "Unknown error"
                    }
                    Log.e(TAG, "Empty response from ${provider.name}. Response: $errorMsg")
                    return Result.failure(Exception("Empty response from ${provider.name}"))
                }

                Log.d(TAG, "✓ Response: ${summary.length} chars")
                return Result.success(summary.trim())

            } catch (e: HttpException) {

                Log.w(TAG, "${provider.name} HTTP ${e.code()}")

                // ✅ ADD: Log error response body for debugging
                val errorBody = try {
                    e.response()?.errorBody()?.string()
                } catch (ex: Exception) {
                    "Could not read error body"
                }
                Log.e(TAG, "${provider.name} Error Response: $errorBody")


                when (e.code()) {
                    401, 403 -> return Result.failure(Exception("${provider.name} auth failed (${e.code()})"))
                    413 -> return Result.failure(Exception("413: payload too large for ${provider.name}"))
                    429 -> {
                        // Rate limit - exponential backoff
                        val waitTime = (2000L * (attempt + 1))
                        Log.i(TAG, "Rate limited, waiting ${waitTime}ms (attempt ${attempt + 1}/$maxAttempts)")
                        if (attempt < maxAttempts - 1) {
                            delay(waitTime)
                            attempt++
                            continue
                        }
                        return Result.failure(RateLimitException("${provider.name} rate limited after $maxAttempts retries"))
                    }
                    in 500..599 -> {
                        if (attempt < maxAttempts - 1) {
                            val waitTime = 1000L * (attempt + 1)
                            Log.i(TAG, "Server error, waiting ${waitTime}ms")
                            delay(waitTime)
                            attempt++
                            continue
                        }
                        return Result.failure(Exception("${provider.name} server error after $maxAttempts retries"))
                    }
                    else -> return Result.failure(Exception("${provider.name} HTTP ${e.code()}"))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Exception: ${e.message}", e)
                return Result.failure(e)
            }
        }

        return Result.failure(Exception("Max retries exceeded"))
    }

    private fun shouldContinueToNextProvider(error: Throwable?): Boolean {
        if (error == null) return false

        val msg = error.message?.lowercase() ?: ""
        return msg.contains("401") || msg.contains("403") ||
                msg.contains("auth") || msg.contains("permission") ||
                error is RateLimitException || msg.contains("429") ||
                msg.contains("rate limit")
    }

    private fun createService(
        apiKey: String,
        baseUrl: String,
        providerName: String
    ): com.example.novel_summary.data.network.GroqApiService {
        val loggingInterceptor = okhttp3.logging.HttpLoggingInterceptor().apply {
            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC  // Reduced logging
        }

        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")

                // GLM/Z.AI specific headers
                if (providerName.contains("GLM", ignoreCase = true)) {
                    requestBuilder.header("User-Agent", "Novel-Summary-App/1.0")
                }

                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)  // Increased for chunking
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        return retrofit2.Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(com.example.novel_summary.data.network.GroqApiService::class.java)
    }
}