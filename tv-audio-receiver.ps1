# Makes this Windows PC a Bluetooth speaker for the TV (A2DP sink), so TV audio plays on the PC's
# current output (e.g. your headphones) mixed with the PC's own audio.
# Uses Windows' built-in AudioPlaybackConnection API (Windows 10 2004+ / Windows 11); nothing to install.
#
# One-time: pair from the PC (Windows Settings > Bluetooth & devices > Add device) while the TV is on
# Settings > Remotes & Accessories. Pairing is remembered; you don't repeat it.
#
# Usage:
#   .\tv-audio-receiver.ps1 -List            - show paired devices that can send audio to this PC
#   .\tv-audio-receiver.ps1 [-Name SONY]     - connect now and keep receiving until Ctrl+C
#   .\tv-audio-receiver.ps1 -Watch           - run forever: connect whenever the TV is on, reconnect after drops
#   .\tv-audio-receiver.ps1 -InstallTask     - start -Watch hidden at every Windows sign-in (current user, no admin)
#   .\tv-audio-receiver.ps1 -RemoveTask      - undo -InstallTask
# While connected, the TV's own speakers are silent (its sound goes to the PC). To use the TV speakers,
# stop the task (Task Scheduler, or -RemoveTask) or disconnect the PC in the TV's Bluetooth settings.
param(
    [string]$Name = 'SONY',
    [switch]$List,
    [switch]$Watch,
    [switch]$InstallTask,
    [switch]$RemoveTask
)

$ErrorActionPreference = 'Stop'
$taskName = 'TV audio receiver'

if ($InstallTask -or $RemoveTask) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    if ($RemoveTask) { "Removed scheduled task '$taskName'."; return }
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Watch -Name `"$Name`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings `
        -Description 'Receives TV audio over Bluetooth (TRG-TV tv-audio-receiver.ps1 -Watch).' | Out-Null
    "Installed scheduled task '$taskName' (runs at sign-in). Start it now with: Start-ScheduledTask '$taskName'"
    return
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime
[void][Windows.Media.Audio.AudioPlaybackConnection, Windows.Media.Audio, ContentType = WindowsRuntime]
[void][Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime]

# PowerShell can't await WinRT async calls directly; bridge them through .NET tasks.
$asTaskOfT = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1
$asTaskAction = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
} | Select-Object -First 1
function Await($op, [Type]$resultType) {
    $task = $asTaskOfT.MakeGenericMethod($resultType).Invoke($null, @($op))
    [void]$task.Wait(-1)
    $task.Result
}
function AwaitAction($op) { [void]$asTaskAction.Invoke($null, @($op)).Wait(-1) }

function Find-Tv {
    $selector = [Windows.Media.Audio.AudioPlaybackConnection]::GetDeviceSelector()
    $all = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector)) ([Windows.Devices.Enumeration.DeviceInformationCollection])
    return @{ All = $all; Tv = ($all | Where-Object { $_.Name -like "*$Name*" } | Select-Object -First 1) }
}

# Connects and blocks while audio is flowing; returns when the link closes or can't be opened.
function Receive-Tv($device) {
    $connection = [Windows.Media.Audio.AudioPlaybackConnection]::TryCreateFromId($device.Id)
    if (-not $connection) { return "Windows refused to create an audio connection for $($device.Name)." }
    try {
        AwaitAction ($connection.StartAsync())
        $result = Await ($connection.OpenAsync()) ([Windows.Media.Audio.AudioPlaybackConnectionOpenResult])
        if ($result.Status -ne 'Success') { return "Could not open audio from $($device.Name): $($result.Status)" }
        Write-Log "Receiving audio from $($device.Name)."
        while ($connection.State -eq 'Opened') { Start-Sleep -Seconds 5 }
        return "Connection to $($device.Name) closed ($($connection.State))."
    } finally {
        $connection.Dispose()
    }
}

# -Watch runs hidden, so it logs to a small file instead of the console.
$logFile = Join-Path $env:LOCALAPPDATA 'TRG-TV\tv-audio-receiver.log'
function Write-Log([string]$text) {
    $line = "{0:yyyy-MM-dd HH:mm:ss}  {1}" -f (Get-Date), $text
    if (-not $Watch) { $line; return }
    New-Item -ItemType Directory -Force (Split-Path $logFile) | Out-Null
    if ((Test-Path $logFile) -and (Get-Item $logFile).Length -gt 256KB) { Move-Item $logFile "$logFile.old" -Force }
    Add-Content -Path $logFile -Value $line -Encoding utf8
}

if ($Watch) {
    # Keep one started connection alive the whole time: Windows then accepts the audio link when the TV
    # connects (TV: Remotes & Accessories > this PC > Connect), and we also retry opening it from here.
    # The TV is often off or asleep, so retries are quiet and only changes are logged.
    Write-Log "Watching for '$Name'."
    $last = ''
    $connection = $null
    while ($true) {
        try {
            if (-not $connection) {
                $tv = (Find-Tv).Tv
                if (-not $tv) { throw "No paired device matching '$Name'." }
                $connection = [Windows.Media.Audio.AudioPlaybackConnection]::TryCreateFromId($tv.Id)
                if (-not $connection) { throw "Windows refused to create an audio connection for $($tv.Name)." }
                AwaitAction ($connection.StartAsync())
            }
            if ($connection.State -eq 'Opened') {
                $status = 'Receiving TV audio.'
            } else {
                $result = Await ($connection.OpenAsync()) ([Windows.Media.Audio.AudioPlaybackConnectionOpenResult])
                $status = if ($result.Status -eq 'Success') { 'Receiving TV audio.' } else { "Waiting for the TV ($($result.Status))." }
            }
        } catch {
            $status = "Error: $($_.Exception.Message)"
            if ($connection) { $connection.Dispose(); $connection = $null }
        }
        if ($status -ne $last) { Write-Log $status; $last = $status }
        Start-Sleep -Seconds 10
    }
}

$found = Find-Tv
if ($List -or $found.All.Count -eq 0) {
    "Paired devices that can send audio to this PC: $($found.All.Count)"
    $found.All | ForEach-Object { "  $($_.Name)" }
    if ($found.All.Count -eq 0) { 'None yet - pair the TV with this PC first.' }
    return
}
if (-not $found.Tv) { throw "No paired device matching '$Name'. Run with -List to see names." }
"Connecting; leave this window open, Ctrl+C to stop."
Receive-Tv $found.Tv
