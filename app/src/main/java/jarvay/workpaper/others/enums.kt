package jarvay.workpaper.others

import androidx.annotation.StringRes
import jarvay.workpaper.R

enum class FormMode {
    CREATE,
    UPDATE
}

enum class GestureEvent(@StringRes val labelResId: Int) {
    NONE(R.string.gesture_event_none),
    LOCK_SCREEN(R.string.gesture_event_lock_screen),
    CHANGE_WALLPAPER_PREV(R.string.gesture_event_change_wallpaper_prev),
    CHANGE_WALLPAPER_NEXT(R.string.gesture_event_change_wallpaper_next),
    TOGGLE_EFFECT(R.string.gesture_event_toggle_effect),
}

/**
 * 壁纸缩放模式，全局设置，图片和视频共用同一个值：
 * - BY_HEIGHT / BY_WIDTH：按高度或宽度等比缩放，铺满该方向，另一方向裁剪或留黑边
 * - FIT：完整显示整张图/整段视频，不裁剪，可能有黑边
 * - FIXED_PERCENTAGE：在"铺满裁剪"（等价于旧默认效果）基础上乘一个用户自定义的百分比，
 *   100 = 和旧默认效果一致，大于 100 更多裁剪/放大，小于 100 可能露出黑边
 */
enum class WallpaperScaleType(@StringRes val labelResId: Int) {
    BY_HEIGHT(R.string.scale_type_by_height),
    BY_WIDTH(R.string.scale_type_by_width),
    FIT(R.string.scale_type_fit),
    FIXED_PERCENTAGE(R.string.scale_type_fixed_percentage),
}

/**
 * 阅读器模式"跟随上一张"（readerCarryOverTransform）开启时，横向位置（panX）
 * 具体怎么带到下一张图。缩放（readerScale）不管选哪个都是原样带过去，只有
 * 位置受这个影响：
 * - FOLLOW（默认）：位置也原样带过去，跟以前唯一的行为一样。
 * - LEFT：图片这个缩放下如果比屏幕宽，位置改成"图片左边缘贴屏幕左边缘"；
 *   没超出屏幕宽度的话直接归零居中（漫画阅读器常见的"从左边开始看"）。
 * - RIGHT：同上，但贴右边缘（"从右边开始看"，适合从右往左阅读的漫画）。
 */
enum class ReaderPanXMode(@StringRes val labelResId: Int) {
    FOLLOW(R.string.reader_pan_x_mode_follow),
    CENTER(R.string.reader_pan_x_mode_center),
    LEFT(R.string.reader_pan_x_mode_left),
    RIGHT(R.string.reader_pan_x_mode_right),
}

/** 跟 [ReaderPanXMode] 同理，管的是纵向位置（panY），贴上/贴下边缘。 */
enum class ReaderPanYMode(@StringRes val labelResId: Int) {
    FOLLOW(R.string.reader_pan_y_mode_follow),
    CENTER(R.string.reader_pan_y_mode_center),
    TOP(R.string.reader_pan_y_mode_top),
    BOTTOM(R.string.reader_pan_y_mode_bottom),
}

/**
 * 阅读器模式下，一张图在当前缩放下*没有*超出屏幕高度时（比如漫画原图
 * 本来就没有屏幕高，或者缩小到没超屏），怎么摆放它的纵向位置：
 * - CENTER（默认，等于以前唯一的行为）：居中，上下留白对称。
 * - TOP：贴屏幕上边缘，留白全部留在下方（比如用来避开屏幕底部的 dock 栏）。
 * - BOTTOM：贴屏幕下边缘，留白全部留在上方。
 * 跟 [ReaderPanYMode]（"跟随上一张"下面那个贴边设置，管的是*超屏*之后的
 * 贴边方式）是完全独立的两码事：这个设置管"没超屏"的情况，不要求"跟随
 * 上一张"开着才生效，任何时候只要阅读器模式开着就生效。
 */
enum class ReaderUndersizeVAlign(@StringRes val labelResId: Int) {
    CENTER(R.string.reader_undersize_valign_center),
    TOP(R.string.reader_undersize_valign_top),
    BOTTOM(R.string.reader_undersize_valign_bottom),
    // 不自动摆放，原样保持当前位置（同一张图内部翻页、换到下一张图、双击
    // 复位都不会去改这个轴向的位置）；双击复位比较特殊，见调用方注释。
    KEEP(R.string.reader_undersize_valign_keep),
}

/**
 * 阅读器模式"分区翻页"用的阅读方向，跟 [ReaderPanXMode]/[ReaderPanYMode]
 * （"跟随上一张"那个开关下面的贴边设置）完全独立，互不影响：
 * - LEFT_TO_RIGHT（默认）：图片比屏幕大的时候，点"下一张"先在当前这张图里
 *   从左到右、从上到下按屏幕大小分区翻页，翻到最后一个分区了才真的切下一张
 *   图片（新图片会从左上角开始）；"上一张"完全反过来。
 * - RIGHT_TO_LEFT：同上，但同一行内从右到左翻（从右往左阅读的漫画常见），
 *   行的顺序（从上到下）不变。
 * 图片如果本来就没超出屏幕（缩放=1），翻页会直接退化成字面意思的"下一张"，
 * 不受这个设置影响。
 */
enum class ReaderReadingDirection(@StringRes val labelResId: Int) {
    LEFT_TO_RIGHT(R.string.reader_reading_direction_ltr),
    RIGHT_TO_LEFT(R.string.reader_reading_direction_rtl),
}