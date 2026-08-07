package com.echo.livetranslate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echo.livetranslate.data.AsrProvider
import com.echo.livetranslate.data.EngineMode
import com.echo.livetranslate.data.OcrScript
import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.TransProvider

private val TARGET_LANGS = listOf(
    "zh-Hans" to "简体中文",
    "zh-Hant" to "繁體中文",
    "en" to "English",
    "ja" to "日本語",
    "ko" to "한국어",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "ru" to "Русский"
)

@Composable
fun SettingsScreen(
    s: Settings,
    running: Boolean,
    hasOverlay: Boolean,
    onChange: (Settings) -> Unit,
    onRequestOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenHistory: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        SubtitlePreview(s)

        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasOverlay) {
                Button(onClick = onRequestOverlay, modifier = Modifier.weight(1f)) {
                    Text("① 授予悬浮窗权限")
                }
            } else if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("停止字幕") }
            } else {
                Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("开始字幕") }
            }
            OutlinedButton(onClick = onOpenHistory) { Text("历史") }
        }

        s.validate()?.let { warning ->
            Text(
                "⚠ $warning",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        Section("引擎", "音频模式听系统声音；遇到 DRM 视频（Netflix 等）抓不到声音时切 OCR") {
            Picker("模式", s.engineMode, EngineMode.entries, { it.label }) {
                onChange(s.copy(engineMode = it))
            }
        }

        if (s.engineMode == EngineMode.AUDIO) {
            AudioSection(s, onChange)
        } else {
            OcrSection(s, onChange)
        }

        TranslateSection(s, onChange)
        AppearanceSection(s, onChange)
    }
}

@Composable
private fun AudioSection(s: Settings, onChange: (Settings) -> Unit) {
    Section("语音识别", "决定延迟和准确度的关键。Azure 是识别+翻译一次返回，最快") {
        Picker("服务商", s.asrProvider, AsrProvider.entries, { it.label }) {
            onChange(s.copy(asrProvider = it))
        }

        when (s.asrProvider) {
            AsrProvider.AZURE -> {
                Field("Azure Speech Key", s.azureKey, secret = true) {
                    onChange(s.copy(azureKey = it))
                }
                Field("区域", s.azureRegion, hint = "如 eastasia、southeastasia、eastus") {
                    onChange(s.copy(azureRegion = it))
                }
                Field(
                    "源语言", s.azureSourceLangs,
                    hint = "逗号分隔。填多个会自动判断说的是哪种语言，但每多一种都会稍微增加一点延迟"
                ) { onChange(s.copy(azureSourceLangs = it)) }
            }

            AsrProvider.DEEPGRAM -> {
                Field("Deepgram API Key", s.deepgramKey, secret = true) {
                    onChange(s.copy(deepgramKey = it))
                }
                Field("接口地址", s.deepgramUrl) { onChange(s.copy(deepgramUrl = it)) }
                Field("模型", s.deepgramModel, hint = "nova-3 最准，nova-2 更便宜") {
                    onChange(s.copy(deepgramModel = it))
                }
                Field(
                    "语言", s.deepgramLanguage,
                    hint = "只能填一个代码，不要用逗号列多个。\n" +
                        "固定一种语言最准最快，例如 en / ja / ko；\n" +
                        "内容会在多种语言间切换时才填 multi"
                ) { onChange(s.copy(deepgramLanguage = it)) }
            }

            AsrProvider.OPENAI_REALTIME -> {
                Field("API Key", s.realtimeKey, secret = true) { onChange(s.copy(realtimeKey = it)) }
                Field("WebSocket 地址", s.realtimeUrl, hint = "换成中转/自建地址也可以") {
                    onChange(s.copy(realtimeUrl = it))
                }
                Field("转写模型", s.realtimeModel) { onChange(s.copy(realtimeModel = it)) }
                Field("语言", s.realtimeLanguage, hint = "留空=自动判断；填 en / ja 之类可提升准确度") {
                    onChange(s.copy(realtimeLanguage = it))
                }
            }
        }
    }
}

@Composable
private fun OcrSection(s: Settings, onChange: (Settings) -> Unit) {
    Section("OCR", "识别在手机本地完成，不走网络，只有翻译需要联网") {
        Picker("识别文字", s.ocrScript, OcrScript.entries, { it.label }) {
            onChange(s.copy(ocrScript = it))
        }
        SliderRow("扫描间隔", s.ocrIntervalMs, 300..2000, " ms") {
            onChange(s.copy(ocrIntervalMs = it))
        }
        SliderRow("扫描屏幕下方", s.ocrBottomPct, 10..60, "%") {
            onChange(s.copy(ocrBottomPct = it))
        }
    }
}

@Composable
private fun TranslateSection(s: Settings, onChange: (Settings) -> Unit) {
    Section(
        "翻译",
        if (s.translationComesFromAsr) "当前由 Azure 直接给出译文，无需再配。选一个服务商可以覆盖它"
        else "识别服务只出原文，需要在这里配一个翻译服务"
    ) {
        Picker("目标语言", s.targetLang, TARGET_LANGS.map { it.first },
            { code -> TARGET_LANGS.firstOrNull { it.first == code }?.second ?: code }) {
            onChange(s.copy(targetLang = it))
        }

        Picker("服务商", s.transProvider, TransProvider.entries, { it.label }) {
            onChange(s.copy(transProvider = it))
        }

        when (s.transProvider) {
            TransProvider.NONE -> Unit

            TransProvider.BAIDU -> {
                Field("APP ID", s.baiduAppId, hint = "fanyi-api.baidu.com 控制台，个人实名即可") {
                    onChange(s.copy(baiduAppId = it))
                }
                Field("密钥", s.baiduSecret, secret = true) { onChange(s.copy(baiduSecret = it)) }
                SliderRow("QPS 上限", s.baiduQps, 1..10, " 次/秒") {
                    onChange(s.copy(baiduQps = it))
                }
                Text(
                    "标准版填 1，个人实名认证后的高级版填 10。\n" +
                        "填得比账号实际额度高会一直报 54003；填 1 时只翻译定稿的整句，" +
                        "中文会在一句话说完后才出现。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TransProvider.OPENAI_COMPAT -> {
                Field(
                    "接口地址", s.openaiTransUrl,
                    hint = "DeepSeek: https://api.deepseek.com/chat/completions\n" +
                        "本地 Ollama: http://127.0.0.1:11434/v1/chat/completions"
                ) { onChange(s.copy(openaiTransUrl = it)) }
                Field("API Key", s.openaiTransKey, secret = true) {
                    onChange(s.copy(openaiTransKey = it))
                }
                Field(
                    "模型", s.openaiTransModel,
                    hint = "大模型首字延迟 1-3 秒，字幕会明显滞后。\n" +
                        "追求低延迟请改用上面的百度翻译"
                ) { onChange(s.copy(openaiTransModel = it)) }
            }

            TransProvider.DEEPL -> {
                Field("DeepL Key", s.deeplKey, secret = true) { onChange(s.copy(deeplKey = it)) }
                Toggle("使用免费版接口", s.deeplFreeTier, "Key 结尾带 :fx 的选这个") {
                    onChange(s.copy(deeplFreeTier = it))
                }
            }

            TransProvider.GOOGLE -> {
                Field("Google API Key", s.googleKey, secret = true) {
                    onChange(s.copy(googleKey = it))
                }
            }
        }
    }
}

@Composable
private fun AppearanceSection(s: Settings, onChange: (Settings) -> Unit) {
    Section("字幕外观") {
        Toggle("双语字幕", s.bilingual, "上面原文，下面译文") { onChange(s.copy(bilingual = it)) }
        Toggle("显示原文", s.showOriginal, "关掉就只剩译文一行") { onChange(s.copy(showOriginal = it)) }
        Toggle("文字描边", s.outlineText, "亮画面上也能看清，比加深背景更不挡视频") {
            onChange(s.copy(outlineText = it))
        }
        Toggle("锁定位置", s.locked, "锁定后触摸会穿透字幕，滑视频不受影响") {
            onChange(s.copy(locked = it))
        }

        SliderRow("字号", s.fontSizeSp, 12..36, " sp") { onChange(s.copy(fontSizeSp = it)) }
        SliderRow("每段最多行数", s.maxLines, 1..4, " 行") { onChange(s.copy(maxLines = it)) }
        SliderRow("宽度", s.overlayWidthPct, 40..100, "%") { onChange(s.copy(overlayWidthPct = it)) }
        SliderRow("背景不透明度", s.backgroundAlpha, 0..100, "%") {
            onChange(s.copy(backgroundAlpha = it))
        }

        ColorPicker("背景颜色", s.backgroundColor) { onChange(s.copy(backgroundColor = it)) }
        ColorPicker("原文颜色", s.originalColor) { onChange(s.copy(originalColor = it)) }
        ColorPicker("译文颜色", s.translatedColor) { onChange(s.copy(translatedColor = it)) }
    }
}

/** 所见即所得：改颜色和透明度不用反复开关服务去看效果。 */
@Composable
private fun SubtitlePreview(s: Settings) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(Color(0xFF1B2430), RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .fillMaxWidth(s.overlayWidthPct / 100f)
                .background(Color(s.backgroundArgb), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (s.showOriginal) {
                Text(
                    "This is what the subtitle looks like.",
                    color = Color(s.originalColor),
                    fontSize = s.fontSizeSp.sp,
                    textAlign = TextAlign.Center,
                    maxLines = s.maxLines
                )
            }
            if (s.bilingual || !s.showOriginal) {
                Text(
                    "字幕大概长这样。",
                    color = Color(s.translatedColor),
                    fontSize = s.fontSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = s.maxLines
                )
            }
        }
    }
}
