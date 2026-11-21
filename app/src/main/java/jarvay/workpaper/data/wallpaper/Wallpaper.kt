package jarvay.workpaper.data.wallpaper

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class WallpaperType(val value: Int) {
    IMAGE(1),
    VIDEO(2)
}

@Entity(tableName = "wallpapers")
data class Wallpaper(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val wallpaperId: Long = 0,
    val albumId: Long = -1,
    val contentUri: String,
    @ColumnInfo(defaultValue = "IMAGE")
    val type: WallpaperType,
    @ColumnInfo(defaultValue = "NULL")
    val ratio: Float?,
    // 阅读器模式下这张图的双指缩放/平移状态，切到下一张再切回来时用它还原现场。
    // scale=1 表示未缩放（基准是"适合屏幕"整图可见）。
    @ColumnInfo(defaultValue = "1.0")
    val readerScale: Float = 1f,
    @ColumnInfo(defaultValue = "0.0")
    val readerPanX: Float = 0f,
    @ColumnInfo(defaultValue = "0.0")
    val readerPanY: Float = 0f,
)