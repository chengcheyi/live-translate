package com.echo.livetranslate.data

/** 引擎模式：抓系统声音，还是截屏 OCR。 */
enum class EngineMode(val label: String) {
    AUDIO("音频模式（听系统声音）"),
    OCR("OCR 模式（读屏幕硬字幕）")
}

/** 流式语音识别服务商。 */
enum class AsrProvider(val label: String, val oneShotTranslate: Boolean) {
    /** 识别 + 翻译一次返回，双语字幕延迟最低 */
    AZURE("Azure 语音翻译（识别+翻译一体，延迟最低）", true),
    /** 只识别，翻译交给独立翻译服务 */
    DEEPGRAM("Deepgram 流式识别", false),
    /** OpenAI 兼容的 Realtime WebSocket，只取转写 */
    OPENAI_REALTIME("OpenAI Realtime（地址可自定义）", false)
}

/** 文本翻译服务商。ASR 自带翻译时可选 NONE。 */
/** OCR 识别哪套文字。选错会明显掉准确率，所以做成显式选项。 */
enum class OcrScript(val label: String) {
    LATIN("拉丁字母（英/法/德/西…）"),
    CHINESE("中文"),
    JAPANESE("日文"),
    KOREAN("韩文")
}

enum class TransProvider(val label: String) {
    NONE("不额外翻译（由识别端直出）"),
    OPENAI_COMPAT("OpenAI 兼容接口（DeepSeek / Kimi / OpenRouter / 本地均可）"),
    DEEPL("DeepL"),
    GOOGLE("Google Translate v2")
}

data class Settings(
    val engineMode: EngineMode = EngineMode.AUDIO,

    // ---- 语音识别 ----
    val asrProvider: AsrProvider = AsrProvider.AZURE,

    val azureKey: String = "",
    val azureRegion: String = "eastasia",
    /** 逗号分隔；多于一个时开启自动语种识别 */
    val azureSourceLangs: String = "en-US,ja-JP,ko-KR",

    val deepgramKey: String = "",
    val deepgramUrl: String = "wss://api.deepgram.com/v1/listen",
    val deepgramModel: String = "nova-3",
    /** multi = 多语种自动识别 */
    val deepgramLanguage: String = "multi",

    val realtimeKey: String = "",
    val realtimeUrl: String = "wss://api.openai.com/v1/realtime?intent=transcription",
    val realtimeModel: String = "gpt-4o-mini-transcribe",
    val realtimeLanguage: String = "",

    // ---- 翻译 ----
    val transProvider: TransProvider = TransProvider.NONE,
    val targetLang: String = "zh-Hans",

    val openaiTransUrl: String = "https://api.openai.com/v1/chat/completions",
    val openaiTransKey: String = "",
    val openaiTransModel: String = "gpt-4o-mini",

    val deeplKey: String = "",
    val deeplFreeTier: Boolean = true,

    val googleKey: String = "",

    // ---- 字幕外观 ----
    val bilingual: Boolean = true,
    val showOriginal: Boolean = true,
    val fontSizeSp: Int = 18,
    val maxLines: Int = 2,
    val originalColor: Int = 0xFFE2E8F0.toInt(),
    val translatedColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    /** 0..100 */
    val backgroundAlpha: Int = 55,
    val outlineText: Boolean = true,

    // ---- 悬浮窗位置（像素，-1 表示未设置，默认贴底） ----
    val overlayX: Int = 0,
    val overlayY: Int = -1,
    val overlayWidthPct: Int = 92,
    val locked: Boolean = false,

    // ---- OCR ----
    val ocrScript: OcrScript = OcrScript.LATIN,
    val ocrIntervalMs: Int = 700,
    /** 只识别屏幕下方这个百分比的区域，减少误识别、提速 */
    val ocrBottomPct: Int = 30
) {
    /** 译文由识别端直接给出，无需再调翻译接口 */
    val translationComesFromAsr: Boolean
        get() = engineMode == EngineMode.AUDIO &&
                asrProvider.oneShotTranslate &&
                transProvider == TransProvider.NONE

    /** 配置不完整时的提示，null 表示可以启动 */
    fun validate(): String? = when {
        engineMode == EngineMode.AUDIO && asrProvider == AsrProvider.AZURE && azureKey.isBlank() ->
            "请填写 Azure 语音服务的 Key 和区域"
        engineMode == EngineMode.AUDIO && asrProvider == AsrProvider.DEEPGRAM && deepgramKey.isBlank() ->
            "请填写 Deepgram 的 API Key"
        // Azure 那栏能列多个语言，Deepgram 不能。两栏长得一样，很容易照抄错
        engineMode == EngineMode.AUDIO && asrProvider == AsrProvider.DEEPGRAM &&
            deepgramLanguage.contains(',') ->
            "Deepgram 的语言只能填一个代码（如 en）。要同时识别多种语言请填 multi"
        engineMode == EngineMode.AUDIO && asrProvider == AsrProvider.OPENAI_REALTIME && realtimeKey.isBlank() ->
            "请填写 Realtime 接口的 API Key"
        !translationComesFromAsr && transProvider == TransProvider.NONE ->
            "当前识别服务不自带翻译，请在「翻译」里选一个服务商"
        transProvider == TransProvider.OPENAI_COMPAT && openaiTransKey.isBlank() ->
            "请填写翻译用的 API Key"
        transProvider == TransProvider.DEEPL && deeplKey.isBlank() -> "请填写 DeepL 的 Key"
        transProvider == TransProvider.GOOGLE && googleKey.isBlank() -> "请填写 Google 翻译的 Key"
        else -> null
    }

    val backgroundArgb: Int
        get() = (backgroundColor and 0x00FFFFFF) or
                (((backgroundAlpha.coerceIn(0, 100) * 255) / 100) shl 24)
}
