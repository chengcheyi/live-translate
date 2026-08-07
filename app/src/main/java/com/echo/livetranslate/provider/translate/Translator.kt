package com.echo.livetranslate.provider.translate

import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.data.TransProvider

interface Translator {
    /** 返回译文；失败时抛异常，由调用方决定是否降级显示原文。 */
    suspend fun translate(text: String, targetLang: String): String

    companion object {
        fun from(s: Settings): Translator? = when (s.transProvider) {
            TransProvider.NONE -> null
            TransProvider.OPENAI_COMPAT -> OpenAiCompatTranslator(s)
            TransProvider.DEEPL -> DeepLTranslator(s)
            TransProvider.GOOGLE -> GoogleTranslator(s)
        }
    }
}
