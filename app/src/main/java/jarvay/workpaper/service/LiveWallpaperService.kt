package jarvay.workpaper.service

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory.Options
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.service.wallpaper.WallpaperService
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.blankj.utilcode.util.LogUtils
import dagger.hilt.android.AndroidEntryPoint
import jarvay.workpaper.Workpaper
import jarvay.workpaper.data.preferences.DEFAULT_SETTINGS
import jarvay.workpaper.data.preferences.RunningPreferencesKeys
import jarvay.workpaper.data.preferences.SettingsPreferences
import jarvay.workpaper.data.wallpaper.WallpaperRepository
import jarvay.workpaper.data.wallpaper.WallpaperType
import jarvay.workpaper.others.GestureEvent
import jarvay.workpaper.others.LOG_TAG
import jarvay.workpaper.others.ReaderPanXMode
import jarvay.workpaper.others.ReaderPanYMode
import jarvay.workpaper.others.ReaderReadingDirection
import jarvay.workpaper.others.ReaderUndersizeVAlign
import jarvay.workpaper.others.WallpaperScaleType
import jarvay.workpaper.others.bitmapFromContentUri
import jarvay.workpaper.others.scaleForScreen
import jarvay.workpaper.receiver.WallpaperReceiver
import jarvay.workpaper.wallpaper.WallpaperRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt
import javax.inject.Inject

// 阅读器模式双指缩放的数值范围：不再要求"至少盖满屏幕"，用户可以缩到露出
// 黑边、也可以放大裁剪，这两个值只是一个宽松的安全兜底（防止缩成 0 之类的
// 极端值），不是产品意义上的"限制"。
private const val READER_SCALE_MIN = 0.05f
private const val READER_SCALE_MAX = 10f

@AndroidEntryPoint
class LiveWallpaperService : WallpaperService(), LifecycleOwner {
    companion object {
        // "恢复到默认大小和位置"的广播 action：App 内按钮和桌面小组件都发这个，
        // 由正在渲染的引擎实例（如果活着）动态注册接收并处理，跟"上一张/下一张"
        // 走的 WorkManager 任务队列完全是两套不同的通路——重置不需要真的切图，
        // 只需要告诉正在渲染的这个实例"把当前这张的缩放位置清零"。
        const val ACTION_RESET_READER_TRANSFORM =
            "jarvay.workpaper.ACTION_RESET_READER_TRANSFORM"

        // "分区翻页"用的广播：阅读器模式下，App 按钮/桌面小组件点的"上一张/
        // 下一张"不再直接发给 WallpaperReceiver 走真的切图流程，而是先发这个，
        // 让正在渲染的引擎自己判断——图片如果因为缩放超出了屏幕、还没翻到头，
        // 就只是挪一下 readerPanX/readerPanY（相当于翻到当前这张大图的
        // 下一个分区），不真的换图；只有翻到头了，引擎才会自己转发一条
        // WallpaperReceiver 认识的广播，走回原来"真的换下一张"的流程。
        const val ACTION_READER_STEP = "jarvay.workpaper.ACTION_READER_STEP"
        const val EXTRA_READER_STEP_DIRECTION = "direction"
        const val READER_STEP_NEXT = "next"
        const val READER_STEP_PREV = "prev"
    }

    @Inject
    lateinit var workpaper: Workpaper

    @Inject
    lateinit var wallpaperRepository: WallpaperRepository
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry


    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    @OptIn(UnstableApi::class)
    override fun onCreateEngine(): Engine {
        return LiveWallpaperEngine()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    @UnstableApi
    inner class LiveWallpaperEngine : Engine(), LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = engineLifecycleRegistry
        private val engineLifecycleRegistry = LifecycleRegistry(this)

        private var surfaceView: GLWallpaperSurfaceView? = null
        private var renderer: WallpaperRenderer? = null
        private val player: MediaPlayer = MediaPlayer()
        private var resetOnScreenOff = false
        private var doubleTapEvent: GestureEvent = GestureEvent.NONE
        private var isScreenOn = true
        private var bitmap: Bitmap? = null
        private var settings: SettingsPreferences = DEFAULT_SETTINGS

        // 这两个字段本来错误地挂在外层 Service 上，导致系统同时存在多个
        // Engine 实例时（比如部分厂商 ROM 会为"最近任务"多任务切换界面单独
        // 起一个预览 Engine 来渲染同一个动态壁纸），会互相覆盖：
        // - surfaceSize 被别的 Engine 的（可能小得多的）预览分辨率覆盖后，
        //   loadBitmap()/阅读器手势换算全部用错误的基准尺寸，表现为图片
        //   "莫名其妙变了大小/位置"；
        // - prevImageUri 被别的 Engine 覆盖后，persistReaderTransform() 落盘时
        //   用的 contentUri 对不上当前真正显示的这张图，写入要么进错行、要么
        //   干脆没写进去，下次重新加载读到的就是没调整过的默认值。
        // 现在改成每个 Engine 实例各自持有一份，互不影响。
        private var prevImageUri: String? = null
        private var surfaceSize = Size(0, 0)

        // 阅读器模式相关状态：readerScale 不再有"至少盖满屏幕"的下限，
        // 只受 READER_SCALE_MIN/MAX 这两个安全兜底约束。
        // readerPanX/readerPanY 是矩形中心在 NDC 空间的偏移量（-1..1 对应整个
        // 屏幕宽/高），允许的范围是 ±(readerScale + 1)——超过这个值，图片矩形
        // 就跟屏幕完全不相交了，所以到这就不再放宽，防止无限拖没了。
        private var readerScale = 1f
        private var readerPanX = 0f
        private var readerPanY = 0f

        // 当前这张图，按"适合屏幕"缩放（不裁剪、不合成黑边画布，见 loadBitmap）
        // 之后，横/竖两个方向各自占屏幕宽/高的比例（<=1，撑满的那个方向正好
        // 是 1）。readerScale 缩放和分区翻页的边缘/格数计算都要乘上这两个值
        // 才是"图片内容"真正的边界——两个方向可能不一样（图片长宽比跟屏幕
        // 长宽比不一致时，一个方向撑满、另一个方向会留白），不能再像以前一样
        // 假设横竖对称。默认 1f/1f（还没加载过图时，等价于旧行为）。
        private var readerBaseHalfWidth = 1f
        private var readerBaseHalfHeight = 1f
        private var isTwoFingerActive = false
        private var lastCentroidX = 0f
        private var lastCentroidY = 0f
        // "跟随上一张"模式下，引擎生命周期内只在第一次用到时读一次全局存档，
        // 之后都用内存里的当前值，避免每切一张图就读一次库。
        private var carryOverLoaded = false

        // "分区翻页"用：当这次真正的换图是因为翻页翻到头了才触发的，记一下
        // 新图片应该落在哪个分区（贴哪条边）——只有这种情况下才需要覆盖掉
        // 正常的 readerCarryOverTransform/每张图各自记忆 那两套逻辑算出来的
        // 位置；跳转到第几张、定时轮播、图片本来就没超出屏幕这些正常换图，
        // 这个字段永远是 null，不受影响。在 setImageBitmap() 里读取一次就
        // 立刻清空，不会影响下一次换图。
        private var pendingReaderLandingX: ReaderPanXMode? = null
        private var pendingReaderLandingY: ReaderPanYMode? = null

        // 记录"最近一次收到、但当时 renderer 还没创建出来、导致被跳过"的地址。
        // 典型场景：开机重启后，App 恢复上次壁纸的速度比系统把 Surface 准备好还快，
        // changeVideoSource()/setImageBitmap() 在 renderer 为 null 时会直接放弃，
        // 这个值一旦不为空，就说明"有一次应用被漏掉了"。等 renderer 真正创建出来
        // （initSurfaceView）时，会用这个值补一次，避免黑屏一直卡到下次真正切换
        // 壁纸（或者系统恰好重建了一次 Surface）才恢复正常。
        private var pendingVideoUri: Uri? = null
        private var pendingImageUri: Uri? = null

        private var currentBitmap: Bitmap? = null
        private var nextBitmap: Bitmap? = null

        init {
            setTouchEventsEnabled(true)

            MainScope().launch {
                workpaper.settingsPreferencesRepository.settingsPreferencesFlow.collect {
                    settings = it
                }
            }
        }

        private val screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null || context == null) return

                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    isScreenOn = true
                }
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    isScreenOn = false
                    if (player.isPlaying) {
                        player.pause()
                    }
                    if (resetOnScreenOff) {
                        player.seekTo(0);
                    }
                }
                if (intent.action == ACTION_RESET_READER_TRANSFORM) {
                    resetReaderTransform()
                }
                if (intent.action == ACTION_READER_STEP) {
                    val direction = intent.getStringExtra(EXTRA_READER_STEP_DIRECTION)
                    handleReaderStepRequest(context, direction == READER_STEP_NEXT)
                }
            }
        }

        private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val result = super.onDoubleTap(e)

                if (settings.readerMode && renderer?.wallpaperType == WallpaperType.IMAGE) {
                    // 阅读器模式下双击不再走下面那套桌面手势下拉框的逻辑（界面上
                    // 那个下拉框在阅读器模式开启时也会隐藏掉，见 HomeScreen），
                    // 是否复位缩放/位置改成可选（readerDoubleTapReset 开关，
                    // 默认 true 保持以前的行为），避免不小心双击误触把调好的
                    // 位置弄没了。
                    if (settings.readerDoubleTapReset) {
                        resetReaderTransform()
                    }
                    return result
                }

                when (doubleTapEvent) {
                    GestureEvent.NONE -> {

                    }

                    GestureEvent.CHANGE_WALLPAPER_PREV -> {
                        val intent =
                            Intent(this@LiveWallpaperService, WallpaperReceiver::class.java).apply {
                                putExtra(
                                    WallpaperReceiver.FLAG_DIRECTION,
                                    WallpaperReceiver.DIRECTION_PREV
                                )
                            }
                        sendBroadcast(intent)
                    }

                    GestureEvent.CHANGE_WALLPAPER_NEXT -> {
                        val intent =
                            Intent(this@LiveWallpaperService, WallpaperReceiver::class.java).apply {
                                putExtra(
                                    WallpaperReceiver.FLAG_DIRECTION,
                                    WallpaperReceiver.DIRECTION_NEXT
                                )
                            }
                        sendBroadcast(intent)
                    }

                    GestureEvent.LOCK_SCREEN -> {
                        val devicePolicyManager =
                            getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        devicePolicyManager.lockNow()
                    }

                    GestureEvent.TOGGLE_EFFECT -> {
                        // 只对图片壁纸生效：视频壁纸的滤镜走的是另一套 GL 渲染管线，
                        // 双击切换的价值也不大（视频本身就是动态画面），这里不处理。
                        if (renderer?.wallpaperType == WallpaperType.IMAGE) {
                            lifecycleScope.launch {
                                val running =
                                    workpaper.runningPreferencesRepository.runningPreferencesFlow.first()
                                workpaper.runningPreferencesRepository.update(
                                    RunningPreferencesKeys.STYLE_LOCKED_OFF,
                                    !running.styleLockedOff
                                )
                                // 用上一次展示的图片 uri 重新走一遍加载流程，让"有滤镜/无滤镜"
                                // 立刻生效，不用等下一次换图才看到效果。
                                prevImageUri?.let { uriString ->
                                    setImageBitmap(uriString.toUri())
                                }
                            }
                        }
                    }
                }

                return result
            }
        }
        private val gestureDetector = GestureDetector(this@LiveWallpaperService, gestureListener)

        private val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!settings.readerMode || renderer?.wallpaperType != WallpaperType.IMAGE) {
                    return false
                }
                readerScale = (readerScale * detector.scaleFactor)
                    .coerceIn(READER_SCALE_MIN, READER_SCALE_MAX)
                // 缩放变化后，"允许拖到完全移出屏幕"的边界（readerScale*基准半宽/
                // 半高 + 1）也跟着变了，顺手把当前 pan 重新夹一下，避免缩小之后
                // pan 值停留在一个已经超出新边界很多的位置。横竖两个方向的边界
                // 分开算（见 readerBaseHalfWidth/Height 的注释）。
                val maxPanX = readerScale * readerBaseHalfWidth + 1f
                val maxPanY = readerScale * readerBaseHalfHeight + 1f
                readerPanX = readerPanX.coerceIn(-maxPanX, maxPanX)
                readerPanY = readerPanY.coerceIn(-maxPanY, maxPanY)
                applyReaderTransform()
                return true
            }
        }
        private val scaleGestureDetector =
            ScaleGestureDetector(this@LiveWallpaperService, scaleGestureListener)

        init {
            player.apply {
                setVolume(0f, 0f)
                isLooping = true
            }
            lifecycleScope.launch {
                workpaper.settingsPreferencesRepository.settingsPreferencesFlow.collect {
                    resetOnScreenOff = it.videoResetProgressOnScreenOff
                    doubleTapEvent = try {
                        GestureEvent.valueOf(it.doubleTapEvent)
                    } catch (e: Exception) {
                        GestureEvent.NONE
                    }

                    val scaleType = try {
                        WallpaperScaleType.valueOf(it.scaleType)
                    } catch (e: Exception) {
                        WallpaperScaleType.FIXED_PERCENTAGE
                    }
                    renderer?.videoRenderer?.setScaleType(scaleType, it.scalePercent)
                    val readerModeEnabled = it.readerMode
                    // 关掉阅读器模式不清空 readerScale/readerPanX/readerPanY——
                    // 关闭期间这几个值反正也不参与渲染（setReaderMode(false) 之后
                    // GL 那边直接走非阅读器分支，不看这几个字段），留着不清空是
                    // 为了"关掉再打开接着用上次的缩放位置"这个要求。
                    // setReaderMode 也是改 GL 顶点数据，跟触摸手势那几个高频调用
                    // 一样，得丢到 GL 线程去做，见 applyReaderTransform 上面的注释。
                    surfaceView?.queueEvent {
                        renderer?.imageRenderer?.setReaderMode(readerModeEnabled)
                    }
                    surfaceView?.requestRender()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)

            val intentFilter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ACTION_RESET_READER_TRANSFORM)
                addAction(ACTION_READER_STEP)
            }
            ContextCompat.registerReceiver(
                this@LiveWallpaperService,
                screenStateReceiver,
                intentFilter,
                ContextCompat.RECEIVER_EXPORTED
            )

            MainScope().launch {
                workpaper.imageUri.distinctUntilChanged { old, new -> old == new }.collect {
                    if (it == null) return@collect
                    LogUtils.i(LOG_TAG, "On image uri", it.toString())

                    pendingImageUri = it.toUri()
                    pendingVideoUri = null

                    withContext(Dispatchers.IO) {
                        setImageBitmap(it.toUri())
                    }
                }
            }
            MainScope().launch {
                workpaper.videoUri.distinctUntilChanged { old, new -> old == new }.collect {
                    if (it == null) return@collect
                    LogUtils.i(LOG_TAG, "On video uri", it.toString())

                    pendingVideoUri = it.toUri()
                    pendingImageUri = null

                    changeVideoSource(it.toUri())
                }
            }

            engineLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        override fun onDestroy() {
            super.onDestroy()
            currentBitmap?.recycle()
            nextBitmap?.recycle()
            currentBitmap = null
            nextBitmap = null

            engineLifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            renderer?.destroy()
            unregisterReceiver(screenStateReceiver)
        }

        override fun onTouchEvent(event: MotionEvent?) {
            super.onTouchEvent(event)
            if (event == null) return
            gestureDetector.onTouchEvent(event)

            if (!settings.readerMode || renderer?.wallpaperType != WallpaperType.IMAGE) return

            scaleGestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        isTwoFingerActive = true
                        lastCentroidX = (event.getX(0) + event.getX(1)) / 2f
                        lastCentroidY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (isTwoFingerActive && event.pointerCount >= 2 &&
                        surfaceSize.width > 0 && surfaceSize.height > 0
                    ) {
                        val centroidX = (event.getX(0) + event.getX(1)) / 2f
                        val centroidY = (event.getY(0) + event.getY(1)) / 2f
                        val dx = centroidX - lastCentroidX
                        val dy = centroidY - lastCentroidY

                        // 统一用一套"贴着手指挪动矩形位置"的换算，不再区分放大/缩小
                        // 两套几何：readerPanX/readerPanY 现在直接是 NDC 空间里矩形
                        // 中心的偏移量（-1..1 是整个屏幕宽/高），手指移动多少像素，
                        // 矩形就跟着移动等比例的 NDC 距离，缩放倍数不影响这个换算，
                        // 跟手效果在任何缩放倍数下都一致。
                        // 顶点坐标系 Y 轴朝上为正，触屏坐标 Y 轴朝下为正，方向相反，
                        // 所以 Y 这里要用减号（X 不用翻，两者同向）。
                        // 允许拖动的范围放宽到"图片矩形与屏幕完全不再相交"为止
                        // （矩形半宽/半高是 readerScale*readerBaseHalfWidth/Height，
                        // 中心走到 ±(那个半宽/半高 + 1) 时两者刚好完全分开），而不是
                        // 像以前一样卡在"贴边"就停。横竖分开算，见上面字段注释。
                        val maxPanX = readerScale * readerBaseHalfWidth + 1f
                        val maxPanY = readerScale * readerBaseHalfHeight + 1f
                        readerPanX = (readerPanX + dx / surfaceSize.width * 2f)
                            .coerceIn(-maxPanX, maxPanX)
                        readerPanY = (readerPanY - dy / surfaceSize.height * 2f)
                            .coerceIn(-maxPanY, maxPanY)

                        applyReaderTransform()

                        lastCentroidX = centroidX
                        lastCentroidY = centroidY
                    }
                }

                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isTwoFingerActive) {
                        isTwoFingerActive = false
                        persistReaderTransform()
                    }
                }
            }
        }

        /**
         * 缩放/拖动这些高频触摸事件都要走这个方法，把真正改 GL 顶点数据的操作
         * 丢到 GL 线程去做，不要在主线程（触摸事件是在主线程收到的）直接改
         * vertexBuffer。之前是直接在主线程改，跟 GL 渲染线程正在读同一块
         * FloatBuffer 并发冲突，崩溃日志里的 BufferOverflowException 就是
         * 这个原因——NIO 的 Buffer 本身不是线程安全的，两个线程同时碰它的
         * position/limit 就会出这种问题。以前 setBitmap 这类调用没触发是因为
         * 频率低、GL 线程大部分时间是空闲的（RENDERMODE_WHEN_DIRTY），撞上的
         * 概率很小；阅读器模式这里是连续触摸事件，一秒钟能触发几十次，撞上的
         * 概率就高很多，所以只在这几个新加的高频调用点上做处理。
         */
        private fun applyReaderTransform() {
            val scale = readerScale
            val panX = readerPanX
            val panY = readerPanY
            surfaceView?.queueEvent {
                renderer?.imageRenderer?.setReaderTransform(scale, panX, panY)
            }
            surfaceView?.requestRender()
        }
        private fun persistReaderTransform() {
            val scale = readerScale
            val panX = readerPanX
            val panY = readerPanY

            if (settings.readerCarryOverTransform) {
                // 全局存档，不用管当前是哪张图。
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        workpaper.runningPreferencesRepository.update(
                            RunningPreferencesKeys.READER_CARRY_SCALE, scale
                        )
                        workpaper.runningPreferencesRepository.update(
                            RunningPreferencesKeys.READER_CARRY_PAN_X, panX
                        )
                        workpaper.runningPreferencesRepository.update(
                            RunningPreferencesKeys.READER_CARRY_PAN_Y, panY
                        )
                    } catch (e: Exception) {
                        LogUtils.e(LOG_TAG, "persist reader carry-over transform failed", e)
                    }
                }
                return
            }

            val contentUri = prevImageUri ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                // 落盘失败不该影响正在显示的壁纸，兜个底，别让未捕获的异常
                // 直接把整个进程带崩。
                try {
                    wallpaperRepository.updateReaderTransform(contentUri, scale, panX, panY)
                } catch (e: Exception) {
                    LogUtils.e(LOG_TAG, "persist reader transform failed", e)
                }
            }
        }

        /**
         * 计算"这张图在当前缩放下没有超出屏幕高度"时该用的 panY，按
         * settings.readerUndersizeVerticalAlign（居中/贴上/贴下边缘/保持不变）
         * 来定。调用方自己负责判断超没超屏（readerScale*readerBaseHalfHeight
         * 是否 <= 1），这里只管"没超"的情况该落在哪——跟 readerCarryOverPanYMode
         * 管的"超屏后怎么贴边"是完全独立的两套设置，互不影响。
         *
         * [current] 是 KEEP（保持不变）时要原样返回的值，调用方传"这次操作
         * 之前 readerPanY 本来的值"（比如换图前/翻页前的值）。
         * [treatKeepAsCenter] 给"双击复位"这种主动要恢复默认状态的场景用——
         * 复位就是要给一个确定的默认值，KEEP 在这里没意义，统一按 CENTER
         * 处理，而不是原样保留复位前的位置（不然复位按钮就白按了）。
         */
        private fun undersizeReaderPanY(
            current: Float,
            treatKeepAsCenter: Boolean = false
        ): Float {
            return when (
                try {
                    ReaderUndersizeVAlign.valueOf(settings.readerUndersizeVerticalAlign)
                } catch (e: Exception) {
                    ReaderUndersizeVAlign.CENTER
                }
            ) {
                ReaderUndersizeVAlign.CENTER -> 0f
                // 贴上边缘：图片上边缘(posTop = panY + scale)贴屏幕顶部(NDC 1)。
                ReaderUndersizeVAlign.TOP -> 1f - readerScale * readerBaseHalfHeight
                // 贴下边缘：图片下边缘(posBottom = panY - scale)贴屏幕底部(NDC -1)。
                ReaderUndersizeVAlign.BOTTOM -> readerScale * readerBaseHalfHeight - 1f
                ReaderUndersizeVAlign.KEEP -> if (treatKeepAsCenter) 0f else current
            }
        }

        /** 双击复位：缩放回 1（未放大，图片以"适合屏幕"的基准状态显示），居中。 */
        private fun resetReaderTransform() {
            readerScale = 1f
            readerPanX = 0f
            readerPanY = if (readerScale * readerBaseHalfHeight <= 1f) {
                undersizeReaderPanY(current = 0f, treatKeepAsCenter = true)
            } else {
                0f
            }
            applyReaderTransform()
            persistReaderTransform()
        }

        /**
         * "分区翻页"：把当前这张图（缩放超出屏幕的情况下）按屏幕大小切成
         * 若干个分区，[forward]=true 时挪到下一个分区，false 时挪到上一个。
         * 挪成功了返回 true（只是移动了 readerPanX/readerPanY，不涉及真的
         * 换图）；如果已经翻到头了、或者根本不需要翻页（没开阅读器模式、
         * 不是图片壁纸、这张图缩放后没超出屏幕），返回 false，调用方需要自己
         * 转去走"真的换下一张/上一张"的流程。
         *
         * 阅读方向（settings.readerReadingDirection）只影响同一行内先走
         * 左边还是右边这一个维度；行的顺序固定从上到下。
         */
        private fun stepReaderPage(forward: Boolean): Boolean {
            if (!settings.readerMode || renderer?.wallpaperType != WallpaperType.IMAGE) return false

            // 横竖两个方向的"图片内容半宽/半高"（NDC 空间）分开算：
            // readerScale*readerBaseHalfWidth/Height，撑满的那个方向留白比例是
            // 1，另一个方向可能小于 1（图片长宽比跟屏幕不一致时会留白，见字段
            // 注释）。格数因此也可能不相等——不再像以前一样假设 cols==rows。
            val cols = ceil((readerScale * readerBaseHalfWidth).toDouble()).toInt().coerceAtLeast(1)
            val rows = ceil((readerScale * readerBaseHalfHeight).toDouble()).toInt().coerceAtLeast(1)
            val totalPages = cols * rows
            if (totalPages <= 1) return false // 图片没超出屏幕，不需要翻页。

            val leftEdge = readerScale * readerBaseHalfWidth - 1f
            val rightEdge = 1f - readerScale * readerBaseHalfWidth
            val topEdge = 1f - readerScale * readerBaseHalfHeight
            val bottomEdge = readerScale * readerBaseHalfHeight - 1f

            // 把当前 readerPanX/readerPanY 反推成"当前在第几列/第几行"
            // （0-based），取离得最近的那个分区序号。
            fun nearestIndex(current: Float, start: Float, end: Float, count: Int): Int {
                if (count <= 1) return 0
                val t = (current - start) / (end - start)
                return (t * (count - 1)).roundToInt().coerceIn(0, count - 1)
            }

            val curCol = nearestIndex(readerPanX, leftEdge, rightEdge, cols)
            val curRow = nearestIndex(readerPanY, topEdge, bottomEdge, rows)

            val rtl = try {
                ReaderReadingDirection.valueOf(settings.readerReadingDirection)
            } catch (e: Exception) {
                ReaderReadingDirection.LEFT_TO_RIGHT
            } == ReaderReadingDirection.RIGHT_TO_LEFT

            // 按阅读方向把 (行, 列) 换算成一个线性的"阅读顺序序号"，
            // 从右到左时同一行内列序号要反过来数。
            fun readingOrderIndex(row: Int, col: Int) = row * cols + (if (rtl) cols - 1 - col else col)
            fun indexToRowCol(index: Int): Pair<Int, Int> {
                val row = index / cols
                val col = index % cols
                return row to (if (rtl) cols - 1 - col else col)
            }

            val curIndex = readingOrderIndex(curRow, curCol)
            val targetIndex = curIndex + (if (forward) 1 else -1)
            if (targetIndex < 0 || targetIndex >= totalPages) return false // 翻到头了。

            val (targetRow, targetCol) = indexToRowCol(targetIndex)

            fun pagedValue(start: Float, end: Float, index: Int, count: Int): Float {
                if (count <= 1) return 0f
                return start + (end - start) * index / (count - 1)
            }

            readerPanX = pagedValue(leftEdge, rightEdge, targetCol, cols)
            // rows<=1 说明高度本来就没超屏（比如漫画只是横向比屏幕宽），这种
            // 情况 pagedValue 会恒定返回 0f（写死居中），不吃
            // readerUndersizeVerticalAlign 设置——这里单独处理，跟其它换图
            // 落位的地方保持一致，不然同一张图内部左右翻页时这个设置会不生效。
            readerPanY = if (rows <= 1) {
                undersizeReaderPanY(current = readerPanY)
            } else {
                pagedValue(topEdge, bottomEdge, targetRow, rows)
            }
            applyReaderTransform()
            persistReaderTransform()
            return true
        }

        /**
         * App 按钮/桌面小组件的"上一张/下一张"统一先走这里：能在当前这张图
         * 里翻页就翻页；翻不动了（或者根本不需要翻页）就转发一条
         * [WallpaperReceiver] 认识的广播，走原来"真的换图"的流程，换图之前
         * 顺便记一下这次换图应该落在新图片的哪个分区（分区翻页触发的换图才
         * 会记，其它情况——比如图片本来就没超出屏幕——不记，跟以前行为
         * 完全一样）。
         */
        private fun handleReaderStepRequest(context: Context, forward: Boolean) {
            // 排除的是"壁纸选择器里的预览缩略图"这个引擎实例（isPreview），
            // 不能用 isVisible——用户在 App 内点按钮的时候，App 界面正盖在
            // 屏幕最上面，真正渲染在桌面的这个引擎这时候恰恰是"不可见"的，
            // 用 isVisible 判断会把 App 内按钮全部拦死。
            if (isPreview) return

            val overflowing = settings.readerMode &&
                renderer?.wallpaperType == WallpaperType.IMAGE &&
                readerScale * maxOf(readerBaseHalfWidth, readerBaseHalfHeight) > 1f
            // readerSectionPaging=false 时跳过分区翻页，不管当前图片有没有
            // 放大超屏，点上一张/下一张都直接走下面"真的换图"的流程
            // （对应功能加之前更早期版本的行为）。
            if (settings.readerSectionPaging && stepReaderPage(forward)) return

            // 下面这段"记一下新图片该落在哪个分区"，只有"分区翻页翻到头了、
            // 要换真正的下一张"这个场景才有意义——分区翻页整个关掉之后，
            // 点下一张就是单纯的换图，不该再把 pendingReaderLandingX/Y 设
            // 成"贴左/贴右/贴上/贴下"，不然会覆盖掉"跟随上一张"里
            // readerCarryOverPanXMode/readerCarryOverPanYMode 算出来的结果，
            // 导致那两个设置形同虚设。
            if (settings.readerSectionPaging && overflowing) {
                val rtl = try {
                    ReaderReadingDirection.valueOf(settings.readerReadingDirection)
                } catch (e: Exception) {
                    ReaderReadingDirection.LEFT_TO_RIGHT
                } == ReaderReadingDirection.RIGHT_TO_LEFT

                // 翻到头了、要真的换图了：下一张 -> 新图片从"阅读顺序的第一个
                // 分区"开始（从左到右是贴左边，从右到左是贴右边）；上一张 ->
                // 新图片从"阅读顺序的最后一个分区"开始（正好相反）。纵向固定
                // 下一张从最上面开始、上一张从最下面开始（行的顺序本来就没有
                // 反过来的选项）。
                pendingReaderLandingX = when {
                    forward && !rtl -> ReaderPanXMode.LEFT
                    forward && rtl -> ReaderPanXMode.RIGHT
                    !forward && !rtl -> ReaderPanXMode.RIGHT
                    else -> ReaderPanXMode.LEFT
                }
                pendingReaderLandingY = if (forward) ReaderPanYMode.TOP else ReaderPanYMode.BOTTOM
            }

            val intent = Intent(context, WallpaperReceiver::class.java).apply {
                putExtra(
                    WallpaperReceiver.FLAG_DIRECTION,
                    if (forward) WallpaperReceiver.DIRECTION_NEXT else WallpaperReceiver.DIRECTION_PREV
                )
            }
            context.sendBroadcast(intent)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (renderer == null) return

            when (renderer!!.wallpaperType) {
                WallpaperType.IMAGE -> {
                    onImageVisibleChanged(visible)
                }

                WallpaperType.VIDEO -> {
                    onVideoVisibleChanged(visible)
                }
            }
        }

        private fun onImageVisibleChanged(visible: Boolean) {
            if (renderer == null) return
        }

        private fun onVideoVisibleChanged(visible: Boolean) {
            if (visible) {
                player.start()
            } else {
                player.pause()
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            initSurfaceView()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            stopVideo()
            destroySurfaceView()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int
        ) {
            super.onOffsetsChanged(
                xOffset, yOffset, xOffsetStep, yOffsetStep, xPixelOffset, yPixelOffset
            )
            if (renderer?.wallpaperType != WallpaperType.IMAGE ||
                !settings.wallpaperScrollable
            ) return

            renderer?.imageRenderer?.updateOffset(xOffset)
            surfaceView?.requestRender()
        }

        private suspend fun setImageBitmap(uri: Uri) {
            if (!surfaceHolder.surface.isValid) return

            renderer?.updateWallpaperType(WallpaperType.IMAGE)
            stopVideo()

            var newBitmap = loadBitmap(uri) ?: return
            newBitmap = workpaper.handleBitmapStyle(newBitmap)

            // 阅读器模式下，先把这张新图"适合屏幕"缩放后的真实半宽/半高比例
            // 记下来（见 readerBaseHalfWidth/Height 字段注释），下面所有贴边/
            // 翻页相关的计算都要用到，必须在这些计算之前先算好。
            if (settings.readerMode && surfaceSize.width > 0 && surfaceSize.height > 0) {
                readerBaseHalfWidth = newBitmap.width.toFloat() / surfaceSize.width
                readerBaseHalfHeight = newBitmap.height.toFloat() / surfaceSize.height
            }

            renderer?.imageRenderer?.setReaderMode(settings.readerMode)
            if (settings.readerMode && settings.readerCarryOverTransform) {
                // "跟随上一张"模式：缩放(scale)不查这张图自己的记录，直接沿用
                // 内存里现在这个"当前值"（readerScale 本来就没被清过，切图前
                // 是什么状态，切完还是什么状态）。只有引擎刚起来、内存里还没有
                // 值的时候，才去读一次上次退出前存的全局值，之后就一直用内存
                // 里的，不用每次都读。
                if (!carryOverLoaded) {
                    carryOverLoaded = true
                    val runningPrefs = try {
                        workpaper.runningPreferencesRepository.runningPreferencesFlow.first()
                    } catch (e: Exception) {
                        LogUtils.e(LOG_TAG, "read reader carry-over transform failed", e)
                        null
                    }
                    readerScale = (runningPrefs?.readerCarryScale ?: 1f)
                        .coerceIn(READER_SCALE_MIN, READER_SCALE_MAX)
                    val maxPanX = readerScale * readerBaseHalfWidth + 1f
                    val maxPanY = readerScale * readerBaseHalfHeight + 1f
                    readerPanX = (runningPrefs?.readerCarryPanX ?: 0f).coerceIn(-maxPanX, maxPanX)
                    readerPanY = (runningPrefs?.readerCarryPanY ?: 0f).coerceIn(-maxPanY, maxPanY)
                }

                // 位置(pan)是否原样跟着走，还是改成贴边/居中，由这两个子开关
                // 决定，不管这张图在这个方向上超没超屏都统一按选的模式算——
                // 之前版本会在"没超屏"的时候悄悄改用另一套设置
                // （readerUndersizeVerticalAlign），导致这里选了贴边也没用，
                // 现在去掉了那个例外，选哪个就是哪个，行为可预期。
                // FOLLOW：原样带过去，公式是幂等的，不会有跳变。
                // CENTER/LEFT/RIGHT、CENTER/TOP/BOTTOM：不管超不超屏都套用
                // 同一个贴边/居中公式——没超屏时公式算出来的位置一样有效
                // （比如贴上边缘=图片上边缘贴屏幕顶部，留白全部在下面）。
                readerPanX = when (
                    try {
                        ReaderPanXMode.valueOf(settings.readerCarryOverPanXMode)
                    } catch (e: Exception) {
                        ReaderPanXMode.FOLLOW
                    }
                ) {
                    ReaderPanXMode.FOLLOW -> readerPanX
                    ReaderPanXMode.CENTER -> 0f
                    // 贴左边缘：矩形中心右移，让图片左边缘(posLeft = panX - scale)
                    // 正好落在屏幕左边缘(NDC -1)上。
                    ReaderPanXMode.LEFT -> readerScale * readerBaseHalfWidth - 1f
                    // 贴右边缘：矩形中心左移，让图片右边缘(posRight = panX + scale)
                    // 正好落在屏幕右边缘(NDC 1)上。
                    ReaderPanXMode.RIGHT -> 1f - readerScale * readerBaseHalfWidth
                }
                readerPanY = when (
                    try {
                        ReaderPanYMode.valueOf(settings.readerCarryOverPanYMode)
                    } catch (e: Exception) {
                        ReaderPanYMode.FOLLOW
                    }
                ) {
                    ReaderPanYMode.FOLLOW -> readerPanY
                    ReaderPanYMode.CENTER -> 0f
                    // 贴上边缘：NDC 里 Y 轴向上为正，图片上边缘(posTop = panY + scale)
                    // 贴屏幕顶部(NDC 1)。
                    ReaderPanYMode.TOP -> 1f - readerScale * readerBaseHalfHeight
                    // 贴下边缘：图片下边缘(posBottom = panY - scale)贴屏幕底部(NDC -1)。
                    ReaderPanYMode.BOTTOM -> readerScale * readerBaseHalfHeight - 1f
                }
            } else if (settings.readerMode) {
                val saved = try {
                    wallpaperRepository.getReaderTransform(uri.toString())
                } catch (e: Exception) {
                    LogUtils.e(LOG_TAG, "read reader transform failed", e)
                    null
                }
                readerScale = (saved?.readerScale ?: 1f).coerceIn(READER_SCALE_MIN, READER_SCALE_MAX)
                val maxPanX = readerScale * readerBaseHalfWidth + 1f
                val maxPanY = readerScale * readerBaseHalfHeight + 1f
                readerPanX = (saved?.readerPanX ?: 0f).coerceIn(-maxPanX, maxPanX)
                // 没存过这张图的位置时（比如第一次看到、或者刚双击/恢复默认过），
                // 落地默认值改用 undersizeReaderPanY(current = readerPanY)，
                // 而不是写死 0f 居中——
                // 这样"没超屏"的图（漫画常见）翻页/换图时能按用户选的对齐方式
                // （居中/贴上/贴下边缘）来摆，不会每次都被强制拉回居中。已经手动
                // 存过位置的图（saved 不为空）不受影响，还是用回存的那个值。
                readerPanY = (
                    saved?.readerPanY ?: if (readerScale * readerBaseHalfHeight <= 1f) {
                        undersizeReaderPanY(current = readerPanY)
                    } else {
                        0f
                    }
                ).coerceIn(-maxPanY, maxPanY)
            }
            // readerMode 关闭时：readerScale/readerPanX/readerPanY 保持原样不清零。
            // 关闭期间这几个值反正不参与渲染（setReaderMode(false) 之后 GL 那边
            // 直接走非阅读器分支），留着不清空是为了"关掉再打开阅读器模式，
            // 接着用上次的缩放位置"这个要求——如果这里清零，纯纯是白做。

            // "分区翻页"翻到头触发的这次换图，不管上面走的是哪个分支算出来的
            // 位置，都在这里用"新图片该落在哪个分区"覆盖掉——分区序号是按
            // *这张新图片刚刚确定下来的 readerScale* 现算的，缩放不够大的话
            // （比如 readerCarryOverTransform 关着、这张图本来就没被手动缩放
            // 过）公式会自然算出 0（居中），不需要特殊判断。用完立刻清空，
            // 不影响下一次正常换图。
            if (settings.readerMode) {
                // 只有这张新图在对应方向真的超出屏幕时才需要贴边；不超的那个
                // 方向维持居中（0f），不然横竖比例跟触发翻页的那张图不一样时，
                // 没超出的方向会被莫名其妙地推偏，露出不对称的黑边。
                pendingReaderLandingX?.let { mode ->
                    if (readerScale * readerBaseHalfWidth > 1f) {
                        readerPanX = when (mode) {
                            ReaderPanXMode.LEFT -> readerScale * readerBaseHalfWidth - 1f
                            ReaderPanXMode.RIGHT -> 1f - readerScale * readerBaseHalfWidth
                            ReaderPanXMode.FOLLOW -> readerPanX
                            // pendingReaderLandingX 只会被赋成 LEFT/RIGHT（见
                            // handleReaderStepRequest），这两支实际不会走到，
                            // 纯粹是为了让 when 覆盖 ReaderPanXMode 全部取值。
                            ReaderPanXMode.CENTER -> 0f
                        }
                    } else {
                        readerPanX = 0f
                    }
                }
                pendingReaderLandingY?.let { mode ->
                    if (readerScale * readerBaseHalfHeight > 1f) {
                        readerPanY = when (mode) {
                            ReaderPanYMode.TOP -> 1f - readerScale * readerBaseHalfHeight
                            ReaderPanYMode.BOTTOM -> readerScale * readerBaseHalfHeight - 1f
                            ReaderPanYMode.FOLLOW -> readerPanY
                            // 同上，pendingReaderLandingY 只会是 TOP/BOTTOM。
                            ReaderPanYMode.CENTER -> 0f
                        }
                    } else {
                        // 这张新图没超屏——不再写死居中，改成按
                        // readerUndersizeVerticalAlign 设置摆（默认还是居中，
                        // 老用户行为不变；选了贴边的话，翻页换到一张没超屏的
                        // 图时也能贴边，比如躲开桌面底部的 dock 栏）。
                        readerPanY = undersizeReaderPanY(current = readerPanY)
                    }
                }
            }
            pendingReaderLandingX = null
            pendingReaderLandingY = null

            if (!settings.imageTransition) {
                currentBitmap?.recycle()
                currentBitmap = newBitmap
                renderer?.imageRenderer?.setBitmap(newBitmap)
                applyReaderTransform()
                prevImageUri = uri.toString()
                return
            }

            val prevBitmap = currentBitmap ?: newBitmap
            nextBitmap = newBitmap
            prevImageUri = uri.toString()

            renderer?.imageRenderer?.setBitmap(prevBitmap)
            renderer?.imageRenderer?.setBitmap(nextBitmap!!, isNext = true)
            applyReaderTransform()

            renderer?.imageRenderer?.startTransition()
            surfaceView?.requestRender()

            lifecycleScope.launch {
                val transitionSteps = 20
                for (i in 0..transitionSteps) {
                    val alpha = i.toFloat() / transitionSteps
                    renderer?.imageRenderer?.updateTransitionAlpha(alpha)
                    surfaceView?.requestRender()
                    delay(15)
                }

                currentBitmap?.recycle()
                currentBitmap = nextBitmap
                nextBitmap = null

                bitmap = currentBitmap
            }
        }

        private fun loadBitmap(uri: Uri?, options: Options = Options()): Bitmap? {
            if (uri == null) return null

            val originBitmap = bitmapFromContentUri(uri, this@LiveWallpaperService, options)
                ?: return null

            if (surfaceSize.width <= 0 || surfaceSize.height <= 0) return originBitmap

            val scaleType = try {
                WallpaperScaleType.valueOf(settings.scaleType)
            } catch (e: Exception) {
                WallpaperScaleType.FIXED_PERCENTAGE
            }

            if (settings.readerMode) {
                // 阅读器模式下图片的基准展示固定用"适合屏幕"（完整显示，可能留黑边），
                // 跟全局缩放模式设置无关；具体的放大/裁剪交给 GL 层的双指手势去做。
                // （不再需要在这里算"盖满屏幕的最小缩放"——阅读器模式已经不设
                // 这条下限了，缩多小都不会崩，见 GLImageWallpaperRenderer。）
                // allowOverflow = true：只做等比缩放，不再合成到一张跟屏幕等大、
                // 可能带黑边的画布上。FIT 缩放的结果本来就不会超出屏幕（取的是
                // min(widthRatio, heightRatio)），两个方向里"撑满的那个方向"
                // 缩放后正好等于屏幕对应边长，"留白的那个方向"缩放后会小于
                // 屏幕对应边长——这个"小于"的比例（readerBaseHalfWidth/Height，
                // 见 setImageBitmap）会被阅读器的缩放/翻页逻辑用来算真正的图片
                // 内容边缘在哪，而不是像以前一样，把黑边也当成图片内容的一部分
                // 去贴边（那正是"上下翻页贴不到边、卡在中间露一截"这个问题的
                // 根源：以前合成出来的画布图，四周黑边和中间照片糊在一张位图里，
                // 贴边公式只认得"画布边缘"，认不出"照片内容边缘"和"黑边"的区别）。
                return originBitmap.scaleForScreen(
                    targetWidth = surfaceSize.width,
                    targetHeight = surfaceSize.height,
                    scaleType = WallpaperScaleType.FIT,
                    fixedPercent = 100,
                    allowOverflow = true,
                ).also {
                    if (it != originBitmap) originBitmap.recycle()
                }
            }

            return originBitmap.scaleForScreen(
                targetWidth = surfaceSize.width,
                targetHeight = surfaceSize.height,
                scaleType = scaleType,
                fixedPercent = settings.scalePercent,
                allowOverflow = settings.wallpaperScrollable,
            ).also {
                if (it != originBitmap) originBitmap.recycle()
            }
        }

        private fun initSurfaceView() {
            if (surfaceView != null) return

            surfaceView = GLWallpaperSurfaceView(this@LiveWallpaperService)

            renderer = renderer ?: WallpaperRenderer(surfaceView!!, lifecycleScope)

            if (surfaceSize.width == 0) {
                lifecycleScope.launch {
                    renderer!!.surfaceSize.collect {
                        if (it.width > 0) {
                            surfaceSize = Size(it.width, it.height)
                        }
                    }
                }
            }

            val width = surfaceHolder.surfaceFrame.width()
            val height = surfaceHolder.surfaceFrame.height()
            renderer!!.videoRenderer.setScreenSize(width, height)
            val scaleType = try {
                WallpaperScaleType.valueOf(settings.scaleType)
            } catch (e: Exception) {
                WallpaperScaleType.FIXED_PERCENTAGE
            }
            renderer!!.videoRenderer.setScaleType(scaleType, settings.scalePercent)
            renderer!!.imageRenderer.setReaderMode(settings.readerMode)

            surfaceView!!.setEGLContextClientVersion(2)
            surfaceView!!.apply {
                setRenderer(renderer)
                preserveEGLContextOnPause = true
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            }

            // renderer 刚创建出来，补一次此前因为 renderer 还没就绪而被跳过的
            // 图片/视频，避免黑屏一直卡到下一次真正切换壁纸才恢复。
            pendingVideoUri?.let { uri ->
                changeVideoSource(uri)
            } ?: pendingImageUri?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    setImageBitmap(uri)
                }
            }
        }

        private fun destroySurfaceView() {
            surfaceView?.detach()
            surfaceView = null
            renderer = null
        }

        private fun changeVideoSource(uri: Uri) {
            LogUtils.i(
                LOG_TAG,
                "fun changeVideoSource",
                "isVisible=$isVisible, isScreenOn=$isScreenOn"
            )

            if (renderer == null) return

            renderer!!.videoRenderer.setSourcePlayer(player)
            renderer!!.updateWallpaperType(WallpaperType.VIDEO)

            updateVideoInfo(uri)

            player.apply {
                reset()
                setVolume(0f, 0f)
                isLooping = true
                setDataSource(this@LiveWallpaperService, uri)
                setOnPreparedListener {
                    if (isVisible && isScreenOn) {
                        it.start()
                        LogUtils.i(
                            LOG_TAG,
                            "isVisible=$isVisible, isScreenOn=$isScreenOn",
                            "player started"
                        )
                    } else {
                        LogUtils.i(
                            LOG_TAG,
                            "isVisible=$isVisible, isScreenOn=$isScreenOn",
                            "player not start"
                        )
                    }
                }

                prepareAsync()
            }

            prevImageUri = null
        }

        private fun stopVideo() {
            player.apply {
                if (isPlaying) {
                    stop()
                    LogUtils.i(LOG_TAG, "player stopped")
                }
            }
        }

        private fun updateVideoInfo(uri: Uri) {
            if (renderer == null) return

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this@LiveWallpaperService, uri)
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
            )!!.toInt()
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )!!.toInt()
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )!!.toInt()
            retriever.release()
            renderer!!.videoRenderer.setVideoSizeAndRotation(
                width = width, height = height, rotation = rotation
            )
        }

        inner class GLWallpaperSurfaceView(
            context: Context
        ) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder {
                return surfaceHolder
            }

            fun detach() {
                onDetachedFromWindow()
            }
        }
    }
}