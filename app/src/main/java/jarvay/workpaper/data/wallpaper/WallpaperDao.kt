package jarvay.workpaper.data.wallpaper

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface WallpaperDao {
    @Insert
    suspend fun insert(item: Wallpaper)

    @Insert
    suspend fun insert(item: List<Wallpaper>)

    @Update
    suspend fun update(item: Wallpaper)

    @Delete
    suspend fun delete(item: Wallpaper)

    @Query("DELETE FROM wallpapers where id in (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM wallpapers where albumId=:albumId")
    suspend fun deleteByAlbumId(albumId: Long)

    @Query("SELECT EXISTS (SELECT * FROM wallpapers WHERE contentUri=:contentUri )")
    fun existsByContentUri(contentUri: String): Boolean

    @Query(
        "UPDATE wallpapers SET readerScale=:scale, readerPanX=:panX, readerPanY=:panY " +
                "WHERE contentUri=:contentUri"
    )
    suspend fun updateReaderTransform(contentUri: String, scale: Float, panX: Float, panY: Float)

    @Query(
        "SELECT readerScale, readerPanX, readerPanY FROM wallpapers WHERE contentUri=:contentUri LIMIT 1"
    )
    suspend fun getReaderTransform(contentUri: String): WallpaperReaderTransform?
}

data class WallpaperReaderTransform(
    val readerScale: Float,
    val readerPanX: Float,
    val readerPanY: Float,
)