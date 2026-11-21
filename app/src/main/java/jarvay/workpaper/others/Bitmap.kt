package jarvay.workpaper.others

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.IntRange
import com.blankj.utilcode.util.LogUtils
import com.google.android.renderscript.Toolkit
import java.util.Random
import kotlin.math.max
import kotlin.math.min


fun Bitmap.scaleFixedRatio(targetWidth: Int, targetHeight: Int, useMin: Boolean = true): Bitmap {
    val scaleWidth = (targetWidth.toFloat()) / width
    val scaleHeight = (targetHeight.toFloat()) / height

    val scaleRatio = if (useMin) {
        min(scaleHeight.toDouble(), scaleWidth.toDouble()).toFloat()
    } else {
        max(scaleHeight.toDouble(), scaleWidth.toDouble()).toFloat()
    }
    val matrix = Matrix()
    matrix.postScale(scaleRatio, scaleRatio)
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

/**
 * 统一的"缩放模式"实现，取代原来 scaleFixedRatio(useMin) + centerCrop 的组合。
 *
 * @param allowOverflow 为 true 时（对应"允许壁纸滚动"开启的动态壁纸），
 * 只做等比缩放、不裁剪不合成画布，直接把缩放结果返回（可能比屏幕宽，
 * 用于横向滚动）；为 false 时，把缩放结果居中合成到一张目标尺寸的黑色画布上，
 * 超出部分自然被画布裁掉，不够的部分露出黑边。
 */
fun Bitmap.scaleForScreen(
    targetWidth: Int,
    targetHeight: Int,
    scaleType: WallpaperScaleType,
    fixedPercent: Int,
    allowOverflow: Boolean = false,
): Bitmap {
    val widthRatio = targetWidth.toFloat() / width
    val heightRatio = targetHeight.toFloat() / height
    val coverRatio = max(widthRatio, heightRatio)

    val ratio = when (scaleType) {
        WallpaperScaleType.BY_HEIGHT -> heightRatio
        WallpaperScaleType.BY_WIDTH -> widthRatio
        WallpaperScaleType.FIT -> min(widthRatio, heightRatio)
        WallpaperScaleType.FIXED_PERCENTAGE ->
            coverRatio * (fixedPercent.coerceIn(10, 300) / 100f)
    }

    val matrix = Matrix().apply { postScale(ratio, ratio) }
    val scaled = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)

    if (allowOverflow) return scaled

    if (scaled.width == targetWidth && scaled.height == targetHeight) return scaled

    val canvasBitmap = Bitmap.createBitmap(
        targetWidth, targetHeight, scaled.config ?: Bitmap.Config.ARGB_8888
    )
    Canvas(canvasBitmap).apply {
        drawColor(Color.BLACK)
        val left = (targetWidth - scaled.width) / 2f
        val top = (targetHeight - scaled.height) / 2f
        drawBitmap(scaled, left, top, null)
    }
    if (scaled != this) scaled.recycle()
    return canvasBitmap
}

fun Bitmap.centerCrop(targetWidth: Int, targetHeight: Int): Bitmap {
    val srcRate = width.toFloat() / height.toFloat()
    val desRate: Float = targetWidth.toFloat() / targetHeight.toFloat()
    var dx = 0
    var dy = 0
    if (srcRate == desRate) {
        return this
    } else if (srcRate > desRate) {
        dx = (width - targetWidth) / 2
    } else {
        dy = (height - targetHeight) / 2
    }

    val desBitmap = Bitmap.createBitmap(this, dx, dy, targetWidth, targetHeight)
    return desBitmap
}

fun Bitmap.info(): String {
    return "width: $width, height: $height"
}

fun coverBitmapFromContentUri(contentUri: Uri, context: Context): Bitmap? {
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(context, contentUri)
    return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
}

fun bitmapFromContentUri(
    contentUri: Uri,
    context: Context,
    options: BitmapFactory.Options = BitmapFactory.Options()
): Bitmap? {
    fun fromStream(): Bitmap? {
        return try {
            context.contentResolver.openInputStream(contentUri)
                ?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream, null, options.apply {
                        inMutable = true
                    })
                }
        } catch (e: Exception) {
            LogUtils.w("bitmapFromContentUri", "Load bitmap failed", e.toString())
            e.printStackTrace()
            null
        }
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            val source =
                ImageDecoder.createSource(context.contentResolver, contentUri)
            ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } catch (e: Exception) {
            fromStream()
        }
    } else {
        fromStream()
    }
}

/**
 * 模糊半径支持到 [MAX_BLUR_RADIUS]（UI 上限 50，滑块刻度不变）。
 *
 * 底层 Toolkit.blur() 硬性限制 radius 只能是 1-25（继承自 RenderScript
 * ScriptIntrinsicBlur 的限制），超过这个值会直接抛异常。做法是先把图片按比例
 * 缩小，再用 25 以内的固定半径模糊，最后放大回原尺寸——图片缩得越小，同样的
 * 模糊半径在放大回来后视觉上就越糊。
 *
 * "缩小比例"和 UI 上的 radius 不是简单的线性关系（分段曲线）：
 * - [0, BLUR_STEEP_START] 区间：跟以前一样的渐进曲线（scaleFactor = 25/radius），
 *   保持原来已经调好的"越拉越糊"的手感不变。
 * - (BLUR_STEEP_START, MAX_BLUR_RADIUS] 这最后一小段：换一条陡峭得多的曲线，
 *   scaleFactor 在几步之内就冲到 [BLUR_STEEP_MIN_SCALE]（缩到只有几像素再放大），
 *   效果上就是"纯色块糊成一片"，不再能看出任何原图内容。
 * 两段在 BLUR_STEEP_START 处的 scaleFactor 是连续的，不会跳变；
 * 陡峭段内部因为曲线很陡，肉眼会感觉"最后一点点突然从有轮廓糊成纯色块"，
 * 这正是想要的效果。
 */
const val MAX_BLUR_RADIUS = 50
private const val TOOLKIT_MAX_BLUR_RADIUS = 25
private const val BLUR_STEEP_START = 40
private const val BLUR_STEEP_MIN_SCALE = 0.02f

fun Bitmap.blur(@IntRange(from = 0, to = MAX_BLUR_RADIUS.toLong()) radius: Int): Bitmap {
    val rawClamped = radius.coerceIn(0, MAX_BLUR_RADIUS)
    if (rawClamped <= 0) return this
    // 50 这一档单独处理：效果和 49 完全一样（不再有额外的那一小步“陡增”），
    // UI 滑块本身还是能拖到 50，只是这里把它当 49 算。
    val clamped = if (rawClamped >= MAX_BLUR_RADIUS) MAX_BLUR_RADIUS - 1 else rawClamped
    if (clamped <= TOOLKIT_MAX_BLUR_RADIUS) {
        return Toolkit.blur(this, clamped)
    }

    val scaleFactor = if (clamped <= BLUR_STEEP_START) {
        TOOLKIT_MAX_BLUR_RADIUS.toFloat() / clamped
    } else {
        // 陡峭段：从 BLUR_STEEP_START 处的值，用二次曲线快速下探到 BLUR_STEEP_MIN_SCALE。
        val scaleAtSteepStart = TOOLKIT_MAX_BLUR_RADIUS.toFloat() / BLUR_STEEP_START
        val progress =
            (clamped - BLUR_STEEP_START).toFloat() / (MAX_BLUR_RADIUS - BLUR_STEEP_START)
        val eased = progress * progress
        scaleAtSteepStart + (BLUR_STEEP_MIN_SCALE - scaleAtSteepStart) * eased
    }
    val scaledWidth = (width * scaleFactor).toInt().coerceAtLeast(1)
    val scaledHeight = (height * scaleFactor).toInt().coerceAtLeast(1)

    val small = Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
    val blurredSmall = if (scaledWidth <= TOOLKIT_MAX_BLUR_RADIUS * 2 ||
        scaledHeight <= TOOLKIT_MAX_BLUR_RADIUS * 2
    ) {
        // 缩得太小时 Toolkit.blur 的固定半径可能比图片本身还大，直接跳过再模糊一次，
        // 缩小+放大本身已经足够糊成纯色块。
        small
    } else {
        Toolkit.blur(small, TOOLKIT_MAX_BLUR_RADIUS)
    }
    val result = Bitmap.createScaledBitmap(blurredSmall, width, height, true)

    if (small !== this && small !== blurredSmall) small.recycle()
    if (blurredSmall !== result && blurredSmall !== small) blurredSmall.recycle()

    return result
}

fun Bitmap.noise(@IntRange(1, 100) percent: Int): Bitmap {
    val pixels = IntArray(width * height)

    getPixels(pixels, 0, width, 0, 0, width, height)

    val random = Random()

    var index: Int

    var rgb: Int
    var randColor: Int

    for (y in 0 until height) {
        for (x in 0 until width) {
            if (random.nextInt(101) > percent / 4) {
                continue
            }
            index = y * width + x
            rgb = random.nextInt(255)
            randColor = Color.rgb(rgb, rgb, rgb)
            pixels[index] = pixels[index] or randColor
        }
    }
    val bmOut = Bitmap.createBitmap(width, height, config)
    bmOut.setPixels(pixels, 0, width, 0, 0, width, height)
    return bmOut
}

/**
 * brightness/contrast/saturation 输入范围是 -50~50，0 = 不变（居中）。
 * 以前是 0~100、50 = 不变，公式里各种"-50"就是那时候留下的居中换算，
 * 现在输入本身已经以 0 为中心，直接用原值即可，不再需要减 50。
 *
 * contrast 的 offset 公式之前多加了一个 "+0.5f"（`0.5f*(1-scale)+0.5f`），
 * 导致中灰基准点被顶偏，对比度一调整画面就整体发灰/偏亮，跟 Photoshop 的
 * "围绕中灰缩放"直觉对不上，改成标准公式 `0.5f*(1-scale)`。
 *
 * saturation 原来的换算 `(saturation/50)*0.3f+1` 只能落在 0.7~1.3 这个很窄
 * 的范围里，-50 都到不了纯黑白（setSaturation(0)）。改成线性映射到 0~2：
 * -50 → 0（纯黑白）、0 → 1（不变）、+50 → 2（更浓艳），两头都是有意义的
 * 极限值，跟 PS 里"饱和度拉到最低=黑白"的直觉一致。
 */
fun Bitmap.effect(
    brightness: Int,
    contrast: Int,
    saturation: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(this, 0, 0, width, height)

    val colorMatrix = ColorMatrix()

    val lum = brightness * 2 * 0.3f * 255 * 0.01f
    val brightnessArray = floatArrayOf(
        1f, 0f, 0f, 0f, lum,
        0f, 1f, 0f, 0f, lum,
        0f, 0f, 1f, 0f, lum,
        0f, 0f, 0f, 1f, 0f
    )
    colorMatrix.set(brightnessArray)

    val scale = (contrast + 100) / 100f
    val offset = 0.5f * (1 - scale) * 255f
    val contrastArray =
        floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )
    colorMatrix.postConcat(ColorMatrix(contrastArray))
    val saturationMatrix = ColorMatrix()
    saturationMatrix.setSaturation((saturation.toFloat() + 50) / 50f)
    colorMatrix.postConcat(saturationMatrix)

    val colorFilter = ColorMatrixColorFilter(colorMatrix)

    val paint = Paint().apply {
        this.colorFilter = colorFilter
    }

    val canvas = Canvas(bitmap)
    canvas.drawBitmap(this, 0f, 0f, paint)

    return bitmap
}

fun Bitmap.setAlpha(alpha: Int): Bitmap {
    val bm = Bitmap.createBitmap(width, height, config)
    val canvas = Canvas(bm)
    val paint = Paint()
    paint.alpha = alpha
    canvas.drawBitmap(this, 0f, 0f, paint)

    return bm
}