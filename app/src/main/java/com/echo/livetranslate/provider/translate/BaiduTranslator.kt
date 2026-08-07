package com.echo.livetranslate.provider.translate

import com.echo.livetranslate.data.Settings
import com.echo.livetranslate.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

/**
 * 百度翻译开放平台。专用机器翻译接口，一次往返通常 200-400ms，
 * 比大模型快一个数量级——字幕要的就是把一句话直译过来，用不上推理能力。
 *
 * 国内直连，个人实名即可，没有信用卡门槛。
 */
class BaiduTranslator(private val s: Settings) : Translator {

    override suspend fun translate(text: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            val appId = s.baiduAppId.trim()
            val secret = s.baiduSecret.trim()
            val salt = System.currentTimeMillis().toString()

            val form = FormBody.Builder()
                .add("q", text)
                .add("from", "auto")
                .add("to", toBaiduCode(targetLang))
                .add("appid", appId)
                .add("salt", salt)
                .add("sign", md5(appId + text + salt + secret))
                .build()

            val request = Request.Builder()
                .url("https://fanyi-api.baidu.com/api/trans/vip/translate")
                .post(form)
                .build()

            Http.restClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "百度翻译 ${resp.code}: ${body.take(200)}" }

                val json = JSONObject(body)
                // 百度用 HTTP 200 + error_code 表示失败，得单独判
                json.optString("error_code").takeIf { it.isNotEmpty() && it != "52000" }?.let { code ->
                    error("百度翻译 $code: ${json.optString("error_msg")}")
                }

                val results = json.optJSONArray("trans_result")
                    ?: error("百度翻译返回异常：${body.take(200)}")

                // 长句会被拆成多段返回，拼回去
                buildString {
                    for (i in 0 until results.length()) {
                        append(results.getJSONObject(i).optString("dst"))
                    }
                }.trim()
            }
        }

    /** 百度用的是自己一套语言代码，不是标准 BCP-47 */
    private fun toBaiduCode(lang: String): String = when (lang) {
        "zh-Hans" -> "zh"
        "zh-Hant" -> "cht"
        "ja" -> "jp"
        "ko" -> "kor"
        "fr" -> "fra"
        "es" -> "spa"
        else -> lang.substringBefore('-')
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }
}
