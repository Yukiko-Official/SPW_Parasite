$ErrorActionPreference = 'Continue'
# 强制 UTF-8 输出，避免 Java ProcessBuilder 读中文乱码
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# ---- 白名单：只允许这些 SourceAppUserModelId（进程 exe 名）的音乐播放器 ----
# 实际值可通过 PowerShell 脚本 dump 查看：$s.SourceAppUserModelId
$MUSIC_APP_PATTERNS = @(
    'QQMusic.exe',        # QQ 音乐
    'cloudmusic.exe',     # 网易云音乐
    'Spotify.exe',        # Spotify
    'Spotify Helper.exe',
    'AMPLITUDE.exe',      # Apple Music (Windows)
    'iTunes.exe',         # iTunes (Windows)
    'MusicBee.exe',       # MusicBee
    'foobar2000.exe',     # foobar2000
    'AIMP.exe',           # AIMP
    'AIMP4.exe',
    'AIMP5.exe',
    'JAM.exe',            # JetAudio
    'Dopamine.exe',       # Dopamine
    'Lollypop.exe',
    'Audacious.exe',
    'Clementine.exe',
    'SPlayer.exe'         # 搜狗音乐
)

function IsAllowed([string]$sourceApp) {
    if ([string]::IsNullOrWhiteSpace($sourceApp)) { return $false }
    foreach ($p in $MUSIC_APP_PATTERNS) {
        if ($sourceApp -ieq $p) { return $true }
    }
    return $false
}

try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $t = [Type]::GetType('Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType=WindowsRuntime')
    if (-not $t) { Write-Output 'ERR:type_not_found'; exit 1 }
    $op = $t.GetMethod('RequestAsync').Invoke($null, @())
    if (-not $op) { Write-Output 'ERR:request_failed'; exit 1 }
    $ext = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' }
    if (-not $ext) { Write-Output 'ERR:asTask_not_found'; exit 1 }
    $gm = $ext.MakeGenericMethod($t)
    $task = $gm.Invoke($null, @($op))
    $mgr = $task.Result
    if (-not $mgr) { Write-Output 'ERR:no_manager'; exit 1 }

    $extensions = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' }
    $mediaExt = $extensions | Where-Object { $_.GetParameters()[0].ParameterType.FullName -like '*SessionMediaProperties*' }
    if (-not $mediaExt) { $mediaExt = $extensions | Select-Object -First 1 }

    $sessions = $mgr.GetSessions()
    foreach ($s in $sessions) {
        # ---- 白名单过滤：用 SourceAppUserModelId（exe 名，如 QQMusic.exe） ----
        $sourceApp = $s.SourceAppUserModelId
        if (-not (IsAllowed $sourceApp)) { continue }

        $info = $s.GetPlaybackInfo()
        $status = $info.PlaybackStatus
        if ($status -eq 4) {
            $mediaOp = $s.TryGetMediaPropertiesAsync()
            $mTask = $mediaExt.MakeGenericMethod([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]).Invoke($null, @($mediaOp))
            $media = $mTask.Result
            Write-Output ("OK|{0}|{1}|{2}" -f $media.Title, $media.Artist, $sourceApp)
            exit 0
        }
    }
    Write-Output 'NONE'
} catch {
    Write-Output ("ERR: " + $_.Exception.Message)
}
