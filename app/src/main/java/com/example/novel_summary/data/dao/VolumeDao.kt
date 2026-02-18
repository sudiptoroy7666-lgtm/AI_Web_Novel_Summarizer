package com.example.novel_summary.data.dao


import androidx.room.*
import com.example.novel_summary.data.model.Volume
import com.example.novel_summary.data.model.VolumeWithStats
import kotlinx.coroutines.flow.Flow

@Dao
interface VolumeDao {

    @Query("SELECT * FROM volumes_table WHERE novelId = :novelId ORDER BY volumeName ASC")
    fun getVolumesByNovelId(novelId: Long): Flow<List<Volume>>

    @Query("SELECT * FROM volumes_table WHERE id = :id")
    suspend fun getVolumeById(id: Long): Volume?

    @Query("SELECT * FROM volumes_table WHERE novelId = :novelId AND volumeName = :volumeName")
    suspend fun getVolumeByName(novelId: Long, volumeName: String): Volume?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVolume(volume: Volume): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVolumes(volumes: List<Volume>)

    @Update
    suspend fun updateVolume(volume: Volume)

    @Delete
    suspend fun deleteVolume(volume: Volume)

    @Query("DELETE FROM volumes_table WHERE id = :id")
    suspend fun deleteVolumeById(id: Long)

    @Query("DELETE FROM volumes_table WHERE novelId = :novelId")
    suspend fun deleteVolumesByNovelId(novelId: Long)

    @Query("DELETE FROM volumes_table")
    suspend fun deleteAllVolumes()

    @Query("""
        SELECT 
            v.id AS id,
            v.novelId AS novelId,
            v.volumeName AS volumeName,
            COUNT(c.id) AS chapter_count
        FROM volumes_table v
        LEFT JOIN chapters_table c ON v.id = c.volumeId
        WHERE v.novelId = :novelId
        GROUP BY v.id, v.novelId, v.volumeName
        ORDER BY v.volumeName ASC
    """)
    fun getVolumesWithStatsByNovelId(novelId: Long): Flow<List<VolumeWithStats>>  // ✅ kotlinx.coroutines.flow.Flow
}