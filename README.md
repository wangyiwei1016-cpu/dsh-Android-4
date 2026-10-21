# dsh — 给 Android 4.x 老手机用的 AI 聊天客户端

minSdk **14（Android 4.0）**，纯 Kotlin/Java，可在 2013 年的机器上跑。界面是原生 View（老系统用不了 Compose），网络走 OkHttp 3.12 + Conscrypt，兼容任何 OpenAI 格式的接口。

## 界面

`app-dsh.png` / `light-mode.png` / `dark-mode.png` 都是红米 1 上的实拍。

Material 风格但**没有用 Material Components 库**（`com.google.android.material` 要求 API 19+），全部手写：

- 白色顶栏 + 1dp 分隔线，标题 18sp 粗体，顶栏右侧是**深色模式开关**和弱化的「设置」
- 用户气泡：`#2563EB` 实心，右下角收窄成 6dp 形成"尖角"
- AI 气泡：白底 + 1dp 描边，左上角收窄，配 30dp 圆形 AI 头像
- 输入区：胶囊输入框（22dp 圆角 + 描边），右侧 44dp 圆形发送键
- 空状态：居中鲸鱼 logo + 引导语 + TLS 状态
- 流式输出时末尾带 `▍` 光标，结束时去掉

老系统上的取舍：没有 ripple（API 21+）、没有 elevation 阴影（用 1dp 描边替代）。

踩过的坑：发送键的圆形看着被切掉一块，原因是父容器默认 `clipChildren="true"`，圆形背景上下各被裁了 2px。给底栏和输入行加 `android:clipChildren="false"` 后恢复正常——实测边框 88×88（44dp @2x），顶部每行宽度平滑递增，是正圆。

## 深色模式

- 主题用 `Theme.AppCompat.DayNight.NoActionBar`，配色走 `values/colors.xml` + `values-night/colors.xml` 两套
- 顶栏右侧是自绘的**阴阳图标**（`YinYangView`），点击后转半圈再切主题
- 切换调 `AppCompatDelegate.setDefaultNightMode()`，Activity 自动重建
- 选择存进 SharedPreferences，下次启动在 `Application.onCreate` 里先应用，避免先画一帧亮色再闪成深色
- **切换不会丢对话**：`onSaveInstanceState` 把历史拍平成 `ArrayList<String>`，重建后重放气泡。这个机制同时修好了横竖屏旋转丢消息的老问题

### 明暗开关怎么画的

`ThemeToggleView` 用 Canvas 现画，不走图片资源：

- 胶囊底用 `bg` 色 + 1.2dp 描边，衬在白色顶栏上能看出形状
- 左边太阳（圆 + 8 根射线），右边月亮（两个圆做 **even-odd 布尔**挖出月牙）
- 蓝色滑块按 0→1 的进度在两端之间滑，落在哪边哪边的图标就变白；另一边用 `text_secondary` 灰着
- 切换时跑一个 220ms 的 `ValueAnimator`（`DecelerateInterpolator`），滑到位再让 Activity 重建——不这么做的话动画根本来不及显示
- 颜色全部取自主题资源，深色模式下胶囊自动变暗

自绘的好处：任意密度都清晰，也不用为 API 14 准备多套位图。之前用过太极图标，但太极是 180° 旋转对称的，翻面后长得一模一样，看不出状态；换成"日/月 + 滑块"才符合开关该有的信息量。

## 附件

输入框左侧是回形针按钮，点开选「图片」或「文本文件」。

**图片**：走多模态。取图后压到长边 ≤1024px、JPEG 80 质量，转 base64 塞进 OpenAI 的 `image_url`（data URL 形式）：

```json
{"role":"user","content":[
  {"type":"text","text":"这是什么图标？"},
  {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
]}
```

实测在红米 1 上直接把内置鲸鱼 logo 发过去，模型回的是"这是 Docker 的图标（蓝色鲸鱼…）"——说明视觉链路真的通了，不是假装支持。

**文本文件**（`.txt`/`.md`/`.json` 等）：本地读出内容塞进输入框，最多 2 万字符，超出截断并标注。用户可以改完再发。

**不支持**：PDF / Word / Excel。API 14 上没有靠谱的本地解析库，硬上要么体积爆炸要么全是乱码。选中这类文件会明确提示"暂不支持"，而不是发个空消息出去。

回形针图标是矢量 drawable（`res/drawable/ic_attach.xml`），靠 `vectorDrawables.useSupportLibrary = true` + `AppCompatImageView` 在 API 14 上渲染——4.2 原生不支持 VectorDrawable。

## 自定义背景

设置页里可以选一张自己的图当聊天背景，模糊和明暗各一根滑块，**弹窗内有 110dp 预览区实时更新**。

- 选图后复制到 App 私有目录 `files/bg.jpg`（长边缩到 1600px、JPEG 88），不依赖相册权限，删了原图也不影响
- 模糊 0–100：在长边 360px 的缩略图上做盒式模糊再放大，滑块拖动时防抖 150ms
- 明暗 -100–100：负数盖黑、正数盖白，透明度上限 215
- 预览区和主界面背景共用同一张处理后的位图

三个坑：

1. **`Bitmap.createScaledBitmap()` 返回的是不可变位图**，直接 `setPixels()` 会抛 `IllegalStateException`——必须先 `copy(ARGB_8888, true)`。这个 bug 让模糊一直静默失败，界面上只看到纯色。
2. **透明 PNG 转 JPEG 会变白底**。我第一次拿内置的透明底 logo 当背景测试，结果一片白，还以为功能没生效。
3. **弹窗内容超屏**：加了预览区和两根滑块后按钮被挤出屏幕，得把内容套进 `ScrollView`。

## 正文渲染
模型爱回 Markdown。完整的 Markwon 要求 API 16+，所以自己写了个极简渲染：

- `**粗体**` → `StyleSpan(BOLD)`
- `` `行内代码` `` → 等宽
- 行首 `- ` → `• `

流式和最终渲染走同一个函数，避免"流式时是粗体、结束后变回星号"这种不一致。

## 性能

老机器上流式输出最大的开销是：每来一个 token 就 `setText` 一次，触发整条消息列表重新测量布局。

现在的做法：

- **合并重绘**：后台线程只往 `StringBuilder` 追加，主线程按 90ms 节拍刷一次（约 11 帧/秒），CPU 与布局次数都降一个数量级
- **智能跟随**：用户翻上去看历史时不再强制滚到底；用 `canScrollVertically` + 触摸事件判断
- **流式期间关闭文本选中**：`setTextIsSelectable(true)` 的 TextView 布局更重，流完再打开
- 正文追加放在网络线程，主线程只做 `setText`

压测入口：`--ez noThrottle true` 可关掉节流，用 `bench_sse_server.py`（固定 200 个分片）做 A/B。注意红米 1 上测量噪声很大（同一配置两次能差一倍），只能看趋势，不能当基准数字。

## 现在能做什么

- 流式对话（SSE 逐字上屏）
- 接口地址 / API Key / 模型名 都可配，默认 DeepSeek（`https://api.deepseek.com/v1` + `deepseek-chat`）
- 任何 OpenAI 兼容服务都能接：DeepSeek、通义、智谱、Moonshot、Ollama、vLLM……
- 出错时把原始错误显示在气泡里，不吞异常

## 已验证

| 环境 | 结果 |
| --- | --- |
| **红米 1（Android 4.2.1 / API 17，armeabi-v7a）** | 安装、启动、TLS 握手、真实 DeepSeek 流式对话全部正常（`real-chat.png`） |
| Android 4.0.4（API 15，x86 模拟器） | 安装、启动、渲染正常 |
| Android 16（API 36，OPPO） | 安装、启动正常 |
| 真实 DeepSeek 接口 | `deepseek-flash` 返回正常，见截图里的「1+1等于几 → 2」 |

## 两个真正的拦路虎（也是本项目最有价值的部分）

### 1. TLS 版本

Android 4.x 的 SSLSocket 默认只协商 **TLS 1.0/1.1**，现代服务端（含所有 AI API）直接拒绝。

解法：API < 21 时用 **Conscrypt**（`org.conscrypt:conscrypt-android:2.5.2`）注入一个支持 TLS 1.2 的 Provider；API ≥ 21 用系统自带的。这带来 native 库，所以 APK 里带了 `armeabi-v7a / arm64-v8a / x86 / x86_64` 四套 `libconscrypt_jni.so`。

### 2. 根证书

比 TLS 版本更隐蔽：2013 年前后的系统证书库**没有 2015 年后才出现的根 CA**（例如 Let's Encrypt 的 ISRG Root X1）。表现是：

```
javax.net.ssl.SSLHandshakeException: java.security.cert.CertPathValidatorException:
Trust anchor for certification path not found
```

第一次在红米 1 上测就是卡在这里。解法：内置 Mozilla CA 包（`assets/cacert.pem`，118 个根证书），运行时逐块解析成 BKS 信任库，再和系统信任库**合并**（任一认可即通过），这样既补上了新 CA，也不丢系统里已有的。

实现在 `Tls.kt`，启动时后台线程预热，不阻塞冷启动。

## 配置

首次启动会弹设置框，填三样：接口地址、API Key、模型名。也可以直接改 `shared_prefs/ai_settings.xml`。

```
接口地址  https://api.deepseek.com/v1
模型      deepseek-flash
```

### 关于 `v1` 和模型版本

- **`/v1`** 是 **HTTP 接口协议版本**，指"OpenAI 兼容 REST 规范第 1 版"，路径形如 `/v1/chat/completions`。它不会跟着模型升级变。
- **模型版本**在请求体的 `model` 字段里，跟 URL 无关。

### 思考模式

`deepseek-flash` 默认开启思考模式（effort=high），会先吐一大段 `reasoning_content` 再给正文。对老机器来说首字延迟很难受，所以本 App **默认关闭**：

```json
{"thinking": {"type": "disabled"}}
```

设置页勾选「思考模式」可以打开。这个字段是 DeepSeek 的扩展，只在接口地址包含 `deepseek` 时才发送，避免其它 OpenAI 兼容服务因为不认识该字段报错。


## 调试入口

```powershell
# 完整链路自检（TLS + HTTP），结果打到 logcat
adb shell am start -n com.example.myapp/.MainActivity --ez selftest true
adb logcat -d -s OldAI:*

# 流式自检
adb shell am start -n com.example.myapp/.MainActivity --ez selftest true --ez stream true

# 预填并自动发送（走完整 UI 流程）
adb shell am start -n com.example.myapp/.MainActivity --es autoSend "你好"
```

本地假服务（验证流式渲染用，不消耗 key）：

```powershell
python mock_sse_server.py     # 监听 0.0.0.0:8787
# 接口地址改成 http://<你的电脑IP>:8787/v1
```

## 工程结构

```
MyApp/app/src/main/java/com/example/myapp/
  App.kt          Application，后台预热证书库
  Tls.kt          Conscrypt + 内置 CA + 系统 CA 合并
  AiClient.kt     OkHttp + SSE 解析
  Prefs.kt        接口地址 / key / 模型
  MainActivity.kt 聊天界面
  assets/cacert.pem  Mozilla CA 包
```

## 已知限制（下一步可以补）

- **对话不持久化**：进程被杀就丢，没接数据库。老机器内存小，建议加 SQLite 存历史。
- **没有系统提示词配置**：`MainActivity` 里写死了一句简短中文指令。
- **单会话**：没有会话列表、没有多轮管理。
- **无语音/图片**：只有纯文本。
- **targetSdk 34**：Google Play 上架要求，旧机分发不受影响。


## 环境要求

验证过的设备：红米 1（Android 4.2.1 / API 17，armeabi-v7a）、Android 4.0.4 模拟器（API 15 x86）、OPPO（Android 16）。
