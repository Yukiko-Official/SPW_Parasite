# Parasite（椒盐大强兼）

Salt Player for Windows (AppID 3009140) 的 Workshop 插件。当 Salt Player 处于 **暂停状态** 时，自动把 Windows 系统里其他播放器（QQ 音乐、网易云音乐、Spotify、Apple Music 等）的播放状态同步到 Steam 丰富状态。Salt Player 自身播放时自动让位，不抢占宿主状态。

> **Parasite** —— 寄生在 Salt Player 的 Steam Rich Presence 上，宿主活我就溜，宿主停我就上。

## 工作原理

```
┌─────────────────────────────────────────────────┐
│              插件循环 (可配置间隔)                 │
│                                                   │
│  1. 反射宿主 JmtcManager → getPlayingState()      │
│     → 判断 Salt Player 是 PLAYING 还是 PAUSED     │
│                                                   │
│  2a. Salt 正在播放 → 什么都不做                     │
│     (让宿主自己维护 Steam 状态)                     │
│                                                   │
│  2b. Salt 暂停 → PowerShell 调 Windows GSMTC       │
│     → 枚举所有正在播放的 SMTC session               │
│     → 取第一个 PLAYING 的 title / artist            │
│     → 反射宿主 Steamworks4k.setRichPresence()      │
│     → 写入 song + steam_display 两个 key            │
└─────────────────────────────────────────────────┘
```

### 为什么用两个 Rich Presence key？

Steam 对 AppID 3009140 预置了本地化模板 `#ListeningTo`，模板里有 `%song%` 占位符：

| Key | 值 | 作用 |
|---|---|---|
| `song` | `"HOYO-MiX - 轻涟 La vaguelette"` | 歌曲实际内容（替换模板里的 `%song%`） |
| `steam_display` | `#ListeningTo` | Steam 本地化模板标识 |

宿主自身和 GaodaGG 的 `SaltSteamPlugin` 都用这套写法，插件直接照搬以保证兼容性。

### 容错机制

GSMTC 的 `RequestAsync()` 偶尔会短时间返回空列表（播放器刚启动、还没注册到系统 SMTC）。插件内置了：

- **连续 N 次检测不到第三方才清除** Rich Presence（默认 3 次，可在配置里改）
- **每次轮询都重新 setRichPresence**，抗宿主覆盖

## 权限

插件不需要声明任何 Workshop 权限（`PluginPermissions = emptyList()`）。核心能力通过以下非受限途径实现：

| 能力 | 实现方式 | 是否需要权限 |
|---|---|---|
| 判断 Salt 播放状态 | 反射 `JmtcManager`（宿主内部类） | ❌ |
| 查询第三方播放器 | 调 Windows `GlobalSystemMediaTransportControlsSessionManager` | ❌（SMTC 是系统级服务） |
| 写入 Steam Rich Presence | 反射宿主 `Steamworks4k` 单例 | ❌ |

这是 Workshop API 受限范围之外的"灰色地带"用法——插件运行在宿主 JVM 内部，可以直接反射宿主类，但无法通过 Workshop 官方 API 做到。

## 配置

启用插件后，在播放器设置里会出现 "Third-Party Steam Sync" 的配置入口（由 `preference_config.json` 声明）：

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| 启用插件 | 开关 | ✅ | 总开关 |
| 轮询间隔 | 滑动条 2-10s | 3s | 多久检查一次第三方播放器 |
| 容错阈值 | 滑动条 1-6 | 3 | 连续多少次没检测到才清除 Steam 状态 |
| Toast 提示 | 开关 | ✅ | 检测到新歌曲时弹通知 |

> 目前配置界面已声明，但插件代码尚未接入 `WorkshopApi.manager.createConfigManager()`。后续版本会将上述常量从 `Companion Object` 读取用户配置。

## 构建

要求：JDK 25+（低于 25 请改 `build.gradle.kts` 里的 `sourceCompatibility`）

```powershell
# ⚠️ 构建前必须完全关闭 Salt Player
# SPW 启动时会锁定 AOT cache，Gradle 无法写入
Get-Process | Where-Object { $_.ProcessName -match "Salt" } | Stop-Process -Force
Start-Sleep -Seconds 10   # 等 AOT cache 释放

.\gradlew.bat clean plugin
```

产物：
```
build/libs/plugin-com.thirdparty.steamsync-1.0.0.spmod
```

## 安装

### 本地导入

```powershell
Copy-Item "build\libs\plugin-com.thirdparty.steamsync-1.0.0.spmod" `
  "$env:APPDATA\Salt Player for Windows\workshop\plugins\"
```

首次启用后，SPW 会弹出权限确认弹窗（本插件不申请权限，直接启用即可）。

### 避免与 GaodaGG 插件冲突

如果之前装了 `com.gg.SaltSteamPlugin`，需要先在 `enabled.txt` 里移除它，否则两个插件会同时写 Rich Presence 互相覆盖：

```powershell
Set-Content "$env:APPDATA\Salt Player for Windows\workshop\plugins\enabled.txt" `
  "com.thirdparty.steamsync"
```

## 发布

构建完成后：

1. **GitHub Releases** — 上传 `.spmod` 文件，添加 `salt-player-plugins` topic
2. **Steam 创意工坊** — 在 Salt Player 启动参数里加 `--workshop-publisher`，启动后从创意工坊页面上传

详细步骤见 [官方发布文档](https://saltplayer.com/workshop/publishing.html)。

## 依赖技术

| 技术 | 用途 |
|---|---|
| `GlobalSystemMediaTransportControlsSessionManager` | 枚举 Windows SMTC 会话 |
| PowerShell 5.1 + `AsTask` MakeGenericMethod | 在 Kotlin 进程中执行 WinRT 异步调用 |
| Kotlin 反射 (`getDeclaredField` + `setAccessible`) | 访问宿主 `JmtcManager.INSTANCE.jmtcRef` |
| 扫描宿主 JAR + 反射 | 提取宿主 `Steamworks4k` 单例实例 |
| SPW Workshop API | `SpwPlugin` 基类、`WorkshopApi.ui.toast`、配置入口声明 |

## 项目结构

```
third-party-steam-sync/
├── build.gradle.kts          # spmod 元数据 + 依赖
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── src/main/
│   ├── kotlin/com/thirdparty/steamsync/
│   │   └── MainPlugin.kt        # 主文件（MainPlugin + PlaybackState + SteamworksReflector + GsmtcBridge）
│   └── resources/
│       ├── gsmtc_query.ps1      # PowerShell GSMTC 查询脚本（打包到临时文件执行）
│       └── preference_config.json # SPW 配置界面声明
└── README.md
```

## 已知限制

- **Windows 专属** — 依赖 SMTC / WinRT，无 Linux/macOS 支持
- **宿主升级可能失效** — 所有宿主类都是反射访问的（类名混淆、方法改名都会导致失效），届时需要重新探测并更新反射路径
- **编码** — GSMTC 中/日/韩文字符需要 PowerShell 5.1 输出 UTF-8 + Kotlin 强制 UTF-8 读取，已有处理
