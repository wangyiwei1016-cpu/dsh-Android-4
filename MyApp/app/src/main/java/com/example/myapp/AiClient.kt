package com.example.myapp

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * @param imageBase64 有值时按多模态发（OpenAI 的 image_url + data URL），只支持 user 角色
 */
data class ChatMsg(val role: String, var content: String, val imageBase64: String? = null)

/**
 * 走 OpenAI 兼容的 /chat/completions 流式接口。
 * DeepSeek、通义、智谱、Ollama、vLLM 等都是这个形状，所以地址和模型可以自己填。
 */
class AiClient(private val prefs: Prefs) {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(Tls.socketFactory(), Tls.trustManager())
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun chat(
        history: List<ChatMsg>,
        onDelta: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
        onReasoning: (String) -> Unit = {}
    ): Call {
        // 首次调用会解析内置根证书，耗时几十毫秒到 1 秒，所以 chat() 必须在后台线程调用
        Tls.install()

        val payload = JSONObject().apply {
            put("model", prefs.model)
            put("stream", true)
            // thinking 是 DeepSeek 的扩展字段，其它 OpenAI 兼容服务可能不认识，只在 DeepSeek 上发
            if (prefs.baseUrl.contains("deepseek", ignoreCase = true)) {
                put("thinking", JSONObject().put("type", if (prefs.thinking) "enabled" else "disabled"))
            }
            put("messages", JSONArray().apply {
                history.forEach { m -> put(toJson(m)) }
            })
        }

        val url = prefs.baseUrl.trimEnd('/') + "/chat/completions"
        val body = RequestBody.create(JSON, payload.toString())
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer " + prefs.apiKey)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = http.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(describe(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val payloadBody = resp.body()
                    if (!resp.isSuccessful) {
                        val text = try {
                            payloadBody?.string().orEmpty()
                        } catch (e: Exception) {
                            ""
                        }
                        onError("HTTP ${resp.code()} ${resp.message()}\n${text.take(400)}")
                        return
                    }
                    if (payloadBody == null) {
                        onError("响应为空")
                        return
                    }
                    try {
                        readStream(payloadBody.source(), onDelta, onReasoning)
                        onDone()
                    } catch (e: Exception) {
                        onError(describe(e))
                    }
                }
            }
        })
        return call
    }

    /** GET /models，用来把可用的模型名列出来，避免模型改名后还得改代码 */
    fun listModels(onOk: (List<String>) -> Unit, onErr: (String) -> Unit) {
        Tls.install()
        val request = Request.Builder()
            .url(prefs.baseUrl.trimEnd('/') + "/models")
            .addHeader("Authorization", "Bearer " + prefs.apiKey)
            .get()
            .build()

        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onErr(describe(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val text = try {
                            resp.body()?.string().orEmpty()
                        } catch (e: Exception) {
                            ""
                        }
                        onErr("HTTP ${resp.code()} ${resp.message()}\n${text.take(300)}")
                        return
                    }
                    try {
                        val body = resp.body()?.string().orEmpty()
                        val arr = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        val names = ArrayList<String>()
                        for (i in 0 until arr.length()) {
                            val id = arr.getJSONObject(i).optString("id")
                            if (id.isNotEmpty()) names.add(id)
                        }
                        onOk(names)
                    } catch (e: Exception) {
                        onErr("解析模型列表失败：${e.message}")
                    }
                }
            }
        })
    }

    private fun toJson(m: ChatMsg): JSONObject {
        val obj = JSONObject().apply { put("role", m.role) }
        val img = m.imageBase64
        if (img.isNullOrEmpty()) {
            obj.put("content", m.content)
        } else {
            val text = if (m.content.isBlank()) "请看这张图片" else m.content
            obj.put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", text)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + img))
                })
            })
        }
        return obj
    }

    private fun readStream(
        source: okio.BufferedSource,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit
    ) {
        while (true) {
            val raw = source.readUtf8Line() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!line.startsWith("data:")) continue

            val data = line.substring(5).trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue

            try {
                val json = JSONObject(data)
                val choices = json.optJSONArray("choices") ?: continue
                if (choices.length() == 0) continue
                val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue

                // 思考模式下先来的是 reasoning_content，正文在 content 里
                val reasoning = delta.optString("reasoning_content", "")
                if (reasoning.isNotEmpty()) onReasoning(reasoning)

                val piece = delta.optString("content", "")
                if (piece.isNotEmpty()) onDelta(piece)
            } catch (e: Exception) {
                // 单行解析失败不影响整体流
            }
        }
    }

    private fun describe(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("SSLHandshake", true) || msg.contains("handshake", true) ->
                "TLS 握手失败：$msg\n（服务端可能要求 TLS 1.2，检查当前 TLS 提供方）"
            msg.contains("UnknownHost", true) -> "域名解析失败：$msg"
            msg.contains("timeout", true) -> "连接超时：$msg"
            else -> msg
        }
    }

    companion object {
        private val JSON: MediaType? = MediaType.parse("application/json; charset=utf-8")
    }
}
