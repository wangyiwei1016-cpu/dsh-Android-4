package com.example.myapp

import android.content.Context

/** 简单的配置存取：接口地址 / API Key / 模型名 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = sp.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = sp.edit().putString(KEY_MODEL, value.trim()).apply()

    /** 思考模式：默认关闭。开着更准，但首字延迟高、token 也更贵，老机器上体验差距明显 */
    var thinking: Boolean
        get() = sp.getBoolean(KEY_THINKING, false)
        set(value) = sp.edit().putBoolean(KEY_THINKING, value).apply()

    /** 深色模式 */
    var darkMode: Boolean
        get() = sp.getBoolean(KEY_DARK, false)
        set(value) = sp.edit().putBoolean(KEY_DARK, value).apply()

    /** 自定义背景图在私有目录里的路径，空表示用纯色 */
    var backgroundPath: String
        get() = sp.getString(KEY_BG_PATH, "") ?: ""
        set(value) = sp.edit().putString(KEY_BG_PATH, value).apply()

    /** 背景模糊强度 0..100 */
    var backgroundBlur: Int
        get() = sp.getInt(KEY_BG_BLUR, 0)
        set(value) = sp.edit().putInt(KEY_BG_BLUR, value).apply()

    /** 背景明暗 -100..100，负数变暗，正数提亮 */
    var backgroundDim: Int
        get() = sp.getInt(KEY_BG_DIM, 0)
        set(value) = sp.edit().putInt(KEY_BG_DIM, value).apply()

    val isConfigured: Boolean get() = apiKey.isNotEmpty()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-flash"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_THINKING = "thinking"
        private const val KEY_DARK = "dark_mode"
        private const val KEY_BG_PATH = "bg_path"
        private const val KEY_BG_BLUR = "bg_blur"
        private const val KEY_BG_DIM = "bg_dim"
    }
}

