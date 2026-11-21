/*
 * Copyright 2019 Alynx Zhou
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jarvay.workpaper.wallpaper

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import jarvay.workpaper.others.WallpaperScaleType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GLVideoWallpaperRenderer : GLWallpaperRenderer(), GLSurfaceView.Renderer {
    private val vertices: FloatBuffer
    private val texCoords: FloatBuffer
    private val indices: IntBuffer
    private val textures: IntArray
    private val buffers: IntArray
    private val mvp: FloatArray
    private var mvpLocation = 0
    private var positionLocation = 0
    private var texCoordLocation = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoRotation = 0
    private var xOffset = 0f
    private var yOffset = 0f
    private var maxXOffset = 0f
    private var maxYOffset = 0f
    private var scaleType = WallpaperScaleType.FIXED_PERCENTAGE
    private var fixedPercent = 100

    // Fix bug like https://stackoverflow.com/questions/14185661/surfacetexture-onframeavailablelistener-stops-being-called
    private var updatedFrame: Long = 0
    private var renderedFrame: Long = 0

    init {
        val vertexArray = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, 1.0f,
            1.0f, -1.0f,
            1.0f, 1.0f
        )
        vertices = ByteBuffer.allocateDirect(
            vertexArray.size * BYTES_PER_FLOAT
        ).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertices.put(vertexArray).position(0)
        val texCoordArray = floatArrayOf( // u, v
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 1.0f,
            1.0f, 0.0f
        )
        texCoords = ByteBuffer.allocateDirect(
            texCoordArray.size * BYTES_PER_FLOAT
        ).order(ByteOrder.nativeOrder()).asFloatBuffer()
        texCoords.put(texCoordArray).position(0)
        val indexArray = intArrayOf(
            0, 1, 2,
            3, 2, 1
        )
        indices = ByteBuffer.allocateDirect(
            indexArray.size * BYTES_PER_INT
        ).order(ByteOrder.nativeOrder()).asIntBuffer()
        indices.put(indexArray).position(0)
        buffers = IntArray(3)
        textures = IntArray(1)
        mvp = floatArrayOf(
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        )
    }

    override fun onSurfaceCreated(
        gl10: GL10,
        eglConfig: EGLConfig
    ) {
        // No depth test for 2D video.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glGenTextures(textures.size, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        initProgram()
        mvpLocation = GLES20.glGetUniformLocation(program, "mvp")
        // Locations are NOT set in shader sources.
        positionLocation = GLES20.glGetAttribLocation(program, "in_position")
        texCoordLocation = GLES20.glGetAttribLocation(program, "in_tex_coord")

        GLES20.glGenBuffers(buffers.size, buffers, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, vertices.capacity() * BYTES_PER_FLOAT,
            vertices, GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, texCoords.capacity() * BYTES_PER_FLOAT,
            texCoords, GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.capacity() * BYTES_PER_INT,
            indices, GLES20.GL_STATIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
    }

    override fun onSurfaceChanged(
        gl10: GL10, width: Int, height: Int
    ) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(
        gl10: GL10
    ) {
        if (surfaceTexture == null) {
            return
        }
        if (renderedFrame < updatedFrame) {
            try {
                surfaceTexture!!.updateTexImage()
                ++renderedFrame
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Utils.debug(
            //     TAG, "renderedFrame: " + renderedFrame + " updatedFrame: " + updatedFrame
            // );
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpLocation, 1, false, mvp, 0)
        // No vertex array in OpenGL ES 2.
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, 0
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[1])
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(
            texCoordLocation, 2, GLES20.GL_FLOAT, false, 2 * BYTES_PER_FLOAT, 0
        )
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, buffers[2])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_INT, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(texCoordLocation)
        GLES20.glDisableVertexAttribArray(positionLocation)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glUseProgram(0)
    }

    fun setSourcePlayer(player: MediaPlayer) {
        // Re-create SurfaceTexture when getting a new player.
        // Because maybe a new video is loaded.
        createSurfaceTexture()
        player.setSurface(Surface(surfaceTexture))
    }

    fun setScreenSize(width: Int, height: Int) {
        if (screenWidth != width || screenHeight != height) {
            screenWidth = width
            screenHeight = height
//            debug(TAG, String.format(
//                Locale.US, "Set screen size to %dx%d", screenWidth, screenHeight
//            ))
            maxXOffset =
                (1.0f - screenWidth.toFloat() / screenHeight / (videoWidth.toFloat() / videoHeight)) / 2
            maxYOffset =
                (1.0f - screenHeight.toFloat() / screenWidth / (videoHeight.toFloat() / videoWidth)) / 2
            updateMatrix()
        }
    }

    fun setVideoSizeAndRotation(width: Int, height: Int, rotation: Int) {
        // MediaMetadataRetriever always give us raw width and height and won't rotate them.
        // So we rotate them by ourselves.
        var newWidth = width
        var newHeight = height
        if (rotation % 180 != 0) {
            val swap = newWidth
            newWidth = newHeight
            newHeight = swap
        }
        if (videoWidth != newWidth || videoHeight != newHeight || videoRotation != rotation) {
            videoWidth = newWidth
            videoHeight = newHeight
            videoRotation = rotation
//            debug(TAG, String.format(
//                Locale.US, "Set video size to %dx%d", videoWidth, videoHeight
//            ))
//            debug(TAG, String.format(
//                Locale.US, "Set video rotation to %d", videoRotation
//            ))
            maxXOffset =
                (1.0f - screenWidth.toFloat() / screenHeight / (videoWidth.toFloat() / videoHeight)) / 2
            maxYOffset =
                (1.0f - screenHeight.toFloat() / screenWidth / (videoHeight.toFloat() / videoWidth)) / 2
            updateMatrix()
        }
    }

    fun setOffset(xOffset: Float, yOffset: Float) {
        var newXOffset = xOffset
        var newYOffset = yOffset
        if (newXOffset > maxXOffset) {
            newXOffset = maxXOffset
        }
        if (newXOffset < -maxXOffset) {
            newXOffset = -maxXOffset
        }
        if (newYOffset > maxYOffset) {
            newYOffset = maxYOffset
        }
        if (newYOffset < -maxXOffset) {
            newYOffset = -maxYOffset
        }
        if (this.xOffset != newXOffset || this.yOffset != newYOffset) {
            this.xOffset = newXOffset
            this.yOffset = newYOffset
//            debug(TAG, String.format(
//                Locale.US, "Set offset to %fx%f", this.xOffset, this.yOffset
//            ))
            updateMatrix()
        }
    }

    private fun createSurfaceTexture() {
        if (surfaceTexture != null) {
            surfaceTexture!!.release()
            surfaceTexture = null
        }
        updatedFrame = 0
        renderedFrame = 0
        surfaceTexture = SurfaceTexture(textures[0])
        surfaceTexture!!.setDefaultBufferSize(videoWidth, videoHeight)
        surfaceTexture!!.setOnFrameAvailableListener { ++updatedFrame }
    }

    /**
     * 统一支持四种缩放模式（跟图片那边的 WallpaperScaleType 是同一套设置）。
     * r = 视频宽高比 / 屏幕宽高比：
     * - BY_HEIGHT：Y 轴永远钉死在 1（正好占满屏幕高度），X 轴按 r 缩放
     *   （r>1 会裁剪左右，r<1 会露出左右黑边）。
     * - BY_WIDTH：X 轴永远钉死在 1，Y 轴按 1/r 缩放，同理。
     * - FIT：完整显示，不裁剪，可能露出黑边——跟原来的"铺满裁剪"正好是
     *   同一组分支条件，只是缩放的轴换成另一边。
     * - FIXED_PERCENTAGE：先算出"铺满裁剪"（相当于以前唯一的默认效果）
     *   这条基准的缩放，再整体乘上用户设置的百分比。
     */
    private fun updateMatrix() {
        // Players are buggy and unclear, so we do crop by ourselves.
        // Start with an identify matrix.
        for (i in 0..15) {
            mvp[i] = 0.0f
        }
        mvp[15] = 1.0f
        mvp[10] = mvp[15]
        mvp[5] = mvp[10]
        mvp[0] = mvp[5]

        if (videoWidth <= 0 || videoHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) return

        val videoRatio = videoWidth.toFloat() / videoHeight
        val screenRatio = screenWidth.toFloat() / screenHeight
        val r = videoRatio / screenRatio

        var scaleX: Float
        var scaleY: Float
        when (scaleType) {
            WallpaperScaleType.BY_HEIGHT -> {
                scaleX = r
                scaleY = 1f
            }

            WallpaperScaleType.BY_WIDTH -> {
                scaleX = 1f
                scaleY = 1f / r
            }

            WallpaperScaleType.FIT -> {
                if (r >= 1f) {
                    scaleX = 1f
                    scaleY = 1f / r
                } else {
                    scaleX = r
                    scaleY = 1f
                }
            }

            WallpaperScaleType.FIXED_PERCENTAGE -> {
                if (r >= 1f) {
                    scaleX = r
                    scaleY = 1f
                } else {
                    scaleX = 1f
                    scaleY = 1f / r
                }
                val percentFactor = fixedPercent.coerceIn(10, 300) / 100f
                scaleX *= percentFactor
                scaleY *= percentFactor
            }
        }

        // OpenGL model matrix: scaling, rotating, translating.
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f)
        // Some video recorder save video frames in direction differs from recoring,
        // and add a rotation metadata. Need to detect and rotate them.
        if (videoRotation % 360 != 0) {
            Matrix.rotateM(mvp, 0, -videoRotation.toFloat(), 0f, 0f, 1f)
        }
        // 只有真的被放大裁剪的那根轴才有"多余画面"可以滚动，FIT 模式两边都
        // 没裁剪时就不需要（也不应该）响应桌面翻页偏移。
        if (scaleX > 1.001f) {
            Matrix.translateM(mvp, 0, xOffset, 0f, 0f)
        } else if (scaleY > 1.001f) {
            Matrix.translateM(mvp, 0, 0f, yOffset, 0f)
        }
        // This is a 2D center crop, so we only need model matrix, no view and projection.
    }

    /** 全局缩放模式设置变化时调用（跟图片共用同一个设置）。 */
    fun setScaleType(type: WallpaperScaleType, percent: Int) {
        if (scaleType != type || fixedPercent != percent) {
            scaleType = type
            fixedPercent = percent
            updateMatrix()
        }
    }

    override fun getFragmentShaderCode(): String {
        return FRAGMENT_SHADER_CODE
    }

    override fun getVertexShaderCode(): String {
        return VERTEX_SHADER_CODE
    }

    companion object {
        private const val BYTES_PER_FLOAT = 4
        private const val BYTES_PER_INT = 4

        private const val VERTEX_SHADER_CODE: String =
            "#version 100\n" +
                    "attribute vec2 in_position;" +
                    "attribute vec2 in_tex_coord;" +
                    "uniform mat4 mvp;" +
                    "varying vec2 tex_coord;" +
                    "void main() {" +
                    "  gl_Position = mvp * vec4(in_position, 1.0, 1.0);" +
                    "  tex_coord = in_tex_coord;" +
                    "}"

        private const val FRAGMENT_SHADER_CODE: String =
            "#version 100\n" +
                    "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;" +
                    "uniform samplerExternalOES frame;" +
                    "varying vec2 tex_coord;" +
                    "void main() {" +
                    "  gl_FragColor = texture2D(frame, tex_coord);" +
                    "}"

    }
}
