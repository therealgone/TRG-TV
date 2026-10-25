# Usage:
#   .\tv-home.ps1 install [-OwnerName <name>]  - build, install, grant access, make it Home (rolls back if Home doesn't land on it)
#   .\tv-home.ps1 grant      - (re)grant media access for the Now playing panel
#   .\tv-home.ps1 check      - show which app is Home right now
#   .\tv-home.ps1 rollback   - make the stock home screen the Home app again (custom launcher stays installed)
#   .\tv-home.ps1 uninstall  - rollback, then remove the custom launcher
# TV address: -TvHost, $env:TV_HOST or a .tvhost file (see tv-common.ps1).
# Owner name (welcome / signing-out screens): -OwnerName once; it's remembered in a git-ignored .owner-name file.
#
# The stock launcher declares Home priority 2, which Android always prefers over a user-chosen Home
# app, and Android 10 won't let adb disable just that activity. So the whole stock launcher package
# is disabled (pm disable-user: reversible, nothing uninstalled). rollback re-enables it.
param(
    [Parameter(Mandatory)][ValidateSet('install', 'grant', 'check', 'rollback', 'uninstall')][string]$Action,
    [string]$TvHost,
    [string]$Model = 'KD-55X8000H',
    [string]$OwnerName
)

. (Join-Path $PSScriptRoot 'tv-common.ps1')
$TvHost = Resolve-TvHost $TvHost
$adb = Resolve-Adb
$d = '-s', $TvHost
$pkg = 'dev.tvhome.launcher'
$ours = "$pkg/.HomeActivity"
$stock = 'com.google.android.tvlauncher/.MainActivity'
$listener = "$pkg/$pkg.MediaListener"

Connect-Tv $adb $TvHost $Model

function Get-Home { (& $adb @d shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Select-Object -Last 1).Trim() }
function Get-Focus { (& $adb @d shell dumpsys window | Select-String 'mCurrentFocus' | Select-Object -First 1).Line.Trim() }
function Set-Home($component) {
    $stockPkg = $stock.Split('/')[0]
    if ($component -eq $stock) { & $adb @d shell pm enable $stockPkg }
    else { & $adb @d shell pm disable-user --user 0 $stockPkg }
    & $adb @d shell cmd package set-home-activity $component
    & $adb @d shell input keyevent KEYCODE_HOME
    Start-Sleep 3
}

switch ($Action) {
    'install' {
        & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-apk.ps1') (Join-Path $PSScriptRoot 'launcher')
        if ($LASTEXITCODE -ne 0) { throw 'build failed' }
        & $adb @d install -r (Join-Path $PSScriptRoot 'launcher\build\app.apk')
        & $adb @d shell cmd notification allow_listener $listener
        # Leftover from the old 50/50 pane experiment: make sure no simulated display remains.
        & $adb @d shell settings delete global overlay_display_devices | Out-Null
        # Accessibility service for the remote shortcuts (hold 0 / 1-3 / 4, Home) and greetings.
        $svc = "$pkg/$pkg.KeyService"
        $cur = "$(& $adb @d shell settings get secure enabled_accessibility_services)".Trim()
        if ($cur -notmatch [regex]::Escape($svc)) {
            $new = if ($cur -eq 'null' -or -not $cur) { $svc } else { "${cur}:$svc" }
            & $adb @d shell settings put secure enabled_accessibility_services $new
        }
        & $adb @d shell settings put secure accessibility_enabled 1
        Set-Home $ours
        $focus = Get-Focus
        "Home: $(Get-Home)"
        "Focus: $focus"
        if ($focus -notmatch [regex]::Escape($pkg)) {
            'Home did not land on the custom launcher - rolling back to stock.'
            Set-Home $stock
            throw 'install rolled back'
        }
        # Owner name lives on the TV and in a local git-ignored file, never in the source.
        $nameFile = Join-Path $PSScriptRoot '.owner-name'
        if ($PSBoundParameters.ContainsKey('OwnerName')) { Set-Content -Path $nameFile -Value $OwnerName -Encoding utf8 }
        elseif (Test-Path $nameFile) { $OwnerName = "$(Get-Content $nameFile -TotalCount 1)".Trim() }
        if ($OwnerName -ne $null) {
            $safe = $OwnerName -replace "'", ''
            & $adb @d shell "am start -n $ours --es dev.tvhome.launcher.SET_OWNER_NAME '$safe'" | Out-Null
        }
        'Custom launcher is Home.'
    }
    'grant' { & $adb @d shell cmd notification allow_listener $listener; 'Media access granted.' }
    'check' { "Home: $(Get-Home)"; "Focus: $(Get-Focus)" }
    'rollback' { Set-Home $stock; "Home: $(Get-Home)"; "Focus: $(Get-Focus)" }
    'uninstall' { Set-Home $stock; & $adb @d uninstall $pkg; "Home: $(Get-Home)" }
}
