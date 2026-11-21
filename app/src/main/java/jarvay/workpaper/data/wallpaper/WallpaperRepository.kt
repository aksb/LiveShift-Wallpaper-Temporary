package jarvay.workpaper.data.wallpaper

import androidx.annotation.WorkerThread
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WallpaperRepository @Inject constructor(
    private val wallpaperDao: WallpaperDao
) {
    @WorkerThread
    suspend fun insert(item: Wallpaper) {
        wallpaperDao.insert(item)
    }

    @WorkerThread
    suspend fun insert(item: List<Wallpaper>) {
        wallpaperDao.insert(item)
    }

    @WorkerThread
    suspend fun update(item: Wallpaper) {
        wallpaperDao.update(item)
    }

    @WorkerThread
    suspend fun delete(item: Wallpaper) {
        wallpaperDao.delete(item)
    }

    @WorkerThread
    suspend fun delete(ids: List<Long>) {
        wallpaperDao.delete(ids)
    }

    @WorkerThread
    suspend fun deleteByAlbumId(albumId: Long) {
        wallpaperDao.deleteByAlbumId(albumId)
    }

    fun existsByContentUri(contentUri: String): Boolean {
        return wallpaperDao.existsByContentUri(contentUri)
    }

    @WorkerThread
    suspend fun updateReaderTransform(contentUri: String, scale: Float, panX: Float, panY: Float) {
        wallpaperDao.updateReaderTransform(contentUri, scale, panX, panY)
    }

    @WorkerThread
    suspend fun getReaderTransform(contentUri: String): WallpaperReaderTransform? {
        return wallpaperDao.getReaderTransform(contentUri)
    }
}