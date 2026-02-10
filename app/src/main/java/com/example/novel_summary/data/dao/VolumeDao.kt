package com.example.novel_summary.data.dao


import androidx.room.*
import com.example.novel_summary.data.model.Volume
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
}