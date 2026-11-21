package jarvay.workpaper.data.style

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "styles")
data class Style(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val styleId: Long = 0,
    var name: String,
    var blurRadius: Int = 0,
    var noisePercent: Int = 0,
    // 亮度/对比度/饱和度改成 -50~50，0 = 默认不变（以前是 0~100、50 = 不变）。
    var brightness: Int = 0,
    var contrast: Int = 0,
    var saturation: Int = 0,
)