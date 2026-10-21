package com.example.myapp

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import okhttp3.Call

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var client: AiClient

    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: TextView
    private lateinit var tlsHint: TextView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var themeToggle: ThemeToggleView

    // 顶/底栏的高斯模糊
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private var topBlurBitmap: Bitmap? = null
    private var bottomBlurBitmap: Bitmap? = null
    private var blurPending = false

    // 自定义背景
    private lateinit var bgImage: android.widget.ImageView
    private lateinit var bgDim: View
    private var bgSource: Bitmap? = null
    private var bgSourcePath: String? = null
    private var bgApplyPending = false
    private var bgPreview: android.widget.ImageView? = null
    private var bgPreviewTint: View? = null

    private val history = ArrayList<ChatMsg>()
    private val ui = Handler(Looper.getMainLooper())
    private var currentCall: Call? = null
    private var streaming = false
    private var streamed = StringBuilder()

    // 流式渲染用的共享状态：后台线程只追加文本，主线程按节拍重绘
    private val bufferLock = Any()
    @Volatile private var renderScheduled = false
    private var streamingBubble: TextView? = null
    private var autoFollow = true
    /** 流式重绘间隔；压测时可用 intent 参数关掉，模拟"每个 token 都重绘" */
    private var renderDelayMs = 90L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        client = AiClient(prefs)

        messages = findViewById(R.id.messagesContainer)
        scroll = findViewById(R.id.scrollView)
        input = findViewById(R.id.inputText)
        sendButton = findViewById(R.id.sendButton)
        emptyState = findViewById(R.id.emptyState)
        emptyTitle = findViewById(R.id.emptyTitle)
        tlsHint = findViewById(R.id.tlsHint)

        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        bgImage = findViewById(R.id.bgImage)
        bgDim = findViewById(R.id.bgDim)
        applyBackground(immediate = true)
        scroll.post { scheduleBlur() }
        bottomBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scheduleBlur() }

        // 用户翻上去看历史时，停止自动跟随
        scroll.viewTreeObserver.addOnScrollChangedListener {
            autoFollow = !scroll.canScrollVertically(1)
            scheduleBlur()
        }
        scroll.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                autoFollow = !scroll.canScrollVertically(1)
            }
            false
        }

        history.add(ChatMsg("system", "你是一个简洁、直接的中文助手。回答尽量短。"))

        // 深色模式切换：切完 Activity 会重建，历史由 onSaveInstanceState 接住
        themeToggle = findViewById(R.id.themeToggle)
        themeToggle.dark = prefs.darkMode
        themeToggle.setOnClickListener {
            val next = !prefs.darkMode
            prefs.darkMode = next
            // 滑块先滑到位再重建，否则动画来不及显示
            themeToggle.animateTo(next) {
                AppCompatDelegate.setDefaultNightMode(
                    if (next) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        restoreHistory(savedInstanceState)

        sendButton.setOnClickListener { send() }
        findViewById<TextView>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<View>(R.id.attachButton).setOnClickListener { pickAttachment() }

        if (intent.getBooleanExtra("noThrottle", false)) renderDelayMs = 0L

        if (!prefs.isConfigured) {
            emptyTitle.text = "先点右上角「设置」填 API Key"
            showSettings()
        }

        if (intent.getBooleanExtra("selftest", false)) {
            runSelfTest(intent.getBooleanExtra("stream", false))
        }

        // 预填并自动发送：am start ... --es autoSend "你好"
        val autoSend = intent.getStringExtra("autoSend")
        if (!autoSend.isNullOrEmpty()) {
            input.setText(autoSend)
            ui.postDelayed({
                if (!isFinishing) send()
            }, 800)
        }

        // 调试：用内置鲸鱼图当背景，验证背景渲染链路（--ez bgFromLogo true）
        if (intent.getBooleanExtra("bgFromLogo", false)) {
            Thread {
                try {
                    // 画一张彩色测试图：透明 PNG 转 JPEG 会变白底，看不出效果
                    val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    pt.shader = android.graphics.LinearGradient(
                        0f, 0f, 720f, 1280f,
                        intArrayOf(0xFF1E3A8A.toInt(), 0xFF7C3AED.toInt(), 0xFFF59E0B.toInt(), 0xFF059669.toInt()),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                    cv.drawRect(0f, 0f, 720f, 1280f, pt)
                    pt.shader = null
                    pt.color = 0x66FFFFFF
                    var ci = 0
                    while (ci < 12) {
                        cv.drawCircle((60 + ci * 57).toFloat(), (120 + ci * 93 % 1100).toFloat(), 70f + ci * 4, pt)
                        ci++
                    }
                    val file = java.io.File(filesDir, "bg.jpg")
                    Log.i(TAG, "bgFromLogo decoded=${bmp.width}x${bmp.height}")
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    ui.post {
                        prefs.backgroundPath = file.absolutePath
                        if (intent.hasExtra("bgBlur")) prefs.backgroundBlur = intent.getIntExtra("bgBlur", 0)
                        if (intent.hasExtra("bgDim")) prefs.backgroundDim = intent.getIntExtra("bgDim", 0)
                        bgSource = null
                        bgSourcePath = null
                        bgImage.tag = null
                        applyBackground(immediate = true)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "bgFromLogo failed", t)
                }
            }.start()
        }
        // 调试：把内置 logo 当图片发出去，验证多模态链路（--ez sendLogo true）
        if (intent.getBooleanExtra("sendLogo", false)) {
            Thread {
                try {
                    // 画一张彩色测试图：透明 PNG 转 JPEG 会变白底，看不出效果
                    val bmp = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(bmp)
                    val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    pt.shader = android.graphics.LinearGradient(
                        0f, 0f, 720f, 1280f,
                        intArrayOf(0xFF1E3A8A.toInt(), 0xFF7C3AED.toInt(), 0xFFF59E0B.toInt(), 0xFF059669.toInt()),
                        null, android.graphics.Shader.TileMode.CLAMP
                    )
                    cv.drawRect(0f, 0f, 720f, 1280f, pt)
                    pt.shader = null
                    pt.color = 0x66FFFFFF
                    var ci = 0
                    while (ci < 12) {
                        cv.drawCircle((60 + ci * 57).toFloat(), (120 + ci * 93 % 1100).toFloat(), 70f + ci * 4, pt)
                        ci++
                    }
                    val out = ByteArrayOutputStream()
                    scaleDown(bmp, 1024).compress(Bitmap.CompressFormat.JPEG, 80, out)
                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    Log.i(TAG, "logo image base64=${b64.length}")
                    ui.post { dispatch("这是什么图标？", b64, "[图片] 这是什么图标？") }
                } catch (t: Throwable) {
                    Log.e(TAG, "sendLogo failed", t)
                }
            }.start()
        }
    }

    /** 供 adb 触发：am start -n com.example.myapp/.MainActivity --ez selftest true [--ez stream true] */
    private fun runSelfTest(stream: Boolean) {
        Log.i(TAG, "selftest start, sdk=${android.os.Build.VERSION.SDK_INT}")
        Thread {
            try {
                Tls.install()
                Log.i(TAG, "TLS provider = ${Tls.providerName}")
            } catch (t: Throwable) {
                Log.e(TAG, "TLS install failed", t)
                return@Thread
            }

            if (stream) {
                val collected = StringBuilder()
                val msgs = listOf(ChatMsg("user", "hi"))
                Log.i(TAG, "stream test -> ${prefs.baseUrl}")
                client.chat(
                    msgs,
                    onDelta = { piece ->
                        collected.append(piece)
                        Log.i(TAG, "delta[${collected.length}]: $piece")
                    },
                    onError = { err -> Log.e(TAG, "stream error: $err") },
                    onDone = { Log.i(TAG, "stream done, total=${collected.length} : $collected") }
                )
                return@Thread
            }
            val url = prefs.baseUrl.trimEnd('/') + "/chat/completions"
            val body = okhttp3.RequestBody.create(
                okhttp3.MediaType.parse("application/json"),
                "{\"model\":\"${prefs.model}\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}"
            )
            val req = okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + prefs.apiKey)
                .post(body)
                .build()
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .sslSocketFactory(Tls.socketFactory(), Tls.trustManager())
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body()?.string().orEmpty()
                    Log.i(TAG, "HTTP ${resp.code()} body=${text.take(300)}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "request failed: ${t.javaClass.name}: ${t.message}", t)
            }
        }.start()
    }

    private fun send() {
        if (streaming) return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        Log.i(TAG, "send: ${text.take(40)}")

        if (!prefs.isConfigured) {
            showSettings()
            return
        }

        input.setText("")
        dispatch(text, null, text)
    }

    /** 文字和图片共用一条发送链路 */
    private fun dispatch(userText: String, imageBase64: String?, bubbleText: String) {
        addBubble(bubbleText, isUser = true)
        history.add(ChatMsg("user", userText, imageBase64))

        val bubble = addBubble("…", isUser = false)
        bubble.setTextIsSelectable(false)
        synchronized(bufferLock) { streamed.setLength(0) }
        streamingBubble = bubble
        renderScheduled = false
        streaming = true
        setSending(true)
        var gotContent = false

        Thread {
            try {
                currentCall = client.chat(
                    history,
                    onDelta = { piece ->
                        // 追加在后台线程做，主线程只负责按节奏重绘
                        synchronized(bufferLock) { streamed.append(piece) }
                        gotContent = true
                        scheduleRender()
                    },
                    onError = { err ->
                        ui.post {
                            renderScheduled = false
                            streamingBubble = null
                            bubble.text = err
                            bubble.setBackgroundResource(R.drawable.bg_error)
                            bubble.setTextColor(ContextCompat.getColor(this, R.color.error_text))
                            streaming = false
                            setSending(false)
                            refreshTlsHint()
                            followIfAtBottom()
                        }
                    },
                    onDone = {
                        ui.post {
                            renderScheduled = false
                            streamingBubble = null
                            val finalText = synchronized(bufferLock) { streamed.toString() }
                            if (finalText.isNotEmpty()) {
                                history.add(ChatMsg("assistant", finalText))
                                bubble.text = formatMarkdown(finalText)
                                bubble.setTextIsSelectable(true)
                            }
                            streaming = false
                            setSending(false)
                            refreshTlsHint()
                            followIfAtBottom()
                        }
                    },
                    onReasoning = {
                        // 思考阶段没有正文，给个占位就够了，不必每块都重绘
                        if (!gotContent && streamingBubble != null) {
                            ui.post { if (!gotContent) bubble.text = "思考中…" }
                        }
                    }
                )
            } catch (t: Throwable) {
                ui.post {
                    renderScheduled = false
                    streamingBubble = null
                    bubble.text = "初始化失败：${t.message}"
                    bubble.setBackgroundResource(R.drawable.bg_error)
                    bubble.setTextColor(ContextCompat.getColor(this, R.color.error_text))
                    streaming = false
                    setSending(false)
                }
            }
        }.start()
    }

    /**
     * 流式重绘节流：每 ~90ms 最多刷一次。
     * 4.2 的机器上，每个 token 都 setText 会触发整条消息列表重新测量布局，直接掉帧。
     */
    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        ui.postDelayed({
            renderScheduled = false
            val tv = streamingBubble ?: return@postDelayed
            val text = synchronized(bufferLock) { streamed.toString() }
            if (text.isEmpty()) return@postDelayed
            tv.text = formatMarkdown(text + "▍")
            followIfAtBottom()
        }, renderDelayMs)
    }

    private fun refreshTlsHint() {
        tlsHint.text = "TLS: ${Tls.providerName}"
    }

    private fun addBubble(text: String, isUser: Boolean): TextView {
        emptyState.visibility = View.GONE

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = if (isUser) Gravity.END else Gravity.TOP
        val rowLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rowLp.topMargin = dp(10)
        row.layoutParams = rowLp

        if (!isUser) row.addView(makeAvatar())

        val tv = TextView(this)
        tv.text = if (isUser) text else formatMarkdown(text)
        tv.textSize = 15f
        tv.setLineSpacing(dp(2).toFloat(), 1f)
        tv.setTextColor(
            if (isUser) 0xFFFFFFFF.toInt()
            else ContextCompat.getColor(this, R.color.text_primary)
        )
        tv.setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_ai)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        tv.setTextIsSelectable(!isUser)

        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.74f).toInt()

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        row.addView(tv, lp)

        messages.addView(row)
        scrollDown()
        scheduleBlur()
        return tv
    }

    private fun makeAvatar(): TextView {
        val av = TextView(this)
        av.text = "AI"
        av.textSize = 11f
        av.gravity = Gravity.CENTER
        av.setIncludeFontPadding(false)
        av.setTextColor(ContextCompat.getColor(this, R.color.primary))
        av.setBackgroundResource(R.drawable.bg_avatar)
        val lp = LinearLayout.LayoutParams(dp(30), dp(30))
        lp.rightMargin = dp(8)
        lp.topMargin = dp(2)
        av.layoutParams = lp
        return av
    }

    private fun setSending(on: Boolean) {
        sendButton.isEnabled = !on
        sendButton.alpha = if (on) 0.45f else 1f
    }

    /**
     * 极简 Markdown：**粗体**、`代码`、行首 "- " 换子弹点。
     * 模型经常回 Markdown，原样显示满屏星号；完整渲染器（Markwon）要 API 16+，这里自己搓。
     */
    private fun formatMarkdown(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < raw.length) {
            if (raw.startsWith("**", i)) {
                val end = raw.indexOf("**", i + 2)
                if (end > i + 2) {
                    val s = sb.length
                    sb.append(raw, i + 2, end)
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 2
                    continue
                }
            }
            if (raw[i] == '`') {
                val end = raw.indexOf('`', i + 1)
                if (end > i + 1) {
                    val s = sb.length
                    sb.append(raw, i + 1, end)
                    sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = end + 1
                    continue
                }
            }
            if (raw[i] == '-' && (i == 0 || raw[i - 1] == '\n') && i + 1 < raw.length && raw[i + 1] == ' ') {
                sb.append('•')
                i += 1
                continue
            }
            sb.append(raw[i])
            i++
        }
        return sb
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 只有用户本来就停在底部时才跟着滚，翻上去看历史时不要抢 */
    private fun followIfAtBottom() {
        if (autoFollow) scrollDown()
    }

    private fun showSettings() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(16), dp(8), dp(16), dp(8))

        val urlInput = EditText(this).apply {
            setText(prefs.baseUrl)
            hint = "接口地址"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val keyInput = EditText(this).apply {
            setText(prefs.apiKey)
            hint = "API Key"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelInput = EditText(this).apply {
            setText(prefs.model)
            hint = "模型名"
            setSingleLine()
        }

        container.addView(label("接口地址（OpenAI 兼容，结尾到 /v1）"))
        container.addView(urlInput)
        container.addView(label("API Key"))
        container.addView(keyInput)
        container.addView(label("模型"))
        container.addView(modelInput)

        val fetchButton = Button(this)
        fetchButton.text = "从服务端拉取模型列表"
        fetchButton.textSize = 13f
        container.addView(fetchButton)

        fetchButton.setOnClickListener {
            fetchButton.isEnabled = false
            fetchButton.text = "拉取中…"
            Thread {
                client.listModels(
                    onOk = { names ->
                        ui.post {
                            fetchButton.isEnabled = true
                            fetchButton.text = "从服务端拉取模型列表"
                            pickModel(names, modelInput)
                        }
                    },
                    onErr = { err ->
                        ui.post {
                            fetchButton.isEnabled = true
                            fetchButton.text = "从服务端拉取模型列表"
                            Toast.makeText(this, err.take(200), Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }.start()
        }

        container.addView(label("当前 TLS 提供方：${Tls.providerName}"))

        val thinkingBox = CheckBox(this)
        thinkingBox.text = "思考模式（更准，但首字慢、更贵）"
        thinkingBox.textSize = 13f
        thinkingBox.isChecked = prefs.thinking
        container.addView(thinkingBox)

        // ----- 背景 -----
        val previewBox = FrameLayout(this)
        val preview = android.widget.ImageView(this)
        preview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        preview.setImageDrawable(bgImage.drawable)
        val previewTint = View(this)
        previewBox.addView(preview, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        previewBox.addView(previewTint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        container.addView(previewBox)
        previewBox.visibility = if (prefs.backgroundPath.isEmpty()) View.GONE else View.VISIBLE
        bgPreview = preview
        bgPreviewTint = previewTint
        prefs.backgroundDim.let { d0 ->
            previewTint.setBackgroundColor(
                if (d0 < 0) android.graphics.Color.argb(minOf(215, -d0 * 2), 0, 0, 0)
                else android.graphics.Color.argb(minOf(215, d0 * 2), 255, 255, 255)
            )
        }

        container.addView(label("背景图片"))
        val bgRow = LinearLayout(this)
        bgRow.orientation = LinearLayout.HORIZONTAL
        val pickBg = Button(this).apply { text = "选择图片"; textSize = 13f }
        val clearBg = Button(this).apply { text = "清除"; textSize = 13f }
        bgRow.addView(pickBg)
        bgRow.addView(clearBg)
        container.addView(bgRow)
        pickBg.setOnClickListener { pickBackground() }
        clearBg.setOnClickListener {
            prefs.backgroundPath = ""
            bgSource = null
            bgSourcePath = null
            bgImage.tag = null
            applyBackground(immediate = true)
            Toast.makeText(this, "已清除背景", Toast.LENGTH_SHORT).show()
        }

        val blurLabel = label("模糊 ${prefs.backgroundBlur}")
        container.addView(blurLabel)
        val blurBar = SeekBar(this)
        blurBar.max = 100
        blurBar.progress = prefs.backgroundBlur
        blurBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                blurLabel.text = "模糊 $value"
                if (fromUser) {
                    prefs.backgroundBlur = value
                    applyBackground()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        container.addView(blurBar)

        val dimLabel = label("明暗 ${prefs.backgroundDim}")
        container.addView(dimLabel)
        val dimBar = SeekBar(this)
        dimBar.max = 200
        dimBar.progress = prefs.backgroundDim + 100
        dimBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                val v = value - 100
                dimLabel.text = "明暗 $v"
                if (fromUser) {
                    prefs.backgroundDim = v
                    applyBackground()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        container.addView(dimBar)

        val scroller = ScrollView(this)
        scroller.addView(container)
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(scroller)
            .setPositiveButton("保存") { _, _ ->
                prefs.baseUrl = urlInput.text.toString().ifBlank { Prefs.DEFAULT_BASE_URL }
                prefs.apiKey = keyInput.text.toString()
                prefs.model = modelInput.text.toString().ifBlank { Prefs.DEFAULT_MODEL }
                prefs.thinking = thinkingBox.isChecked
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { bgPreview = null; bgPreviewTint = null }
            .show()
    }

    private fun pickModel(names: List<String>, target: EditText) {
        if (names.isEmpty()) {
            Toast.makeText(this, "服务端没有返回模型", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(names.toTypedArray()) { _, which -> target.setText(names[which]) }
            .setNegativeButton("取消", null)
            .setOnDismissListener { bgPreview = null; bgPreviewTint = null }
            .show()
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(0xFF757575.toInt())
        setPadding(0, dp(8), 0, dp(2))
    }

    // ---------- 附件 ----------

    private fun pickAttachment() {
        val items = arrayOf(getString(R.string.attach_image), getString(R.string.attach_doc))
        AlertDialog.Builder(this)
            .setTitle(R.string.attach_pick)
            .setItems(items) { _, which ->
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                if (which == 0) {
                    intent.type = "image/*"
                } else {
                    intent.type = "text/*"
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/xml"))
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, items[which]), REQ_PICK)
                } catch (t: Throwable) {
                    Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { bgPreview = null; bgPreviewTint = null }
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val picked = data?.data ?: return
        if (requestCode == REQ_BG) {
            handleBackground(picked)
            return
        }
        if (requestCode != REQ_PICK) return
        val uri = picked
        val mime = contentResolver.getType(uri).orEmpty()
        if (mime.startsWith("image/")) handleImage(uri) else handleDoc(uri)
    }

    private fun handleImage(uri: Uri) {
        Toast.makeText(this, "正在处理图片…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("无法解码图片")
                val scaled = scaleDown(bitmap, 1024)
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                Log.i(TAG, "image ready: ${scaled.width}x${scaled.height}, base64=${b64.length}")

                val caption = input.text.toString().trim()
                ui.post {
                    input.setText("")
                    dispatch(
                        userText = if (caption.isBlank()) getString(R.string.image_prompt) else caption,
                        imageBase64 = b64,
                        bubbleText = "[图片] " + if (caption.isBlank()) "" else " · $caption"
                    )
                }
            } catch (t: Throwable) {
                ui.post { Toast.makeText(this, "读取图片失败：${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun handleDoc(uri: Uri) {
        Thread {
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("无法读取文件")
                val trimmed = if (text.length > 20000) text.take(20000) + "\n…（已截断）" else text
                ui.post {
                    input.setText("【$name】\n$trimmed")
                    input.setSelection(input.text.length)
                    Toast.makeText(this, "已读入 ${trimmed.length} 字符", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                ui.post {
                    Toast.makeText(
                        this,
                        "读不了这个文件：${t.message}\n（PDF/Word 暂不支持）",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun scaleDown(src: Bitmap, max: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= max) return src
        val ratio = max.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, (src.width * ratio).toInt()),
            maxOf(1, (src.height * ratio).toInt()),
            true
        )
    }

    /**
     * 顶/底栏模糊。思路：把栏底下那条内容按 1/8 采样画进小位图，做盒式模糊再放大显示。
     * 节流 120ms，滚动时也不会把主线程压死。
     */
    private fun scheduleBlur() {
        if (blurPending) return
        blurPending = true
        ui.postDelayed({
            blurPending = false
            updateBars()
        }, 120)
    }

    private fun updateBars() {
        if (scroll.width == 0 || scroll.height == 0) return

        if (topBar.height > 0) {
            val target = topBlurBitmap?.takeIf { it.height == maxOf(1, topBar.height / BLUR_SCALE) }
                ?: Blur.createTarget(scroll, topBar.height, BLUR_SCALE).also { topBlurBitmap = it }
            Blur.captureInto(scroll, 0, topBar.height, BLUR_SCALE, BLUR_RADIUS, target)
            androidx.core.view.ViewCompat.setBackground(topBar, glassLayer(target))
        }

        if (bottomBar.height > 0) {
            val target = bottomBlurBitmap?.takeIf { it.height == maxOf(1, bottomBar.height / BLUR_SCALE) }
                ?: Blur.createTarget(scroll, bottomBar.height, BLUR_SCALE).also { bottomBlurBitmap = it }
            Blur.captureInto(
                scroll, scroll.height - bottomBar.height, bottomBar.height,
                BLUR_SCALE, BLUR_RADIUS, target
            )
            androidx.core.view.ViewCompat.setBackground(bottomBar, glassLayer(target))
        }
    }


    /** 模糊位图 + 半透明蒙层，用 LayerDrawable 叠在一起当背景 */
    private fun glassLayer(bmp: Bitmap): android.graphics.drawable.LayerDrawable {
        val blurLayer = android.graphics.drawable.BitmapDrawable(resources, bmp)
        val tintLayer = android.graphics.drawable.ColorDrawable(
            ContextCompat.getColor(this, R.color.surface_glass)
        )
        return android.graphics.drawable.LayerDrawable(arrayOf(blurLayer, tintLayer))
    }
    // ---------- 自定义背景 ----------

    private fun pickBackground() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        try {
            startActivityForResult(Intent.createChooser(intent, "选择背景"), REQ_BG)
        } catch (t: Throwable) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleBackground(uri: Uri) {
        Toast.makeText(this, "正在处理背景…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val src = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("无法解码图片")
                val scaled = scaleDown(src, 1600)
                val file = java.io.File(filesDir, "bg.jpg")
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                ui.post {
                    prefs.backgroundPath = file.absolutePath
                    if (intent.hasExtra("bgBlur")) prefs.backgroundBlur = intent.getIntExtra("bgBlur", 0)
                    if (intent.hasExtra("bgDim")) prefs.backgroundDim = intent.getIntExtra("bgDim", 0)
                    bgSource = null
                    bgSourcePath = null
                    bgImage.tag = null
                    applyBackground(immediate = true)
                    Toast.makeText(this, "背景已设置", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                ui.post { Toast.makeText(this, "设置背景失败：${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /**
     * 应用背景。滑块拖动时走防抖，避免每次 onProgressChanged 都重算模糊。
     */
    private fun applyBackground(immediate: Boolean = false) {
        val path = prefs.backgroundPath
        if (path.isEmpty() || !java.io.File(path).exists()) {
            bgImage.visibility = View.GONE
            bgImage.setImageDrawable(null)
            bgDim.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            return
        }
        bgImage.visibility = View.VISIBLE

        val dim = prefs.backgroundDim
        val overlay = if (dim < 0) android.graphics.Color.argb(minOf(215, -dim * 2), 0, 0, 0)
                      else android.graphics.Color.argb(minOf(215, dim * 2), 255, 255, 255)
        bgDim.setBackgroundColor(overlay)
        bgPreviewTint?.setBackgroundColor(overlay)

        if (bgApplyPending && !immediate) return
        bgApplyPending = true
        ui.postDelayed({
            bgApplyPending = false
            val blurAmount = prefs.backgroundBlur
            val token = path + "#" + blurAmount
            if (bgImage.tag == token) return@postDelayed
            bgImage.tag = token
            Thread {
                try {
                    val src = loadBackgroundSource(path)
                    Log.i(TAG, "bg path=$path src=${src.width}x${src.height}")
                    val out = if (blurAmount > 0) {
                        Blur.blurForDisplay(src, blurAmount)
                    } else {
                        val ratio = minOf(1f, 1080f / maxOf(src.width, src.height))
                        Bitmap.createScaledBitmap(
                            src,
                            maxOf(1, (src.width * ratio).toInt()),
                            maxOf(1, (src.height * ratio).toInt()),
                            true
                        )
                    }
                    ui.post { if (bgImage.tag == token) bgImage.setImageBitmap(out); bgPreview?.setImageBitmap(out) }
                } catch (t: Throwable) {
                    Log.e(TAG, "apply background failed", t)
                }
            }.start()
        }, if (immediate) 0L else 150L)
    }

    private fun loadBackgroundSource(path: String): Bitmap {
        val cached = bgSource
        if (cached != null && bgSourcePath == path && !cached.isRecycled) return cached

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1080) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("背景读取失败")
        bgSource = bmp
        bgSourcePath = path
        return bmp
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
    private fun restoreHistory(state: Bundle?) {
        val flat = state?.getStringArrayList(KEY_HISTORY) ?: return
        var i = 0
        while (i + 1 < flat.size) {
            val role = flat[i]
            val content = flat[i + 1]
            i += 2
            if (role == "system") continue
            history.add(ChatMsg(role, content))
            addBubble(content, isUser = role == "user")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val flat = ArrayList<String>()
        for (m in history) {
            if (m.role == "system") continue
            flat.add(m.role)
            flat.add(m.content)
        }
        outState.putStringArrayList(KEY_HISTORY, flat)
    }

    companion object {
        private const val TAG = "OldAI"
        private const val KEY_HISTORY = "history"
        private const val REQ_PICK = 1001
        private const val REQ_BG = 1002
        private const val BLUR_SCALE = 8
        private const val BLUR_RADIUS = 6
    }

    override fun onDestroy() {
        currentCall?.cancel()
        super.onDestroy()
    }
}





















