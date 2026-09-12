package com.example.novel_summary.utils.customai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object CustomAiStore {

    private const val FILE_NAME = "custom_ai_apis.json"
    private const val PREFS_NAME = "secure_custom_ai_apis"

    private val RESERVED_NAMES = listOf(
        "auto",
        "auto (smart routing)",
        "cerebras",
        "groq primary",
        "groq fallback",
        "google ai (gemini)"
    )

    private fun prefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun jsonFile(context: Context): File = File(context.filesDir, FILE_NAME)

    fun getApis(context: Context): List<CustomAiApi> {
        return try {
            val encryptedValue = prefs(context).getString("custom_ai_apis_json", null)
            val json = encryptedValue ?: run {
                val file = jsonFile(context)
                if (!file.exists()) return emptyList()
                file.readText(Charsets.UTF_8).ifBlank { return emptyList() }
            }
            if (json.isBlank()) return emptyList()
            val type = object : TypeToken<List<CustomAiApi>>() {}.type
            val apis: List<CustomAiApi>? = Gson().fromJson(json, type)
            apis ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveApis(context: Context, apis: List<CustomAiApi>) {
        try {
            val json = Gson().toJson(apis)
            val securePrefs = prefs(context)
            securePrefs.edit().putString("custom_ai_apis_json", json).apply()
            val file = jsonFile(context)
            if (file.exists()) file.delete()
        } catch (_: Exception) {
            try {
                val file = jsonFile(context)
                file.writeText(Gson().toJson(apis), Charsets.UTF_8)
            } catch (_: Exception) {
            }
        }
    }

    fun upsertApi(context: Context, api: CustomAiApi) {
        val apis = getApis(context).toMutableList()
        val index = apis.indexOfFirst { it.id == api.id }

        if (index >= 0) {
            apis[index] = api
        } else {
            apis.add(api)
        }

        saveApis(context, apis)
    }

    fun deleteApi(context: Context, id: String) {
        val apis = getApis(context).filterNot { it.id == id }
        saveApis(context, apis)
    }

    fun maskApiKey(apiKey: String): String {
        if (apiKey.isBlank()) return ""
        return if (apiKey.length <= 6) "•••" else "${apiKey.take(2)}••••${apiKey.takeLast(2)}"
    }

    fun normalizeBaseUrl(rawUrl: String): String {
        var url = rawUrl.trim()

        if (url.isEmpty()) return url

        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            url = "https://$url"
        }

        while (url.endsWith("/")) {
            url = url.dropLast(1)
        }

        if (url.endsWith("chat/completions", true)) {
            url = url.dropLast("chat/completions".length)
        }

        while (url.endsWith("/")) {
            url = url.dropLast(1)
        }

        return "$url/"
    }

    fun validate(
        context: Context,
        existingApi: CustomAiApi?,
        name: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        maxCharsText: String
    ): String? {
        val trimmedName = name.trim()

        if (trimmedName.isBlank()) {
            return "Name is required"
        }

        if (baseUrl.isBlank()) {
            return "Base URL is required"
        }

        if (apiKey.isBlank()) {
            return "API key is required"
        }

        if (model.isBlank()) {
            return "Model is required"
        }

        val normalizedName = trimmedName.lowercase()

        if (RESERVED_NAMES.contains(normalizedName)) {
            return "This name is reserved. Please choose another name."
        }

        val normalizedBaseUrl = normalizeBaseUrl(baseUrl)

        if (!normalizedBaseUrl.startsWith("http://", true) &&
            !normalizedBaseUrl.startsWith("https://", true)
        ) {
            return "Base URL must start with http:// or https://"
        }

        val maxChars = maxCharsText.trim().toIntOrNull()
            ?: return "Max characters must be a number"

        if (maxChars < 1_000) {
            return "Max characters must be at least 1000"
        }

        if (maxChars > 2_000_000) {
            return "Max characters is too large. Max allowed: 2,000,000"
        }

        val nameAlreadyExists = getApis(context).any { api ->
            api.id != existingApi?.id && api.name.equals(trimmedName, ignoreCase = true)
        }

        if (nameAlreadyExists) {
            return "An API with this name already exists"
        }

        return null
    }
}