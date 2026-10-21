package com.example.myapp

import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDexApplication

class App : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()

        // 主题要在任何 Activity 创建之前定下来，否则会先画一帧亮色再重建
        val dark = Prefs(this).darkMode
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        // 只注册 context，证书库解析放到后台线程预热，避免拖慢冷启动
        Tls.init(this)
        Thread {
            try {
                Tls.install()
            } catch (ignored: Throwable) {
            }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }
}
