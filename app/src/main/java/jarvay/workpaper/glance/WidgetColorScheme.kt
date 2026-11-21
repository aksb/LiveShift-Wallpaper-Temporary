package jarvay.workpaper.glance

import androidx.compose.ui.graphics.Color
import jarvay.workpaper.R

/**
 * 小组件的配色方案。
 *
 * 不再有"药丸底"这个概念——按钮就是纯文字（"◁" "▷"），
 * 只有小组件整体的背景色 + 文字颜色两个维度。
 *
 * containerColor：整个小组件背景色（可为完全透明）
 * textColor：按钮文字（"◁" "▷"）颜色
 * labelRes：设置界面下拉菜单里显示的文案
 */
enum class WidgetColorScheme(
    val value: String,
    val containerColor: Color,
    val textColor: Color,
    val labelRes: Int,
) {
    FULL_TRANSPARENT_WHITE_TEXT(
        value = "fullTransparentWhiteText",
        containerColor = Color.Transparent,
        textColor = Color.White,
        labelRes = R.string.widget_color_scheme_full_transparent_white_text,
    ),
    FULL_TRANSPARENT_BLACK_TEXT(
        value = "fullTransparentBlackText",
        containerColor = Color.Transparent,
        textColor = Color.Black,
        labelRes = R.string.widget_color_scheme_full_transparent_black_text,
    ),
    TRANSLUCENT_BLACK_WHITE_TEXT(
        value = "translucentBlackWhiteText",
        containerColor = Color(0, 0, 0, (255 * 0.45).toInt()),
        textColor = Color.White,
        labelRes = R.string.widget_color_scheme_translucent_black_white_text,
    ),
    TRANSLUCENT_WHITE_BLACK_TEXT(
        value = "translucentWhiteBlackText",
        containerColor = Color(255, 255, 255, (255 * 0.45).toInt()),
        textColor = Color.Black,
        labelRes = R.string.widget_color_scheme_translucent_white_black_text,
    ),
    OPAQUE_BLACK_WHITE_TEXT(
        value = "opaqueBlackWhiteText",
        containerColor = Color.Black,
        textColor = Color.White,
        labelRes = R.string.widget_color_scheme_opaque_black_white_text,
    ),
    OPAQUE_WHITE_BLACK_TEXT(
        value = "opaqueWhiteBlackText",
        containerColor = Color.White,
        textColor = Color.Black,
        labelRes = R.string.widget_color_scheme_opaque_white_black_text,
    );

    companion object {
        val DEFAULT = FULL_TRANSPARENT_WHITE_TEXT

        fun fromValue(value: String): WidgetColorScheme {
            return entries.find { it.value == value } ?: DEFAULT
        }
    }
}
