package jarvay.workpaper.wallpaper

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

// 阅读器模式缩放的安全数值兜底：只是防止手指乱滑出现极端值（比如缩放系数
// 变成 0）导致除零之类的数值错误，不是"必须盖满屏幕"那种体验上的限制，
// 正常操作根本碰不到这两个边界。
private const val READER_SCALE_HARD_MIN = 0.05f
private const val READER_SCALE_HARD_MAX = 10f

class GLImageWallpaperRenderer : GLSurfaceView.Renderer, GLWallpaperRenderer() {
    private val textureHandles = IntArray(2)
    private var currentTextureIndex = 0
    private var transitionAlpha = 0f
    private var shouldSwapAfterTransition = false

    private val vertices = floatArrayOf(
        -1.0f, 1.0f, 0.0f, 0.0f, 0.0f,
        -1.0f, -1.0f, 0.0f, 0.0f, 1.0f,
        1.0f, -1.0f, 0.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 0.0f, 1.0f, 0.0f,
    )

    private val vertexBuffer = ByteBuffer
        .allocateDirect(vertices.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(vertices)
            position(0)
        }

    private val pendingBitmaps = arrayOfNulls<Bitmap?>(2)
    private val textureUploaded = booleanArrayOf(false, false)

    private var textureWidthRatio = 1.0f
    private var currentOffset = 0f
    private var screenWidth = 0
    private var screenHeight = 0

    // 阅读器模式：双指缩放(readerScale，不设"必须盖满屏幕"的下限，只有一个
    // 防止数值异常的安全兜底 0.05~10 倍)/拖动(readerPanX、readerPanY)。开启时
    // 完全接管顶点位置+纹理坐标，忽略上面那套"桌面横向翻页滚动"的
    // textureWidthRatio/currentOffset 逻辑（两者本来就是互斥的功能，不会同时用）。
    //
    // 统一用一套模型，不再区分"放大裁剪"和"缩小平移"两个分支：纹理坐标永远
    // 用完整的 0~1（不裁剪），矩形顶点位置按 readerScale/readerPanX/readerPanY
    // 摆放——矩形半宽/半高是 readerScale*baseHalfWidth/baseHalfHeight（不再假设
    // 横竖对称，见 baseHalfWidth/baseHalfHeight 字段注释），中心点是
    // (readerPanX, readerPanY)（NDC 空间，-1..1 对应整个屏幕）。矩形比屏幕大（放大）或比屏幕小（缩小）
    // 都是同一套摆放逻辑，超出屏幕视口的部分由 OpenGL 自动裁剪掉，超出的地方
    // 露出 GL 清屏色（这个 App 里已经设成纯黑）。
    // 这样纹理坐标永远不会跑到 0~1 以外，从根上避免了"贴图坐标越界采样在个别
    // GPU 驱动上直接崩溃"这个问题（旧版本缩到比屏幕小就闪退，原因就在于旧写法
    // 有的分支会去裁剪贴图坐标，缩到某个点必然会越界）；同时因为矩形位置可以
    // 自由摆到视口以外，"拖到图片完全移出屏幕才停"这件事也是这套模型自带的，
    // 不需要额外处理——具体允许拖多远由调用方（LiveWallpaperService）夹在
    // ±(readerScale + 1) 以内，这里只做数值安全兜底。
    private var readerModeEnabled = false
    private var readerScale = 1f
    private var readerPanX = 0f
    private var readerPanY = 0f

    // 当前贴图（已经按"适合屏幕"缩放、不再合成黑边画布，见 LiveWallpaperService
    // 的 loadBitmap）横/竖两个方向各自占屏幕宽/高的比例（<=1）。图片长宽比跟
    // 屏幕长宽比不一致时，撑满的那个方向是 1，另一个方向会小于 1——矩形的半宽/
    // 半高不能再假设相等，要分别乘上这两个值，见 updateTextureCoordinates。
    private var baseHalfWidth = 1f
    private var baseHalfHeight = 1f

    override fun onSurfaceCreated(p0: GL10?, p1: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        initProgram()
        GLES20.glGenTextures(2, textureHandles, 0)

        for (i in 0 until 2) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandles[i])
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
        }
    }

    override fun onSurfaceChanged(gl10: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateTextureCoordinates()
    }

    override fun onDrawFrame(p0: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        for (i in 0 until 2) {
            if (pendingBitmaps[i] != null && !textureUploaded[i]) {
                uploadTexture(i, pendingBitmaps[i]!!)
                pendingBitmaps[i] = null
                textureUploaded[i] = true
            }
        }

        GLES20.glUseProgram(program)

        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, vertexBuffer)
        GLES20.glEnableVertexAttribArray(0)

        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, vertexBuffer)
        GLES20.glEnableVertexAttribArray(1)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandles[currentTextureIndex])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "texture1"), 0)

        if (transitionAlpha > 0f) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(
                GLES20.GL_TEXTURE_2D,
                textureHandles[(currentTextureIndex + 1) % 2]
            )
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "texture2"), 1)
        }

        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "alpha"), transitionAlpha)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 4)

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)

        if (transitionAlpha >= 1f && shouldSwapAfterTransition) {
            swapTextures()
            transitionAlpha = 0f
            shouldSwapAfterTransition = false
        }
    }

    fun setBitmap(bitmap: Bitmap, isNext: Boolean = false) {
        val textureIndex = if (isNext) (currentTextureIndex + 1) % 2 else currentTextureIndex

        pendingBitmaps[textureIndex]?.recycle()
        pendingBitmaps[textureIndex] = bitmap
        textureUploaded[textureIndex] = false

        val scaleX = 1.0f
        val scaleY = 1.0f
        val verticesData = floatArrayOf(
            -scaleX, scaleY, 0.0f, 0.0f, 0.0f,
            -scaleX, -scaleY, 0.0f, 0.0f, 1.0f,
            scaleX, -scaleY, 0.0f, 1.0f, 1.0f,
            scaleX, scaleY, 0.0f, 1.0f, 0.0f,
        )
        vertexBuffer.clear()
        vertexBuffer.put(verticesData).position(0)

        val ratio = bitmap.width.toFloat() / screenWidth
        setTextureRatio(ratio)

        // 阅读器模式用：记下这张贴图相对屏幕的真实横/竖占比，见字段注释。
        if (screenWidth > 0 && screenHeight > 0) {
            baseHalfWidth = bitmap.width.toFloat() / screenWidth
            baseHalfHeight = bitmap.height.toFloat() / screenHeight
        }
    }

    fun setTextureRatio(widthRatio: Float) {
        textureWidthRatio = widthRatio
        updateTextureCoordinates()
    }

    fun updateOffset(offset: Float) {
        currentOffset = offset
        updateTextureCoordinates()
    }

    fun setReaderMode(enabled: Boolean) {
        readerModeEnabled = enabled
        updateTextureCoordinates()
    }

    /**
     * scale 不设"必须 >=1"的限制，只做一个防止数值异常的安全兜底。
     * panX/panY 是矩形中心在 NDC 空间的偏移量，这里只做一个宽松的安全钳制
     * （±(scale+1) 是"图片矩形与屏幕刚好完全分离"的临界点，业务层面上具体
     * 允许拖多远由调用方——LiveWallpaperService 的触摸/缩放逻辑——决定，
     * 这里的钳制只是兜底，防止传进来奇怪的数值导致顶点坐标异常）。
     */
    fun setReaderTransform(scale: Float, panX: Float, panY: Float) {
        readerScale = scale.coerceIn(READER_SCALE_HARD_MIN, READER_SCALE_HARD_MAX)
        val maxPan = readerScale + 1f
        readerPanX = panX.coerceIn(-maxPan, maxPan)
        readerPanY = panY.coerceIn(-maxPan, maxPan)
        updateTextureCoordinates()
    }

    private fun updateTextureCoordinates() {
        // 默认：矩形铺满整个屏幕（-1..1），只有阅读器模式下才会去改动顶点
        // 位置本身，其余分支都保持这个默认值不变。
        var posLeft = -1f
        var posRight = 1f
        var posTop = 1f
        var posBottom = -1f

        val u0: Float
        val u1: Float
        val v0: Float
        val v1: Float

        if (readerModeEnabled) {
            // 纹理坐标永远是完整的 0~1，矩形按 readerScale/readerPanX/readerPanY
            // 摆放（矩形半宽/半高 = readerScale，中心 = (readerPanX, readerPanY)），
            // 不管矩形比屏幕大还是小，超出视口的部分都交给 GL 自动裁剪。
            u0 = 0f
            u1 = 1f
            v0 = 0f
            v1 = 1f
            val halfX = readerScale * baseHalfWidth
            val halfY = readerScale * baseHalfHeight
            posLeft = readerPanX - halfX
            posRight = readerPanX + halfX
            posTop = readerPanY + halfY
            posBottom = readerPanY - halfY
        } else if (textureWidthRatio > 1.0f) {
            val scrollableWidth = textureWidthRatio - 1.0f
            u0 = currentOffset * scrollableWidth / textureWidthRatio
            u1 = u0 + 1.0f / textureWidthRatio
            v0 = 0f
            v1 = 1f
        } else {
            u0 = 0f
            u1 = 1f
            v0 = 0f
            v1 = 1f
        }

        vertices[0] = posLeft
        vertices[1] = posTop
        vertices[3] = u0
        vertices[4] = v0

        vertices[5] = posLeft
        vertices[6] = posBottom
        vertices[8] = u0
        vertices[9] = v1

        vertices[10] = posRight
        vertices[11] = posBottom
        vertices[13] = u1
        vertices[14] = v1

        vertices[15] = posRight
        vertices[16] = posTop
        vertices[18] = u1
        vertices[19] = v0

        vertexBuffer.clear()
        vertexBuffer.put(vertices).position(0)
    }

    private fun uploadTexture(index: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandles[index])
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }


    fun startTransition() {
        transitionAlpha = 0f
        shouldSwapAfterTransition = true
    }

    fun updateTransitionAlpha(alpha: Float) {
        transitionAlpha = alpha.coerceIn(0f, 1f)
    }

    fun onDestroy() {
        for (i in 0 until 2) {
            pendingBitmaps[i]?.recycle()
            pendingBitmaps[i] = null
        }
        GLES20.glDeleteTextures(2, textureHandles, 0)
    }

    private fun swapTextures() {
        currentTextureIndex = (currentTextureIndex + 1) % 2
    }

    override fun getFragmentShaderCode(): String {
        return FRAGMENT_SHADER_CODE
    }

    override fun getVertexShaderCode(): String {
        return VERTEX_SHADER_CODE
    }

    companion object {
        private const val VERTEX_SHADER_CODE: String =
            "attribute vec4 vPosition;" +
                    "attribute vec2 vTexCoord;" +
                    "varying vec2 outTexCoord;" +
                    "void main() {" +
                    "  gl_Position = vPosition;" +
                    "  outTexCoord = vTexCoord;" +
                    "}"

        private const val FRAGMENT_SHADER_CODE: String =
            "precision mediump float;" +
                    "uniform sampler2D texture1;" +
                    "uniform sampler2D texture2;" +
                    "uniform float alpha;" +
                    "varying vec2 outTexCoord;" +
                    "void main() {" +
                    "  vec4 color1 = texture2D(texture1, outTexCoord);" +
                    "  vec4 color2 = texture2D(texture2, outTexCoord);" +
                    "  gl_FragColor = mix(color1, color2, alpha);" +
                    "}"
    }
}