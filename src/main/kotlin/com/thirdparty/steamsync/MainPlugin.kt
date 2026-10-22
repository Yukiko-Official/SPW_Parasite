package com.thirdparty.steamsync

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import com.xuncorp.spw.workshop.api.WorkshopApi
import java.io.File

/**
 * Third-Party Steam Sync 插件
 *
 * 行为：
 * - Salt Player 正在播放 → 不写入 Steam Rich Presence，让宿主自己维护
 * - Salt Player 暂停/停止 → 通过 Windows GSMTC 查询第三方播放器，同步到 Steam
 *
 * 使用的技术：
 * - 反射宿主 JmtcManager.INSTANCE.jmtcRef.getPlayingState() 判断 Salt 播放状态
 * - PowerShell 调 GlobalSystemMediaTransportControlsSessionManager.RequestAsync() 枚举 SMTC 会话
 * - 扫描宿主 JAR + 反射提取 Steamworks4k 单例，调 setRichPresence()
 */
class MainPlugin(pluginContext: PluginContext) : SpwPlugin(pluginContext) {

    @Volatile private var running = false
    private var gsmtc: GsmtcBridge? = null
    private var lastKey: String? = null
    private var consecutiveNone = 0

    companion object {
        /** 连续 N 次读不到第三方播放器才清除 Rich Presence（容错 GSMTC 偶发空返回） */
        private const val NONE_THRESHOLD = 3
        /** 轮询间隔（毫秒） */
        private const val POLL_INTERVAL_MS = 3000L
    }

    override fun start() {
        running = true
        runCatching { gsmtc = GsmtcBridge() }
        WorkshopApi.ui.toast("Steam Sync started", WorkshopApi.Ui.ToastType.Success)
        Thread({ loop() }, "ThirdPartySteamSync").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        // 清空我们写入的 Rich Presence，让下一次启动或宿主自己接管
        runCatching {
            SteamworksReflector.acquire()?.let { SteamworksReflector.clearRichPresence(it) }
        }
        runCatching { gsmtc?.close() }
        WorkshopApi.ui.toast("Steam Sync stopped", WorkshopApi.Ui.ToastType.Success)
    }

    private fun loop() {
        // 启动后先给宿主 2 秒初始化 Steamworks4k
        runCatching { Thread.sleep(2000) }
        SteamworksReflector.refresh()

        while (running) {
            try {
                val saltIsPlaying = PlaybackState.isSaltPlaying()

                if (saltIsPlaying) {
                    // Salt 正在播放 → 什么都不做
                    // 不要 clearRichPresence！宿主只在换歌/状态切换时写 Rich Presence，
                    // 不是定时轮询——我们 clear 完那一瞬间宿主没刷新，会导致 Steam 空状态。
                    // 残留的第三方歌曲信息会被宿主下一次刷新自然覆盖。
                    runCatching { Thread.sleep(POLL_INTERVAL_MS) }
                    continue
                }

                // Salt Player 暂停/停止 → 查询第三方播放器
                val info = runCatching { gsmtc?.getCurrentPlaying() }.getOrNull()

                if (info != null) {
                    consecutiveNone = 0
                    val songValue = if (info.artist.isBlank()) info.title
                        else "${info.artist} - ${info.title}"
                    val key = songValue
                    val isNew = key != lastKey
                    lastKey = key

                    val sw = SteamworksReflector.acquire()
                    if (sw != null && SteamworksReflector.isReady(sw)) {
                        // 完全照搬 GaodaGG 的写法：
                        // song = 歌曲实际内容，steam_display = #ListeningTo（AppID 3009140 预置的本地化模板）
                        SteamworksReflector.setRichPresence(sw, "song", songValue)
                        SteamworksReflector.setRichPresence(sw, "steam_display", "#ListeningTo")
                        if (isNew) {
                            WorkshopApi.ui.toast("Listening: $songValue", WorkshopApi.Ui.ToastType.Success)
                        }
                    } else if (sw == null) {
                        SteamworksReflector.refresh()
                    }
                } else {
                    // 容错：GSMTC 偶发返回空列表，连续 N 次才清除
                    consecutiveNone++
                    if (lastKey != null && consecutiveNone >= NONE_THRESHOLD) {
                        lastKey = null
                        consecutiveNone = 0
                        runCatching {
                            SteamworksReflector.acquire()?.let { SteamworksReflector.clearRichPresence(it) }
                        }
                    }
                }
            } catch (e: Exception) {
                System.err.println("[SteamSync] ${e.javaClass.simpleName}: ${e.message}")
            }
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
        }
    }
}

// ===================================================================
// PlaybackState —— 反射宿主 JmtcManager 判断 Salt Player 是否在播放
// ===================================================================
object PlaybackState {

    /**
     * 通过宿主的 JmtcManager.INSTANCE.jmtcRef.getPlayingState() 获取播放状态。
     * JMTC 是宿主的 Windows SMTC 适配器，其 getPlayingState() 返回 "PLAYING" / "PAUSED" 字符串。
     */
    fun isSaltPlaying(): Boolean {
        return try {
            val hostCL = Class.forName("com.xuncorp.voxzen.service.PlaybackService").classLoader
            val jmtcCls = Class.forName("com.xuncorp.voxzen.service.JmtcManager", false, hostCL)
            val instField = jmtcCls.getDeclaredField("INSTANCE").also { it.isAccessible = true }
            val jmtcInst = instField.get(null)
            val refField = jmtcCls.getDeclaredField("jmtcRef").also { it.isAccessible = true }
            val ref = refField.get(jmtcInst) as java.util.concurrent.atomic.AtomicReference<*>
            val jmtc = ref.get() ?: return false
            val m = jmtc.javaClass.methods.first { it.name == "getPlayingState" && it.parameterCount == 0 }
            val state = m.invoke(jmtc).toString()
            state == "PLAYING"
        } catch (_: Exception) { false }
    }

    fun reset() { /* 占位，保留接口一致性 */ }
}

// ===================================================================
// SteamworksReflector —— 反射宿主 Steamworks4k 单例
//
// 策略：
// 1. Class.forName("com.xuncorp.steamworks4k.Steamworks4k") 找接口
// 2. 从 CodeSource 定位宿主 JAR
// 3. 扫描 JAR 内所有类，提取 Steamworks4k 实现单例
// ===================================================================
object SteamworksReflector {
    private val cache = java.util.concurrent.atomic.AtomicReference<Any?>(null)

    fun refresh() {
        cache.set(null)
    }

    fun acquire(): Any? = cache.get() ?: load()

    private fun load(): Any? {
        // 1. 先试试默认 Class.forName（跟宿主同一 classloader）
        runCatching {
            Class.forName("com.xuncorp.steamworks4k.Steamworks4k")
        }.getOrNull()?.let { swCls ->
            val jarFile = runCatching {
                swCls.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
            }.getOrNull()
            val swImpl = scanJar(jarFile, swCls.classLoader, swCls.name)
            if (swImpl != null) {
                cache.set(swImpl)
                return swImpl
            }
        }

        // 兜底：用 PlaybackService 的 classloader 再试一次
        runCatching {
            Class.forName("com.xuncorp.voxzen.service.PlaybackService").classLoader
        }.getOrNull()?.let { hostCL ->
            runCatching { Class.forName("com.xuncorp.steamworks4k.Steamworks4k", false, hostCL) }.getOrNull()?.let { swCls ->
                val jarFile = runCatching {
                    swCls.protectionDomain?.codeSource?.location?.toURI()?.let { File(it) }
                }.getOrNull()
                val swImpl = scanJar(jarFile, hostCL, swCls.name)
                if (swImpl != null) {
                    cache.set(swImpl)
                    return swImpl
                }
            }
        }
        return null
    }

    private fun scanJar(jar: File?, cl: ClassLoader, targetName: String): Any? {
        if (jar == null) return null
        return try {
            val zf = java.util.zip.ZipFile(jar)
            val entries = zf.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (!name.endsWith(".class")) continue
                if (name.startsWith("kotlin/") || name.startsWith("META-INF/")) continue
                val clsName = name.replace('/', '.').removeSuffix(".class")
                runCatching { Class.forName(clsName, false, cl) }.getOrNull()?.let { cls ->
                    tryExtractInstance(cls, targetName)?.let {
                        zf.close()
                        return it
                    }
                }
            }
            zf.close()
            null
        } catch (e: Exception) { null }
    }

    /** 递归检查类是否实现/继承了目标接口 */
    private fun isSteamworksClass(cls: Class<*>?, target: String): Boolean {
        var c = cls
        while (c != null) {
            if (c.name == target) return true
            c = c.superclass
        }
        return false
    }

    /**
     * 尝试从类中提取 Steamworks4k 单例实例。
     * 三种策略：静态无参方法、静态字段、Kotlin object INSTANCE。
     */
    private fun tryExtractInstance(cls: Class<*>, target: String): Any? {
        // 策略 1: 静态无参方法返回 Steamworks4k
        for (m in cls.declaredMethods) {
            if (java.lang.reflect.Modifier.isStatic(m.modifiers) && m.parameterCount == 0) {
                if (isSteamworksClass(m.returnType, target)) {
                    m.isAccessible = true
                    runCatching { m.invoke(null) }.getOrNull()?.let { r ->
                        if (isSteamworksClass(r.javaClass, target)) return r
                    }
                }
            }
        }
        // 策略 2: 静态字段类型是 Steamworks4k
        for (f in cls.declaredFields) {
            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) {
                if (isSteamworksClass(f.type, target)) {
                    f.isAccessible = true
                    runCatching { f.get(null) }.getOrNull()?.let { r ->
                        if (isSteamworksClass(r.javaClass, target)) return r
                    }
                }
            }
        }
        // 策略 3: Kotlin object INSTANCE 字段
        runCatching {
            val f = cls.getDeclaredField("INSTANCE")
            if (java.lang.reflect.Modifier.isStatic(f.modifiers) && java.lang.reflect.Modifier.isFinal(f.modifiers)) {
                f.isAccessible = true
                val inst = f.get(null)
                if (inst != null) {
                    for (m in inst.javaClass.declaredMethods) {
                        if (m.parameterCount == 0 && isSteamworksClass(m.returnType, target)) {
                            m.isAccessible = true
                            runCatching { m.invoke(inst) }.getOrNull()?.let { r ->
                                if (isSteamworksClass(r.javaClass, target)) return r
                            }
                        }
                    }
                    for (f2 in inst.javaClass.declaredFields) {
                        if (isSteamworksClass(f2.type, target)) {
                            f2.isAccessible = true
                            runCatching { f2.get(inst) }.getOrNull()?.let { r ->
                                if (isSteamworksClass(r.javaClass, target)) return r
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /** 检查 Steamworks4k 是否已初始化 */
    fun isReady(sw: Any): Boolean {
        runCatching {
            var cls: Class<*>? = sw.javaClass
            while (cls != null && cls != Any::class.java) {
                for (m in cls.declaredMethods) {
                    if ((m.name == "isInitialized" || m.name == "getInitialized") && m.parameterCount == 0) {
                        m.isAccessible = true
                        val r = m.invoke(sw)
                        if (r is Boolean) return r
                    }
                }
                cls = cls.superclass
            }
        }
        return true // 找不到方法假设已初始化
    }

    /**
     * 写入 Steam Rich Presence。
     * 走 class hierarchy 查找 setRichPresence(String, String) 方法（兼容宿主混淆）。
     */
    fun setRichPresence(sw: Any, key: String, value: String) {
        var cls: Class<*>? = sw.javaClass
        var target: java.lang.reflect.Method? = null
        while (cls != null && cls != Any::class.java) {
            for (m in cls.declaredMethods) {
                if ((m.name.startsWith("setRichPresence") || m.name.startsWith("SetRichPresence"))
                    && m.parameterCount == 2
                    && m.parameterTypes[0] == String::class.java
                    && m.parameterTypes[1] == String::class.java
                ) {
                    target = m
                    break
                }
            }
            if (target != null) break
            cls = cls.superclass
        }
        if (target != null) {
            target.isAccessible = true
            runCatching { target.invoke(sw, key, value) }
        }
    }

    /** 清空我们写入的两个 Rich Presence key */
    fun clearRichPresence(sw: Any) {
        setRichPresence(sw, "song", "")
        setRichPresence(sw, "steam_display", "")
    }
}

// ===================================================================
// GsmtcBridge —— PowerShell 包装，查询 Windows GSMTC 当前播放状态
// ===================================================================

/** GSMTC 返回的媒体信息 */
data class MediaInfo(
    val title: String,
    val artist: String,
    val appId: String
)

/**
 * 将 resources/gsmtc_query.ps1 提取到临时文件，通过 PowerShell 5.1 执行。
 *
 * 脚本内部：
 * - 调 GlobalSystemMediaTransportControlsSessionManager.RequestAsync() 枚举系统 SMTC 会话
 * - 取第一个 PlaybackStatus == 4 (PLAYING) 的会话
 * - 输出格式：OK|title|artist|appId  或  NONE
 */
class GsmtcBridge {
    private val tempScript: File

    init {
        val psBytes = javaClass.classLoader
            .getResourceAsStream("gsmtc_query.ps1")?.readBytes()
            ?: javaClass.getResourceAsStream("/gsmtc_query.ps1")?.readBytes()
        if (psBytes != null) {
            tempScript = File.createTempFile("gsmtc_", ".ps1")
            tempScript.deleteOnExit()
            tempScript.writeBytes(psBytes)
        } else {
            tempScript = File("NUL")
        }
    }

    fun getCurrentPlaying(): MediaInfo? {
        if (!tempScript.exists()) return null
        return try {
            val p = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NoLogo",
                "-ExecutionPolicy", "Bypass",
                "-File", tempScript.absolutePath
            ).redirectErrorStream(true).start()
            val output = p.inputStream
                .bufferedReader(java.nio.charset.StandardCharsets.UTF_8)
                .use { it.readText() }.trim()
            val exited = p.waitFor(8L, java.util.concurrent.TimeUnit.SECONDS)
            if (!exited) { p.destroyForcibly() }
            parseOutput(output)
        } catch (e: Exception) { null }
    }

    private fun parseOutput(output: String): MediaInfo? {
        val firstLine = output.lines().firstOrNull()?.trim() ?: return null
        return when {
            firstLine.startsWith("OK|") -> {
                val parts = firstLine.removePrefix("OK|").split('|', limit = 4)
                val title = parts.getOrNull(0).orEmpty().trim()
                val artist = parts.getOrNull(1).orEmpty().trim()
                val appId = parts.getOrNull(2).orEmpty().trim()
                if (title.isBlank() && artist.isBlank()) null
                else MediaInfo(title = title, artist = artist, appId = appId)
            }
            else -> null
        }
    }

    fun close() { runCatching { tempScript.delete() } }
}
