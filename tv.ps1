# Usage:
#   .\tv.ps1 check              - confirm the connected TV is the expected model
#   .\tv.ps1 apply   [list]     - disable every package in the list (default debloat.txt)
#   .\tv.ps1 restore [list]     - re-enable every package in the list
#   .\tv.ps1 status  [list]     - show enabled/disabled state of each package in the list
# TV address: -TvHost, $env:TV_HOST or a .tvhost file (see tv-common.ps1).
param(
    [Parameter(Mandatory)][ValidateSet('check', 'apply', 'restore', 'status')][string]$Action,
    [string]$List,
    [string]$TvHost,
    [string]$Model = 'KD-55X8000H'
)
# Windows PowerShell 5.1 doesn't set $PSScriptRoot inside param defaults.
if (-not $List) { $List = Join-Path $PSScriptRoot 'debloat.txt' }

. (Join-Path $PSScriptRoot 'tv-common.ps1')
$TvHost = Resolve-TvHost $TvHost
$adb = Resolve-Adb
$d = '-s', $TvHost

function Get-Packages {
    Get-Content $List | ForEach-Object { ($_ -replace '#.*', '').Trim() } | Where-Object { $_ }
}

Connect-Tv $adb $TvHost $Model
if ($Action -eq 'check') { return }

$disabled = (& $adb @d shell pm list packages -d) -replace '^package:', ''
$installed = (& $adb @d shell pm list packages) -replace '^package:', ''

# One pm call per package: chained shell commands can stop silently partway.
foreach ($p in Get-Packages) {
    if ($p -notin $installed) { "{0,-45} not on this TV, skipped" -f $p; continue }
    switch ($Action) {
        'apply'   { "{0,-45} {1}" -f $p, (& $adb @d shell pm disable-user --user 0 $p) }
        'restore' { "{0,-45} {1}" -f $p, (& $adb @d shell pm enable $p) }
        'status'  { "{0,-45} {1}" -f $p, $(if ($p -in $disabled) { 'disabled' } else { 'enabled' }) }
    }
}
