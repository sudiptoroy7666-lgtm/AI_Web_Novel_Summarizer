package com.example.novel_summary.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.novel_summary.data.model.Bookmark
import com.example.novel_summary.data.model.Chapter
import com.example.novel_summary.utils.audiobook.AudiobookCompression
import com.example.novel_summary.utils.audiobook.AudiobookPositionStore
import com.example.novel_summary.utils.audiobook.AudiobookStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

object SyncManager {

    private const val PREF_NAME = "SummaryPrefs"
    private const val QUEUE_FILE_NAME = "sync_queue.json"
    const val PREF_SYNC_ENABLED = "sync_enabled"
    const val PREF_SYNC_ON_SAVE = "sync_on_save"
    const val PREF_LAST_SYNC = "last_sync_timestamp"

    private const val BATCH_LIMIT = 400

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val syncQueue = ConcurrentLinkedQueue<SyncItem>()

    private fun queueFile(context: Context): File = File(context.filesDir, QUEUE_FILE_NAME)

    private fun persistQueue(context: Context) {
        try {
            val file = queueFile(context)
            val snapshot = syncQueue.toList()
            file.writeText(Gson().toJson(snapshot), Charsets.UTF_8)
        } catch (_: Exception) {}
    }

    private fun loadQueue(context: Context) {
        try {
            val file = queueFile(context)
            if (!file.exists()) return
            val json = file.readText(Charsets.UTF_8)
            if (json.isBlank()) return
            val type = object : TypeToken<List<SyncItem>>() {}.type
            val items: List<SyncItem>? = Gson().fromJson(json, type)
            if (items.isNullOrEmpty()) return
            syncQueue.clear()
            items.forEach { syncQueue.add(it) }
        } catch (_: Exception) {}
    }

    private data class SyncItem(
        val type: String,
        val localId: Long,
        val data: Map<String, Any>,
        val timestamp: Long = System.currentTimeMillis()
    )

    private fun toFirestoreId(vararg parts: String): String {
        val input = parts.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_SYNC_ENABLED, false)
    }

    fun isSyncOnSave(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_SYNC_ON_SAVE, true)
    }

    fun enqueueChapterSync(context: Context, chapter: Chapter, novelId: Long, volumeId: Long) {
        if (!isSyncEnabled(context)) return
        if (!isSyncOnSave(context)) return
        if (auth.currentUser == null) return

        val syncData = mapOf(
            "chapterName" to chapter.chapterName,
            "summaryText" to chapter.summaryText,
            "summaryType" to chapter.summaryType,
            "volumeId" to volumeId.toString(),
            "novelId" to novelId.toString(),
            "timestamp" to chapter.timestamp,
            "userId" to (auth.currentUser?.uid ?: "")
        )

        syncQueue.add(SyncItem("chapter", chapter.id, syncData))
        persistQueue(context)
    }

    fun enqueueBookmarkSync(context: Context, bookmark: Bookmark) {
        if (!isSyncEnabled(context)) return
        if (!isSyncOnSave(context)) return
        if (auth.currentUser == null) return

        val userId = auth.currentUser?.uid ?: return
        val syncData = mapOf(
            "url" to bookmark.url,
            "title" to bookmark.title,
            "timestamp" to bookmark.timestamp,
            "userId" to userId
        )

        syncQueue.add(SyncItem("bookmark", bookmark.id, syncData))
        persistQueue(context)

        val docId = toFirestoreId(bookmark.url)
        db.collection("users").document(userId)
            .collection("bookmarks").document(docId)
            .set(syncData)
    }

    private class BatchWriter {
        var batch = db.batch()
        var opCount = 0

        suspend fun set(ref: com.google.firebase.firestore.DocumentReference, data: Map<String, Any>) {
            batch.set(ref, data)
            opCount++
            if (opCount >= BATCH_LIMIT) flush()
        }

        suspend fun delete(ref: com.google.firebase.firestore.DocumentReference) {
            batch.delete(ref)
            opCount++
            if (opCount >= BATCH_LIMIT) flush()
        }

        suspend fun flush() {
            if (opCount > 0) {
                batch.commit().await()
                batch = db.batch()
                opCount = 0
            }
        }
    }

    suspend fun syncAllDataToFirebase(
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ) {
        val userId = auth.currentUser?.uid ?: throw Exception("Not authenticated")

        processPendingSyncQueue(context)

        val dbInstance = com.example.novel_summary.data.AppDatabase.getDatabase(context)
        val userRef = db.collection("users").document(userId)

        val novels = dbInstance.novelDao().getAllNovels().first()
        onProgress?.invoke("Syncing ${novels.size} novels...")

        val writer = BatchWriter()

        for (novel in novels) {
            val novelDocId = toFirestoreId(novel.name)
            val novelRef = userRef.collection("novels").document(novelDocId)
            val novelData = mapOf("name" to novel.name, "timestamp" to System.currentTimeMillis(), "userId" to userId)
            writer.set(novelRef, novelData)

            val volumes = dbInstance.volumeDao().getVolumesByNovelId(novel.id).first()
            for (volume in volumes) {
                val volumeDocId = toFirestoreId(novel.name, volume.volumeName)
                val volumeRef = novelRef.collection("volumes").document(volumeDocId)
                val volumeData = mapOf("volumeName" to volume.volumeName, "novelName" to novel.name, "timestamp" to System.currentTimeMillis(), "userId" to userId)
                writer.set(volumeRef, volumeData)

                val chapters = dbInstance.chapterDao().getChaptersByVolumeId(volume.id).first()
                onProgress?.invoke("Syncing ${novel.name} / ${volume.volumeName} (${chapters.size} chapters)...")

                for (chapter in chapters) {
                    val chapterDocId = toFirestoreId(novel.name, volume.volumeName, chapter.chapterName)
                    val chapterRef = volumeRef.collection("chapters").document(chapterDocId)
                    val chapterData = mapOf(
                        "chapterName" to chapter.chapterName, "summaryText" to chapter.summaryText,
                        "summaryType" to chapter.summaryType, "novelName" to novel.name,
                        "volumeName" to volume.volumeName, "timestamp" to chapter.timestamp, "userId" to userId
                    )
                    writer.set(chapterRef, chapterData)
                }
            }
        }

        val bookmarks = dbInstance.bookmarkDao().getAllBookmarks().first()
        onProgress?.invoke("Syncing ${bookmarks.size} bookmarks...")
        for (bookmark in bookmarks) {
            val bookmarkDocId = toFirestoreId(bookmark.url)
            val bookmarkRef = userRef.collection("bookmarks").document(bookmarkDocId)
            val bookmarkData = mapOf("url" to bookmark.url, "title" to bookmark.title, "timestamp" to bookmark.timestamp, "userId" to userId)
            writer.set(bookmarkRef, bookmarkData)
        }

        onProgress?.invoke("Uploading standard library...")
        writer.flush()

        // ── Upload Audiobooks ──
        val audiobooks = AudiobookStore.getBooks(context)
        onProgress?.invoke("Syncing ${audiobooks.size} audiobooks...")

        for (book in audiobooks) {
            val bookRef = userRef.collection("audiobooks").document(book.id)

            var needsTextUpload = true
            try {
                val existingDoc = bookRef.get().await()
                if (existingDoc.exists()) {
                    val cloudUpdatedAt = existingDoc.getLong("updatedAt") ?: 0
                    val cloudHasText = existingDoc.getBoolean("hasTextData") ?: false
                    if (cloudHasText && cloudUpdatedAt == book.updatedAt) {
                        needsTextUpload = false
                    }
                }
            } catch (_: Exception) {}

            val lastPos = AudiobookPositionStore.getPosition(context, book.id)

            val metaData = mapOf(
                "title" to book.title,
                "charCount" to book.charCount,
                "createdAt" to book.createdAt,
                "updatedAt" to book.updatedAt,
                "lastUnitIndex" to lastPos.first,
                "lastCharOffset" to lastPos.second,
                "userId" to userId
            )

            if (needsTextUpload) {
                onProgress?.invoke("Uploading audiobook: ${book.title}...")
                val text = AudiobookStore.readText(context, book)
                if (text != null) {
                    val compressed = AudiobookCompression.compress(text)
                    val fullData = metaData + mapOf("textData" to compressed, "hasTextData" to true)
                    try {
                        bookRef.set(fullData).await()
                    } catch (e: Exception) {
                        bookRef.set(metaData + mapOf("hasTextData" to false, "syncError" to "Text too large")).await()
                    }
                } else {
                    bookRef.set(metaData).await()
                }
            } else {
                bookRef.set(metaData, SetOptions.merge()).await()
            }
        }

        onProgress?.invoke("Sync complete!")
    }

    suspend fun processPendingSyncQueue(context: Context): Int {
        if (auth.currentUser == null) return 0
        loadQueue(context)
        if (syncQueue.isEmpty()) return 0

        val userId = auth.currentUser?.uid ?: return 0
        val pending = mutableListOf<SyncItem>()

        while (true) {
            val item = syncQueue.poll() ?: break
            pending.add(item)
        }

        if (pending.isEmpty()) return 0

        return try {
            val batch = db.batch()
            for (item in pending) {
                val ref = when (item.type) {
                    "bookmark" -> db.collection("users").document(userId)
                        .collection("bookmarks").document(toFirestoreId(item.data["url"].toString()))
                    else -> db.collection("users").document(userId)
                        .collection("chapters").document(toFirestoreId(item.data["chapterName"].toString(), item.data["volumeId"].toString(), item.data["novelId"].toString()))
                }
                batch.set(ref, item.data)
            }
            batch.commit().await()
            syncQueue.clear()
            persistQueue(context)
            0
        } catch (_: Exception) {
            pending.forEach { syncQueue.add(it) }
            persistQueue(context)
            pending.size
        }
    }

    suspend fun restoreDataFromFirebase(
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ) {
        val userId = auth.currentUser?.uid ?: throw Exception("Not authenticated")
        val dbInstance = com.example.novel_summary.data.AppDatabase.getDatabase(context)

        onProgress?.invoke("Reading cloud library...")
        val novelsSnapshot = db.collection("users").document(userId).collection("novels").get().await()
        onProgress?.invoke("Restoring ${novelsSnapshot.documents.size} novels...")

        for (novelDoc in novelsSnapshot.documents) {
            try {
                val name = novelDoc.getString("name") ?: continue
                val existingNovel = dbInstance.novelDao().getNovelByName(name)
                val novelId = existingNovel?.id ?: dbInstance.novelDao().insertNovel(com.example.novel_summary.data.model.Novel(name = name))

                val volumesSnapshot = db.collection("users").document(userId).collection("novels").document(novelDoc.id).collection("volumes").get().await()
                for (volumeDoc in volumesSnapshot.documents) {
                    val volumeName = volumeDoc.getString("volumeName") ?: continue
                    val existingVolume = dbInstance.volumeDao().getVolumeByName(novelId, volumeName)
                    val volumeId = existingVolume?.id ?: dbInstance.volumeDao().insertVolume(com.example.novel_summary.data.model.Volume(novelId = novelId, volumeName = volumeName))

                    val chaptersSnapshot = db.collection("users").document(userId).collection("novels").document(novelDoc.id).collection("volumes").document(volumeDoc.id).collection("chapters").get().await()
                    onProgress?.invoke("Restoring chapters for $name / $volumeName...")

                    for (chapterDoc in chaptersSnapshot.documents) {
                        val chapterName = chapterDoc.getString("chapterName") ?: continue
                        val summaryText = chapterDoc.getString("summaryText") ?: continue
                        val summaryType = chapterDoc.getString("summaryType") ?: "detailed"
                        val timestamp = chapterDoc.getLong("timestamp") ?: System.currentTimeMillis()

                        val existingChapter = dbInstance.chapterDao().getChapterByName(volumeId, chapterName)
                        if (existingChapter != null) {
                            dbInstance.chapterDao().updateChapter(existingChapter.copy(summaryText = summaryText, summaryType = summaryType, timestamp = timestamp))
                        } else {
                            dbInstance.chapterDao().insertChapter(com.example.novel_summary.data.model.Chapter(volumeId = volumeId, chapterName = chapterName, summaryText = summaryText, summaryType = summaryType, timestamp = timestamp))
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RestoreError", "Failed restoring novel: ${e.message}", e)
            }
        }

        try {
            onProgress?.invoke("Restoring bookmarks...")
            val bookmarksSnapshot = db.collection("users").document(userId).collection("bookmarks").get().await()
            onProgress?.invoke("Restoring ${bookmarksSnapshot.documents.size} bookmarks...")

            for (bookmarkDoc in bookmarksSnapshot.documents) {
                val url = bookmarkDoc.getString("url") ?: continue
                val title = bookmarkDoc.getString("title") ?: continue
                val timestamp = bookmarkDoc.getLong("timestamp") ?: System.currentTimeMillis()

                val existingBookmark = dbInstance.bookmarkDao().getBookmarkByUrl(url)
                if (existingBookmark != null) {
                    dbInstance.bookmarkDao().insertBookmark(existingBookmark.copy(title = title, timestamp = timestamp))
                } else {
                    dbInstance.bookmarkDao().insertBookmark(Bookmark(url = url, title = title, timestamp = timestamp))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RestoreError", "Failed restoring bookmarks: ${e.message}", e)
        }

        // ── Restore Audiobooks ──
        try {
            onProgress?.invoke("Restoring audiobooks...")
            val audiobooksSnapshot = db.collection("users").document(userId).collection("audiobooks").get().await()
            onProgress?.invoke("Restoring ${audiobooksSnapshot.documents.size} audiobooks...")

            for (bookDoc in audiobooksSnapshot.documents) {
                val id = bookDoc.id
                val title = bookDoc.getString("title") ?: continue
                val createdAt = bookDoc.getLong("createdAt") ?: System.currentTimeMillis()
                val updatedAt = bookDoc.getLong("updatedAt") ?: System.currentTimeMillis()
                val lastUnitIndex = (bookDoc.getLong("lastUnitIndex") ?: 0).toInt()
                val lastCharOffset = (bookDoc.getLong("lastCharOffset") ?: 0).toInt()
                val hasTextData = bookDoc.getBoolean("hasTextData") ?: false

                val localBook = AudiobookStore.getBookById(context, id)
                val needsDownload = localBook == null || (hasTextData && localBook.updatedAt < updatedAt)

                if (needsDownload && hasTextData) {
                    val compressed = bookDoc.getString("textData")
                    if (compressed != null) {
                        try {
                            val text = AudiobookCompression.decompress(compressed)
                            AudiobookStore.importFromCloud(context, id, title, text, createdAt, updatedAt, lastUnitIndex, lastCharOffset)
                        } catch (e: Exception) {
                            android.util.Log.e("RestoreError", "Failed to decompress audiobook $id", e)
                        }
                    }
                } else if (localBook != null) {
                    if (lastUnitIndex > 0 || lastCharOffset > 0) {
                        AudiobookPositionStore.savePosition(context, id, lastUnitIndex, lastCharOffset, true)
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RestoreError", "Failed restoring audiobooks: ${e.message}", e)
        }

        onProgress?.invoke("Restore complete!")
    }

    fun updateLastSyncTime(context: Context) {
        getPrefs(context).edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(PREF_LAST_SYNC, 0)
    }
}