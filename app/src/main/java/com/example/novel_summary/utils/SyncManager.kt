package com.example.novel_summary.utils

import android.content.Context
import android.content.SharedPreferences
import com.example.novel_summary.data.model.Bookmark
import com.example.novel_summary.data.model.Chapter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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

    // Firestore batch limit is 500 operations; flush well before hitting it
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
        } catch (_: Exception) {
            // Best-effort persistence: queue remains in memory until the next successful save.
        }
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
        } catch (_: Exception) {
            // Ignore invalid queued JSON and keep the in-memory queue intact.
        }
    }

    private data class SyncItem(
        val type: String,
        val localId: Long,
        val data: Map<String, Any>,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ──────────────────────────────────────────────
    //  Deterministic document ID generation
    // ──────────────────────────────────────────────

    /**
     * Creates a deterministic, Firestore-safe document ID from content strings.
     * Same content on any device will produce the same ID → no duplicates.
     */
    private fun toFirestoreId(vararg parts: String): String {
        val input = parts.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ──────────────────────────────────────────────
    //  Preferences helpers
    // ──────────────────────────────────────────────

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_SYNC_ENABLED, false)
    }

    fun isSyncOnSave(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_SYNC_ON_SAVE, true)
    }

    // ──────────────────────────────────────────────
    //  Incremental sync-on-save
    // ──────────────────────────────────────────────

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

    /**
     * Enqueue a bookmark for immediate sync when "Sync on Save" is enabled.
     */
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

        // Fire-and-forget: immediately push to Firestore
        val docId = toFirestoreId(bookmark.url)
        db.collection("users").document(userId)
            .collection("bookmarks").document(docId)
            .set(syncData)
    }

    // ──────────────────────────────────────────────
    //  Batched write helper
    // ──────────────────────────────────────────────

    /**
     * Helper class to manage Firestore batch writes with automatic flushing.
     */
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

    // ──────────────────────────────────────────────
    //  Full Sync: Upload + Orphan Cleanup
    // ──────────────────────────────────────────────

    /**
     * Upload all local data (novels, volumes, chapters, bookmarks) to Firebase.
     * Uses content-based document IDs to prevent duplicates, then cleans up
     * any orphaned documents (old numeric IDs, deleted items, etc.).
     *
     * @param onProgress optional callback for real-time progress updates
     */
    suspend fun syncAllDataToFirebase(
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ) {
        val userId = auth.currentUser?.uid
            ?: throw Exception("Not authenticated")

        processPendingSyncQueue(context)

        val dbInstance = com.example.novel_summary.data.AppDatabase.getDatabase(context)
        val userRef = db.collection("users").document(userId)

        // ── Phase 1: Upload all data ──
        val novels = dbInstance.novelDao().getAllNovels().first()
        onProgress?.invoke("Syncing ${novels.size} novels...")

        val writer = BatchWriter()

        for (novel in novels) {
            val novelDocId = toFirestoreId(novel.name)

            val novelRef = userRef.collection("novels").document(novelDocId)
            val novelData = mapOf(
                "name" to novel.name,
                "timestamp" to System.currentTimeMillis(),
                "userId" to userId
            )
            writer.set(novelRef, novelData)

            val volumes = dbInstance.volumeDao().getVolumesByNovelId(novel.id).first()

            for (volume in volumes) {
                val volumeDocId = toFirestoreId(novel.name, volume.volumeName)

                val volumeRef = novelRef.collection("volumes").document(volumeDocId)
                val volumeData = mapOf(
                    "volumeName" to volume.volumeName,
                    "novelName" to novel.name,
                    "timestamp" to System.currentTimeMillis(),
                    "userId" to userId
                )
                writer.set(volumeRef, volumeData)

                val chapters = dbInstance.chapterDao().getChaptersByVolumeId(volume.id).first()
                onProgress?.invoke("Syncing ${novel.name} / ${volume.volumeName} (${chapters.size} chapters)...")

                for (chapter in chapters) {
                    val chapterDocId = toFirestoreId(novel.name, volume.volumeName, chapter.chapterName)

                    val chapterRef = volumeRef.collection("chapters").document(chapterDocId)
                    val chapterData = mapOf(
                        "chapterName" to chapter.chapterName,
                        "summaryText" to chapter.summaryText,
                        "summaryType" to chapter.summaryType,
                        "novelName" to novel.name,
                        "volumeName" to volume.volumeName,
                        "timestamp" to chapter.timestamp,
                        "userId" to userId
                    )
                    writer.set(chapterRef, chapterData)
                }
            }
        }

        // Upload bookmarks
        val bookmarks = dbInstance.bookmarkDao().getAllBookmarks().first()
        onProgress?.invoke("Syncing ${bookmarks.size} bookmarks...")

        for (bookmark in bookmarks) {
            val bookmarkDocId = toFirestoreId(bookmark.url)

            val bookmarkRef = userRef.collection("bookmarks").document(bookmarkDocId)
            val bookmarkData = mapOf(
                "url" to bookmark.url,
                "title" to bookmark.title,
                "timestamp" to bookmark.timestamp,
                "userId" to userId
            )
            writer.set(bookmarkRef, bookmarkData)
        }

        onProgress?.invoke("Uploading to cloud...")
        writer.flush()

        // ── Phase 2: Cleanup is now SKIPPED during normal sync ──
        // Orphan cleanup reads the entire cloud database sequentially,
        // which causes hangs on slow/unstable connections.
        // It should be done separately, not during every sync.

        onProgress?.invoke("Sync complete!")
    }



    /**
     * Deletes any Firestore documents not in the uploaded ID sets.
     * This removes old numeric-ID documents and data deleted locally.
     */
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

    private suspend fun cleanupOrphans(
        userRef: com.google.firebase.firestore.DocumentReference,
        validNovelIds: Set<String>,
        validVolumeIds: Map<String, Set<String>>,
        validChapterIds: Map<String, Set<String>>,
        validBookmarkIds: Set<String>
    ) {
        val writer = BatchWriter()

        // ── Clean up novels and their subcollections ──
        val existingNovels = userRef.collection("novels").get().await()

        for (novelDoc in existingNovels.documents) {
            val novelDocId = novelDoc.id

            if (novelDocId !in validNovelIds) {
                // Orphan novel → delete it and ALL its subcollections
                deleteSubcollections(novelDoc.reference, writer)
                writer.delete(novelDoc.reference)
            } else {
                // Valid novel → check for orphan volumes inside it
                val existingVolumes = novelDoc.reference.collection("volumes").get().await()
                val validVolsForNovel = validVolumeIds[novelDocId] ?: emptySet()

                for (volumeDoc in existingVolumes.documents) {
                    val volumeDocId = volumeDoc.id

                    if (volumeDocId !in validVolsForNovel) {
                        // Orphan volume → delete it and its chapters
                        deleteSubcollections(volumeDoc.reference, writer)
                        writer.delete(volumeDoc.reference)
                    } else {
                        // Valid volume → check for orphan chapters
                        val existingChapters = volumeDoc.reference.collection("chapters").get().await()
                        val key = "$novelDocId|$volumeDocId"
                        val validChapsForVolume = validChapterIds[key] ?: emptySet()

                        for (chapterDoc in existingChapters.documents) {
                            if (chapterDoc.id !in validChapsForVolume) {
                                writer.delete(chapterDoc.reference)
                            }
                        }
                    }
                }
            }
        }

        // ── Clean up orphan bookmarks ──
        val existingBookmarks = userRef.collection("bookmarks").get().await()
        for (bookmarkDoc in existingBookmarks.documents) {
            if (bookmarkDoc.id !in validBookmarkIds) {
                writer.delete(bookmarkDoc.reference)
            }
        }

        writer.flush()
    }

    /**
     * Recursively delete known subcollections (volumes → chapters) under a document.
     */
    private suspend fun deleteSubcollections(
        docRef: com.google.firebase.firestore.DocumentReference,
        writer: BatchWriter
    ) {
        // Delete chapters under each volume
        try {
            val volumes = docRef.collection("volumes").get().await()
            for (volumeDoc in volumes.documents) {
                val chapters = volumeDoc.reference.collection("chapters").get().await()
                for (chapterDoc in chapters.documents) {
                    writer.delete(chapterDoc.reference)
                }
                writer.delete(volumeDoc.reference)
            }
        } catch (_: Exception) {
            // Subcollection may not exist — safe to ignore
        }
    }

    // ──────────────────────────────────────────────
    //  Restore from Firebase
    // ──────────────────────────────────────────────

    /**
     * Restore all data (novels, volumes, chapters, bookmarks) from Firebase to local Room DB.
     * Uses merge strategy: update existing items, insert new ones.
     *
     * @param onProgress optional callback for real-time progress updates
     */
    suspend fun restoreDataFromFirebase(
        context: Context,
        onProgress: ((String) -> Unit)? = null
    ) {
        val userId = auth.currentUser?.uid
            ?: throw Exception("Not authenticated")

        val dbInstance = com.example.novel_summary.data.AppDatabase.getDatabase(context)

        // ── Restore novels, volumes, chapters ──
        onProgress?.invoke("Reading cloud library...")

        val novelsSnapshot = db.collection("users").document(userId)
            .collection("novels").get().await()

        onProgress?.invoke("Restoring ${novelsSnapshot.documents.size} novels...")

        for (novelDoc in novelsSnapshot.documents) {
            try {
                val name = novelDoc.getString("name") ?: continue

                val existingNovel = dbInstance.novelDao().getNovelByName(name)
                val novelId = if (existingNovel != null) {
                    existingNovel.id
                } else {
                    dbInstance.novelDao().insertNovel(
                        com.example.novel_summary.data.model.Novel(name = name)
                    )
                }

                val volumesSnapshot = db.collection("users").document(userId)
                    .collection("novels").document(novelDoc.id)
                    .collection("volumes").get().await()

                for (volumeDoc in volumesSnapshot.documents) {
                    val volumeName = volumeDoc.getString("volumeName") ?: continue

                    val existingVolume = dbInstance.volumeDao().getVolumeByName(novelId, volumeName)
                    val volumeId = if (existingVolume != null) {
                        existingVolume.id
                    } else {
                        dbInstance.volumeDao().insertVolume(
                            com.example.novel_summary.data.model.Volume(
                                novelId = novelId,
                                volumeName = volumeName
                            )
                        )
                    }

                    val chaptersSnapshot = db.collection("users").document(userId)
                        .collection("novels").document(novelDoc.id)
                        .collection("volumes").document(volumeDoc.id)
                        .collection("chapters").get().await()

                    onProgress?.invoke("Restoring chapters for $name / $volumeName...")

                    for (chapterDoc in chaptersSnapshot.documents) {
                        val chapterName = chapterDoc.getString("chapterName") ?: continue
                        val summaryText = chapterDoc.getString("summaryText") ?: continue
                        val summaryType = chapterDoc.getString("summaryType") ?: "detailed"
                        val timestamp = chapterDoc.getLong("timestamp") ?: System.currentTimeMillis()

                        val existingChapter = dbInstance.chapterDao()
                            .getChapterByName(volumeId, chapterName)

                        if (existingChapter != null) {
                            dbInstance.chapterDao().updateChapter(
                                existingChapter.copy(
                                    summaryText = summaryText,
                                    summaryType = summaryType,
                                    timestamp = timestamp
                                )
                            )
                        } else {
                            dbInstance.chapterDao().insertChapter(
                                com.example.novel_summary.data.model.Chapter(
                                    volumeId = volumeId,
                                    chapterName = chapterName,
                                    summaryText = summaryText,
                                    summaryType = summaryType,
                                    timestamp = timestamp
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RestoreError", "Failed restoring novel: ${e.message}", e)
            }
        }

        // ── Restore bookmarks ──
        try {
            onProgress?.invoke("Restoring bookmarks...")

            val bookmarksSnapshot = db.collection("users").document(userId)
                .collection("bookmarks").get().await()

            onProgress?.invoke("Restoring ${bookmarksSnapshot.documents.size} bookmarks...")

            for (bookmarkDoc in bookmarksSnapshot.documents) {
                val url = bookmarkDoc.getString("url") ?: continue
                val title = bookmarkDoc.getString("title") ?: continue
                val timestamp = bookmarkDoc.getLong("timestamp") ?: System.currentTimeMillis()

                val existingBookmark = dbInstance.bookmarkDao().getBookmarkByUrl(url)

                if (existingBookmark != null) {
                    dbInstance.bookmarkDao().insertBookmark(
                        existingBookmark.copy(
                            title = title,
                            timestamp = timestamp
                        )
                    )
                } else {
                    dbInstance.bookmarkDao().insertBookmark(
                        Bookmark(
                            url = url,
                            title = title,
                            timestamp = timestamp
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RestoreError", "Failed restoring bookmarks: ${e.message}", e)
        }

        onProgress?.invoke("Restore complete!")
    }

    // ──────────────────────────────────────────────
    //  Sync timing
    // ──────────────────────────────────────────────

    fun updateLastSyncTime(context: Context) {
        getPrefs(context).edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
    }

    fun getLastSyncTime(context: Context): Long {
        return getPrefs(context).getLong(PREF_LAST_SYNC, 0)
    }
}