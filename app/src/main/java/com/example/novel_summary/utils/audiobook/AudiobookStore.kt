package com.example.novel_summary.utils.audiobook

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AudiobookStore {

    private const val DIRECTORY_NAME = "audiobooks"
    private const val INDEX_FILE_NAME = "index.json"

    private const val MAX_IMPORT_CHARS = 1_000_000
    private const val MAX_IMPORT_BYTES = 4_000_000

    fun getBooks(context: Context): List<AudiobookMeta> {
        return try {
            val file = getIndexFile(context)
            if (!file.exists()) return emptyList()

            val json = file.readText(Charsets.UTF_8)
            if (json.isBlank()) return emptyList()

            val type = object : TypeToken<List<AudiobookMeta>>() {}.type
            val books: List<AudiobookMeta>? = Gson().fromJson(json, type)

            books?.sortedByDescending { it.updatedAt } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getBookById(context: Context, id: String): AudiobookMeta? {
        return getBooks(context).firstOrNull { it.id == id }
    }

    private fun saveBooks(context: Context, books: List<AudiobookMeta>) {
        try {
            val file = getIndexFile(context)
            val json = Gson().toJson(books)
            file.writeText(json, Charsets.UTF_8)
        } catch (e: Exception) {
            // Silent fail
        }
    }

    fun importFromText(
        context: Context,
        rawTitle: String,
        text: String
    ): Result<AudiobookMeta> {
        return try {
            val title = rawTitle.trim().ifBlank { "Imported Book" }
            val normalizedText = text.trim()

            if (normalizedText.length < 100) {
                return Result.failure(Exception("Text is too short. Please paste at least 100 characters."))
            }

            if (normalizedText.length > MAX_IMPORT_CHARS) {
                return Result.failure(Exception("Text is too large. Maximum allowed is 1,000,000 characters."))
            }

            val directory = getDirectory(context)
            val id = "book_" + UUID.randomUUID().toString().replace("-", "").take(12)
            val fileName = "$id.txt"
            val file = File(directory, fileName)

            file.writeText(normalizedText, Charsets.UTF_8)

            val meta = AudiobookMeta(
                id = id,
                title = title,
                fileName = fileName,
                charCount = normalizedText.length,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            val books = getBooks(context).toMutableList()
            books.add(meta)
            saveBooks(context, books)

            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}"))
        }
    }

    fun importFromCloud(
        context: Context,
        id: String,
        title: String,
        text: String,
        createdAt: Long,
        updatedAt: Long,
        lastUnitIndex: Int,
        lastCharOffset: Int
    ): Result<AudiobookMeta> {
        return try {
            val directory = getDirectory(context)
            val fileName = "$id.txt"
            val file = File(directory, fileName)

            val tempFile = File(directory, "$fileName.tmp")
            tempFile.writeText(text, Charsets.UTF_8)
            if (file.exists()) file.delete()
            tempFile.renameTo(file)

            val meta = AudiobookMeta(
                id = id,
                title = title,
                fileName = fileName,
                charCount = text.length,
                createdAt = createdAt,
                updatedAt = updatedAt
            )

            val books = getBooks(context).toMutableList()
            val existingIndex = books.indexOfFirst { it.id == id }
            if (existingIndex >= 0) {
                books[existingIndex] = meta
            } else {
                books.add(meta)
            }
            saveBooks(context, books)

            if (lastUnitIndex > 0 || lastCharOffset > 0) {
                AudiobookPositionStore.savePosition(context, id, lastUnitIndex, lastCharOffset, true)
            }

            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(Exception("Cloud import failed: ${e.message}"))
        }
    }

    fun importFromUri(
        context: Context,
        uri: Uri,
        titleOverride: String? = null
    ): Result<AudiobookMeta> {
        return try {
            val textResult = readTextFromUri(context, uri)
            if (textResult.isFailure) {
                return Result.failure(textResult.exceptionOrNull() ?: Exception("Failed to read file"))
            }

            val text = textResult.getOrNull() ?: ""
            val displayName = titleOverride?.takeIf { it.isNotBlank() } ?: getDisplayName(context, uri)
            val title = cleanFileNameToTitle(displayName)

            importFromText(context, title, text)
        } catch (e: Exception) {
            Result.failure(Exception("Import failed: ${e.message}"))
        }
    }

    fun deleteBook(context: Context, id: String) {
        try {
            val books = getBooks(context).toMutableList()
            val book = books.firstOrNull { it.id == id } ?: return

            books.remove(book)
            saveBooks(context, books)

            val file = File(getDirectory(context), book.fileName)
            if (file.exists()) {
                file.delete()
            }

            AudiobookPositionStore.clearPosition(context, id)
        } catch (e: Exception) {
            // Silent fail
        }
    }

    fun getTextFile(context: Context, meta: AudiobookMeta): File {
        return File(getDirectory(context), meta.fileName)
    }

    fun readText(context: Context, meta: AudiobookMeta): String? {
        return try {
            getTextFile(context, meta).readText(Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun getDetails(meta: AudiobookMeta): String {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())
        val formattedChars = String.format(Locale.getDefault(), "%,d", meta.charCount)
        return "$formattedChars chars • ${dateFormat.format(Date(meta.updatedAt))}"
    }

    private fun readTextFromUri(context: Context, uri: Uri): Result<String> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(Exception("Cannot open file"))

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var totalBytes = 0

            inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    if (totalBytes > MAX_IMPORT_BYTES) {
                        return Result.failure(Exception("File is too large. Maximum allowed is about 1,000,000 characters."))
                    }
                    output.write(buffer, 0, read)
                }
            }

            val text = output.toString(Charsets.UTF_8.name())
            if (text.isBlank()) Result.failure(Exception("File is empty")) else Result.success(text)
        } catch (e: Exception) {
            Result.failure(Exception("Could not read file: ${e.message}"))
        }
    }

    private fun getDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanFileNameToTitle(rawName: String?): String {
        val name = rawName?.trim()?.ifBlank { null } ?: return "Imported Book"
        val simpleName = name.substringAfterLast('/').substringAfterLast('\\')
        val withoutExtension = if (simpleName.endsWith(".txt", true)) simpleName.dropLast(4) else simpleName
        return withoutExtension.trim().ifBlank { "Imported Book" }
    }

    private fun getDirectory(context: Context): File {
        val directory = File(context.filesDir, DIRECTORY_NAME)
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    private fun getIndexFile(context: Context): File {
        return File(getDirectory(context), INDEX_FILE_NAME)
    }
}