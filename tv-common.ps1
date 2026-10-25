# Shared by tv.ps1 and tv-home.ps1 (dot-sourced).
#
# The TV's network address is personal, so it is never stored in the repo. Give it one of these ways:
#   -TvHost <tv-ip>            on the command line
#   $env:TV_HOST = '...'       for the session
#   a file named .tvhost       next to the scripts, containing the address (git-ignored)
# ":5555" (the network-ADB port) is added if missing.

function Resolve-TvHost([string]$TvHost) {
    if (-not $TvHost) { $TvHost = $env:TV_HOST }
    if (-not $TvHost) {
        $file = Join-Path $PSScriptRoot '.tvhost'
        if (Test-Path $file) { $TvHost = "$(Get-Content $file -TotalCount 1)".Trim() }
    }
    if (-not $TvHost) { throw "TV address not set. Use -TvHost <address>, set `$env:TV_HOST, or create a .tvhost file (see README)." }
    if ($TvHost -notmatch ':\d+$') { $TvHost += ':5555' }
    return $TvHost
}

# adb from PATH, or from the folder the README's setup steps unzip platform-tools into.
function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $local = Join-Path $env:USERPROFILE 'platform-tools\adb.exe'
    if (Test-Path $local) { return $local }
    throw 'adb not found. Install Android platform-tools and put adb on PATH (see README).'
}

# Connects, then refuses to go on unless the TV reports the expected model, so a second Android
# TV on the same network is never touched by mistake.
function Connect-Tv([string]$Adb, [string]$TvHost, [string]$Model) {
    & $Adb connect $TvHost | Out-Null
    $name = "$(& $Adb -s $TvHost shell settings get global device_name)".Trim()
    if ($name -notmatch [regex]::Escape($Model)) { throw "Connected device is '$name', not a $Model. Stopping." }
    "Connected to $name"
}
