package com.echo.livetranslate.core

import android.util.Log
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.provider.Caption
import com.echo.livetranslate.provider.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CaptionState(
    val original: String = "",
    val translated: String = "",
    val isFinal: Boolean = false
)

/**
 * 把识别结果变成屏幕上的一行字幕，顺带把定稿的句子交给历史记录。
 *
 * 延迟策略：原文一到就立刻上屏，不等翻译；译文异步补位。
 * 中间结果（partial）会被节流并且只保留最新一条在飞——否则翻译请求会
 * 排成一条越拖越长的队，字幕反而比声音慢好几秒。
 */
class CaptionPipeline(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val onState: (CaptionState) -> Unit,
    private val onFinalLine: (original: String, translated: String) -> Unit
) {
    private val translator: Translator? = Translator.from(settings)

    @Volatile private var current = CaptionState()
    @Volatile private var lastTranslatedSource = ""
    @Volatile private var lastPartialSentAt = 0L
    @Volatile private var lastRecorded = ""

    /** 已定稿但译文还在路上的句子，等译文回来再入库 */
    @Volatile private var pendingFinal: String? = null

    private val gate = Mutex()
    private var inFlight: Job? = null

    fun submit(caption: Caption) {
        val original = caption.original.trim()
        if (original.isEmpty()) return

        // 识别端自带译文时直接用，一次网络往返都不用多花
        if (caption.translated != null) {
            push(CaptionState(original, caption.translated, caption.isFinal))
            if (caption.isFinal) record(original, caption.translated)
            return
        }

        push(current.copy(original = original, isFinal = caption.isFinal))

        val t = translator
        if (t == null) {
            if (caption.isFinal) record(original, "")
            return
        }

        if (caption.isFinal) {
            pendingFinal = original
            // 上一版的译文正好就是这句，直接入库不用再请求一次
            if (original == lastTranslatedSource && current.translated.isNotEmpty()) {
                record(original, current.translated)
                pendingFinal = null
                return
            }
        } else {
            if (original == lastTranslatedSource) return
            val now = System.currentTimeMillis()
            if (now - lastPartialSentAt < PARTIAL_INTERVAL_MS) return
            if (original.length < MIN_PARTIAL_CHARS) return
            lastPartialSentAt = now
        }

        // 后来者胜：新的一版一到就放弃上一版，永远只显示最新
        inFlight?.cancel()
        inFlight = scope.launch {
            if (!caption.isFinal) delay(PARTIAL_SETTLE_MS)
            val result = runCatching { t.translate(original, settings.targetLang) }
            gate.withLock {
                result.onSuccess { translated ->
                    if (current.original == original || caption.isFinal) {
                        lastTranslatedSource = original
                        push(
                            current.copy(
                                original = original,
                                translated = translated,
                                isFinal = caption.isFinal
                            )
                        )
                    }
                    if (caption.isFinal) {
                        record(original, translated)
                        pendingFinal = null
                    }
                }.onFailure { e ->
                    if (e !is CancellationException) {
                        Log.w(TAG, "translate failed: ${e.message}")
                        // 翻译挂了也别丢句子，原文照样存
                        if (caption.isFinal) {
                            record(original, "")
                            pendingFinal = null
                        }
                    }
                }
            }
        }
    }

    private fun record(original: String, translated: String) {
        if (original == lastRecorded) return
        lastRecorded = original
        onFinalLine(original, translated)
    }

    private fun push(state: CaptionState) {
        current = state
        onState(state)
    }

    fun close() {
        inFlight?.cancel()
        // 还没等到译文的最后一句，落一条原文，别让它凭空消失
        pendingFinal?.let { record(it, "") }
        pendingFinal = null
    }

    private companion object {
        const val TAG = "CaptionPipeline"
        /** 中间结果最多这么频繁地送去翻译 */
        const val PARTIAL_INTERVAL_MS = 450L
        /** 再等一下下，避开正在快速变化的半个词 */
        const val PARTIAL_SETTLE_MS = 120L
        const val MIN_PARTIAL_CHARS = 6
    }
}
