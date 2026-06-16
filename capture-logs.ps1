<#
  capture-logs.ps1 - live USB log capture + post-crash forensics for JuneProcess.

  WHILE CONNECTED: streams ALL logcat buffers to a file, flushed line-by-line, so the last
  lines before a kernel panic / USB drop are safely on disk (a reboot wipes the on-device RAM
  buffer, which is why `adb logcat -d` after the fact shows nothing). Camera / crash / our
  breadcrumb (JPCAP) lines are mirrored to the console live.

  ON EVERY (RE)CONNECT: runs a forensics pass that grabs WHY the device rebooted -
    * boot-reason props (sys.boot.reason / ro.boot.bootreason) - work WITHOUT root, and say
      whether it was a real "kernel_panic" vs "watchdog" vs "thermal" vs normal reboot.
    * best-effort kernel ramoops / pstore / last_kmsg + Nothing panic dirs (need root; degrade
      gracefully to nothing if unavailable).
  Each crash's forensics is saved to logs\panic-<timestamp>.txt and echoed into the main log.

  USAGE:   ./capture-logs.ps1            (Ctrl+C to stop)
  OUTPUT:  .\logs\camera-live-<ts>.log   (full stream)
           .\logs\panic-<ts>.txt         (one per reboot detected)
#>

param(
    [string]$OutDir = (Join-Path $PSScriptRoot "logs")
)

$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe"
    )) { if (Test-Path $p) { return $p } }
    throw "adb not found. Add platform-tools to PATH or install Android SDK."
}

$adb = Resolve-Adb
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp   = Get-Date -Format "yyyyMMdd_HHmmss"
$logFile = Join-Path $OutDir "camera-live-$stamp.log"

$consolePattern = 'JPCAP|AndroidRuntime|FATAL|tombstone|CameraDevice|Camera2|Camera3|cameraserver|CAM_|qcom,camera|camxhal|camx|DngCreator|ImageReader|BUG:|Kernel panic|SIGABRT|libc :|restart_reason|watchdog'

$writer = New-Object System.IO.StreamWriter($logFile, $true, [System.Text.Encoding]::UTF8)
$writer.AutoFlush = $true   # flush every line -> nothing lost when USB drops

function Write-Both([string]$line, [System.ConsoleColor]$color = [System.ConsoleColor]::DarkGray) {
    $writer.WriteLine($line); Write-Host $line -ForegroundColor $color
}

# Run a device shell command, discarding host-side adb stderr; returns stdout string (may be empty).
function Adb-Sh([string]$cmd) {
    try { return (& $adb shell $cmd 2>$null | Out-String) } catch { return "" }
}

function Collect-Forensics {
    $ts = Get-Date -Format "yyyyMMdd_HHmmss"
    $pf = Join-Path $OutDir "panic-$ts.txt"
    $fw = New-Object System.IO.StreamWriter($pf, $false, [System.Text.Encoding]::UTF8)
    function W([string]$s, [System.ConsoleColor]$c = [System.ConsoleColor]::Magenta) {
        $fw.WriteLine($s); $writer.WriteLine($s); Write-Host $s -ForegroundColor $c
    }

    W "######## POST-CRASH FORENSICS  $ts ########"

    # Wait (up to ~120s) for the device to finish booting so shell commands are reliable.
    W "--- waiting for boot_completed ---"
    for ($i = 0; $i -lt 60; $i++) {
        if ((Adb-Sh "getprop sys.boot_completed").Trim() -eq "1") { break }
        Start-Sleep -Seconds 2
    }

    # 1) Boot / restart reason - readable WITHOUT root. This is the key signal.
    W "`n--- boot reason (why it rebooted) ---"
    $panicHit = $false
    foreach ($p in @("sys.boot.reason","sys.boot.reason.last","ro.boot.bootreason",
                     "persist.sys.boot.reason.history","ro.boot.boot_reason",
                     "vendor.restart_reason","persist.vendor.restart_reason")) {
        $v = (Adb-Sh "getprop $p").Trim()
        if ($v) {
            $isPanic = $v -match 'panic|oops|fatal|watchdog|hw_reset|kernel'
            if ($isPanic) { $panicHit = $true }
            W ("{0,-34} = {1}" -f $p, $v) ($(if ($isPanic) { 'Red' } else { 'Magenta' }))
        }
    }
    if ($panicHit) { W ">>> REBOOT REASON INDICATES A KERNEL-LEVEL CRASH <<<" Red }

    # 2) Kernel ramoops / pstore / last_kmsg - try unprivileged, then su. Empty if unavailable.
    foreach ($t in @("/sys/fs/pstore/console-ramoops-0","/sys/fs/pstore/console-ramoops",
                     "/sys/fs/pstore/dmesg-ramoops-0","/proc/last_kmsg")) {
        $out = Adb-Sh "cat $t 2>/dev/null"
        if (-not $out.Trim()) { $out = Adb-Sh "su -c 'cat $t' 2>/dev/null" }
        if ($out.Trim()) {
            W "`n--- $t (previous boot kernel log) ---"
            $fw.WriteLine($out)                 # full dump to the panic file
            $writer.WriteLine($out)
            ($out -split "`n" | Select-String -Pattern 'camera|cam_|camx|panic|BUG|Call trace|PC is at|Unable to handle|watchdog' | Select-Object -Last 25) |
                ForEach-Object { Write-Host $_.Line -ForegroundColor Red }   # camera/panic tail to console
        }
    }

    # 3) Current-boot dmesg (usually restricted without root).
    $dmesg = Adb-Sh "dmesg 2>/dev/null"
    if (-not $dmesg.Trim()) { $dmesg = Adb-Sh "su -c dmesg 2>/dev/null" }
    if ($dmesg.Trim()) { W "`n--- dmesg (current boot) ---" Magenta; $fw.WriteLine($dmesg); $writer.WriteLine($dmesg) }

    # 4) Nothing-specific saved panic logs (root only, but list them so we know they exist).
    $nt = Adb-Sh "ls -la /data/logkit/ /mnt/product/nt_log/panic_log/ 2>/dev/null"
    if (-not $nt.Trim()) { $nt = Adb-Sh "su -c 'ls -la /data/logkit/ /mnt/product/nt_log/panic_log/' 2>/dev/null" }
    if ($nt.Trim()) { W "`n--- Nothing saved panic logs ---" Magenta; $fw.WriteLine($nt); $writer.WriteLine($nt) }

    W "######## forensics saved: $pf ########"
    $fw.Flush(); $fw.Close()
}

Write-Host "=== JuneProcess live log capture + forensics ===" -ForegroundColor Cyan
Write-Host "adb : $adb"
Write-Host "log : $logFile"
Write-Host "Mirroring camera/crash/JPCAP below. Take photos on the phone. Ctrl+C to stop.`n" -ForegroundColor Cyan

$firstRun = $true
try {
    while ($true) {
        Write-Both ("=== {0}  waiting for device... ===" -f (Get-Date -Format "HH:mm:ss.fff")) Yellow
        & $adb wait-for-device 2>$null
        $serial = (& $adb get-serialno 2>$null)
        Write-Both ("=== {0}  CONNECTED ({1}) ===" -f (Get-Date -Format "HH:mm:ss.fff"), $serial) Green

        # Always grab why-it-rebooted on connect. On first run this catches the MOST RECENT
        # crash (from pstore/props); on reconnect it catches the crash that just happened.
        Collect-Forensics

        if ($firstRun) {
            & $adb logcat -c 2>$null   # clean slate once, so the stream file is only this session
            $firstRun = $false
        }

        Write-Both ("=== {0}  streaming logcat (-b all) ===" -f (Get-Date -Format "HH:mm:ss.fff")) Green
        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $adb; $psi.Arguments = "logcat -b all -v threadtime"
        $psi.RedirectStandardOutput = $true; $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
        $proc = [System.Diagnostics.Process]::Start($psi)

        while (-not $proc.HasExited) {
            $line = $proc.StandardOutput.ReadLine()
            if ($null -eq $line) { break }
            $writer.WriteLine($line)
            if ($line -match $consolePattern) {
                $c = if ($line -match 'FATAL|BUG:|panic|SIGABRT|tombstone|restart_reason') { 'Red' }
                     elseif ($line -match 'JPCAP') { 'Cyan' } else { 'Gray' }
                Write-Host $line -ForegroundColor $c
            }
        }
        try { $proc.StandardOutput.ReadToEnd() | ForEach-Object { $writer.Write($_) } } catch {}

        Write-Both ("=== {0}  DEVICE DISCONNECTED (possible crash - lines above are the trigger) ===" -f (Get-Date -Format "HH:mm:ss.fff")) Red
    }
}
finally {
    Write-Both ("=== {0}  capture stopped ===" -f (Get-Date -Format "HH:mm:ss.fff")) Yellow
    $writer.Flush(); $writer.Close()
    Write-Host "`nFull log saved to: $logFile" -ForegroundColor Cyan
}
