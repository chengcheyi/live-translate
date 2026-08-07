package com.echo.livetranslate.service

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.echo.livetranslate.core.CaptionState
import com.echo.livetranslate.data.Settings
import kotlin.math.roundToInt

/**
 * 屏幕上那条字幕。用原生 View 而不是 Compose：悬浮窗每秒会刷新很多次，
 * 少一层组合和重组就少一分掉帧的机会。
 */
class OverlayController(
    private val context: Context,
    private val onMoved: (x: Int, y: Int) -> Unit
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: LinearLayout? = null
    private var originalView: TextView? = null
    private var translatedView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var settings = Settings()

    fun show(initial: Settings) {
        if (root != null) {
            apply(initial)
            return
        }
        settings = initial

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(10)
            setPadding(pad, dp(6), pad, dp(6))
        }

        val original = TextView(context).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.05f)
        }
        val translated = TextView(context).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.05f)
        }

        container.addView(
            original,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(
            translated,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val lp = WindowManager.LayoutParams(
            widthFor(initial),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            baseFlags(initial.locked),
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initial.overlayX
            y = if (initial.overlayY >= 0) initial.overlayY else defaultY()
        }

        attachDrag(container, lp)

        root = container
        originalView = original
        translatedView = translated
        params = lp

        windowManager.addView(container, lp)
        apply(initial)
    }

    private fun attachDrag(view: View, lp: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { _, event ->
            if (settings.locked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).roundToInt()
                    lp.y = startY + (event.rawY - downY).roundToInt()
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onMoved(lp.x, lp.y)
                    true
                }
                else -> false
            }
        }
    }

    /** 设置变了就整体重刷一遍样式，比逐项 diff 简单且开销可以忽略。 */
    fun apply(s: Settings) {
        settings = s
        val container = root ?: return
        val lp = params ?: return

        val background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(s.backgroundArgb)
        }
        container.background = background

        originalView?.applyStyle(s, s.originalColor)
        translatedView?.applyStyle(s, s.translatedColor)

        originalView?.visibility = if (s.showOriginal) View.VISIBLE else View.GONE
        translatedView?.visibility = if (s.bilingual || !s.showOriginal) View.VISIBLE else View.GONE

        lp.width = widthFor(s)
        lp.flags = baseFlags(s.locked)
        if (s.overlayY >= 0) lp.y = s.overlayY
        lp.x = s.overlayX
        runCatching { windowManager.updateViewLayout(container, lp) }
    }

    private fun TextView.applyStyle(s: Settings, color: Int) {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, s.fontSizeSp.toFloat())
        setTextColor(color)
        maxLines = s.maxLines.coerceAtLeast(1)
        if (s.outlineText) {
            // 描边让字幕在亮画面上也读得清，比加深背景更不挡视频
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    fun update(state: CaptionState) {
        root?.post {
            originalView?.maxLines = settings.maxLines.coerceAtLeast(1)
            originalView?.text = state.original
            translatedView?.text = state.translated
        }
    }

    /** 错误提示不受字幕行数限制——被截断的报错等于没报。 */
    fun showMessage(text: String) {
        root?.post {
            originalView?.maxLines = MESSAGE_MAX_LINES
            originalView?.visibility = View.VISIBLE
            originalView?.text = text
            translatedView?.text = ""
        }
    }

    fun hide() {
        val container = root ?: return
        runCatching { windowManager.removeView(container) }
        root = null; originalView = null; translatedView = null; params = null
    }

    private fun widthFor(s: Settings): Int {
        val screen = context.resources.displayMetrics.widthPixels
        return (screen * s.overlayWidthPct.coerceIn(30, 100) / 100)
    }

    private fun defaultY(): Int =
        (context.resources.displayMetrics.heightPixels * 0.78f).roundToInt()

    private fun baseFlags(locked: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        // 锁定后触摸直接穿透给下面的播放器，滑视频不会被字幕挡住
        if (locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return flags
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val MESSAGE_MAX_LINES = 6
    }
}
