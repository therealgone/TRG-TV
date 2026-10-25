# Makes this Windows PC a Bluetooth speaker for the TV (A2DP sink), so TV audio plays on the PC's
# current output (e.g. your headphones) mixed with the PC's own audio.
# Uses Windows' built-in AudioPlaybackConnection API (Windows 10 2004+ / Windows 11); nothing to install.
#
# One-time: pair the TV with this PC (TV: Settings > Remotes & Accessories > Add accessory, while
# Windows Settings > Bluetooth & devices > Add device is open on the PC).
#
# Usage:
#   .\tv-audio-receiver.ps1 -List            - show paired devices that can send audio to this PC
#   .\tv-audio-receiver.ps1 [-Name SONY]     - connect and keep receiving until Ctrl+C
param(
    [string]$Name = 'SONY',
    [switch]$List
)

$ErrorActionPreference = 'Stop'
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

$selector = [Windows.Media.Audio.AudioPlaybackConnection]::GetDeviceSelector()
$devices = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($selector)) ([Windows.Devices.Enumeration.DeviceInformationCollection])

if ($List -or $devices.Count -eq 0) {
    "Paired devices that can send audio to this PC: $($devices.Count)"
    $devices | ForEach-Object { "  $($_.Name)" }
    if ($devices.Count -eq 0) { 'None yet - pair the TV with this PC first.' }
    return
}

$device = $devices | Where-Object { $_.Name -like "*$Name*" } | Select-Object -First 1
if (-not $device) { throw "No paired device matching '$Name'. Run with -List to see names." }

$connection = [Windows.Media.Audio.AudioPlaybackConnection]::TryCreateFromId($device.Id)
if (-not $connection) { throw "Windows refused to create an audio connection for $($device.Name)." }
AwaitAction ($connection.StartAsync())
$result = Await ($connection.OpenAsync()) ([Windows.Media.Audio.AudioPlaybackConnectionOpenResult])
if ($result.Status -ne 'Success') { throw "Could not open audio from $($device.Name): $($result.Status) $($result.ExtendedError)" }

"Receiving audio from $($device.Name). Leave this window open; Ctrl+C to stop."
try {
    while ($true) {
        Start-Sleep -Seconds 5
        if ($connection.State -ne 'Opened') {
            "Connection dropped ($($connection.State)); reopening..."
            $result = Await ($connection.OpenAsync()) ([Windows.Media.Audio.AudioPlaybackConnectionOpenResult])
        }
    }
} finally {
    $connection.Dispose()
}
