package com.echo.livetranslate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.echo.livetranslate.MainActivity
import com.echo.livetranslate.R
import com.echo.livetranslate.core.AudioCapture
import com.echo.livetranslate.core.CaptionPipeline
import com.echo.livetranslate.data.EngineMode
import com.echo.livetranslate.data.HistoryStore
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.SettingsStore
import com.echo.livetranslate.ocr.ScreenTextReader
import com.echo.livetranslate.provider.AsrProviderFactory
import com.echo.livetranslate.provider.Caption
import com.echo.livetranslate.provider.SpeechProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 字幕的总闸。前台服务持有 MediaProjection、采集、识别、翻译和悬浮窗，
 * 全程只有这一个实例，退出时统一收尾。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var audio: AudioCapture? = null
    private var speech: SpeechProvider? = null
    private var ocr: ScreenTextReader? = null
    private var pipeline: CaptionPipeline? = null
    private var overlay: OverlayController? = null
    private var sessionId = 0L

    @Volatile private var settings = Settings()
    @Volatile private var lastErrorShownAt = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // 用户在系统弹窗里点了「停止共享」
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 起必须先进前台、且声明 mediaProjection 类型，才能拿到投屏对象
        startInForeground()

        scope.launch {
            val s = SettingsStore.flow(this@CaptureService).first()
            settings = s
            main.post { startEverything(resultCode, resultData, s) }
            observeSettings()
        }

        return START_STICKY
    }

    private fun startEverything(resultCode: Int, resultData: Intent, s: Settings) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (mp == null) {
            toast("投屏授权失效，请重新开启")
            stopSelf()
            return
        }
        projection = mp
        mp.registerCallback(projectionCallback, main)

        overlay = OverlayController(this) { x, y ->
            scope.launch { SettingsStore.save(this@CaptureService, settings.copy(overlayX = x, overlayY = y)) }
        }.also { it.show(s) }

        val history = HistoryStore.get(this)
        sessionId = runCatching { history.beginSession(s.engineMode) }.getOrDefault(0L)

        pipeline = CaptionPipeline(
            scope = scope,
            settings = s,
            onState = { state -> overlay?.update(state) },
            onFinalLine = { original, translated ->
                scope.launch(Dispatchers.IO) { history.appendLine(sessionId, original, translated) }
            }
        )

        when (s.engineMode) {
            EngineMode.AUDIO -> startAudio(mp, s)
            EngineMode.OCR -> startOcr(mp, s)
        }
    }

    private fun startAudio(mp: MediaProjection, s: Settings) {
        val provider = AsrProviderFactory.create(s)
        speech = provider

        overlay?.showMessage("正在连接识别服务…")

        scope.launch(Dispatchers.IO) {
            provider.start(
                onCaption = { caption -> pipeline?.submit(caption) },
                onError = { message -> reportError(message) }
            )
        }

        audio = AudioCapture(
            projection = mp,
            targetSampleRate = provider.sampleRate,
            onPcm = { buffer, len -> provider.feed(buffer, len) },
            onError = { message -> reportError(message) }
        ).also { it.start() }
    }

    private fun startOcr(mp: MediaProjection, s: Settings) {
        overlay?.showMessage("OCR 模式已启动，等待画面上的字幕…")
        ocr = ScreenTextReader(
            projection = mp,
            metrics = resources.displayMetrics,
            script = s.ocrScript,
            bottomPct = s.ocrBottomPct,
            intervalMs = s.ocrIntervalMs.toLong(),
            onText = { text -> pipeline?.submit(Caption(text, null, true)) },
            onError = { message -> reportError(message) }
        ).also { it.start() }
    }

    /** 设置改了就热更新悬浮窗样式；换识别服务这类结构性改动仍需重开。 */
    private suspend fun observeSettings() {
        SettingsStore.flow(this).collect { s ->
            settings = s
            main.post { overlay?.apply(s) }
        }
    }

    private fun reportError(message: String) {
        Log.w(TAG, message)
        val now = System.currentTimeMillis()
        if (now - lastErrorShownAt < ERROR_THROTTLE_MS) return
        lastErrorShownAt = now
        main.post {
            overlay?.showMessage(message)
            toast(message)
        }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private fun startInForeground() {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("字幕运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        running = false
        runCatching { audio?.stop() }
        runCatching { ocr?.stop() }
        runCatching { speech?.stop() }
        runCatching { pipeline?.close() }
        runCatching { overlay?.hide() }
        runCatching {
            projection?.unregisterCallback(projectionCallback)
            projection?.stop()
        }
        val id = sessionId
        if (id > 0) {
            // 空会话不留在历史里
            runCatching { HistoryStore.get(applicationContext).dropIfEmpty(id) }
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "captions"
        private const val NOTIFICATION_ID = 42
        private const val ERROR_THROTTLE_MS = 6000L

        const val ACTION_STOP = "com.echo.livetranslate.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile var running = false
            private set

        fun launch(context: Context, resultCode: Int, data: Intent) {
            running = true
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            running = false
            context.startService(
                Intent(context, CaptureService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
