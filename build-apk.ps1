# Builds a dependency-free Android app (plain Java, no Gradle) into a signed APK.
# Usage: .\build-apk.ps1 <project dir>   (expects AndroidManifest.xml, src\, optional res\)
param([Parameter(Mandatory)][string]$Project)

$ErrorActionPreference = 'Stop'
# JDK 17: JAVA_HOME if set, else the first JDK unzipped into ~\jdk (README setup).
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
    $env:JAVA_HOME = (Get-ChildItem "$env:USERPROFILE\jdk" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
}
if (-not $env:JAVA_HOME) { throw 'JDK 17 not found: set JAVA_HOME or unzip a JDK into ~\jdk (see README).' }
# Android SDK: ANDROID_HOME / ANDROID_SDK_ROOT if set, else the default Windows location.
$sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $sdk) { throw 'Android SDK not found: set ANDROID_HOME (see README).' }
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$keystore = Join-Path $PSScriptRoot 'debug.keystore'

$Project = (Resolve-Path $Project).Path
$out = Join-Path $Project 'build'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$exe failed ($LASTEXITCODE)" }
}

# Resources (optional) + manifest -> base APK
$link = @('link', '-o', "$out\base.apk", '-I', $jar, '--manifest', "$Project\AndroidManifest.xml", '--java', "$out\gen")
if (Test-Path "$Project\res") {
    Run "$bt\aapt2.exe" @('compile', '--dir', "$Project\res", '-o', "$out\res.zip")
    $link += "$out\res.zip"
}
Run "$bt\aapt2.exe" $link

# Java -> classes -> dex
$sources = @(Get-ChildItem "$Project\src", "$out\gen" -Recurse -Filter *.java -ErrorAction SilentlyContinue | ForEach-Object FullName)
Run "$env:JAVA_HOME\bin\javac.exe" (@('-source', '11', '-target', '11', '-encoding', 'UTF-8', '-classpath', $jar, '-d', "$out\classes") + $sources)
$classes = @(Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName)
Run "$bt\d8.bat" (@('--release', '--min-api', '26', '--lib', $jar, '--output', "$out\dex") + $classes)

# Add classes.dex, align, sign
Copy-Item "$out\base.apk" "$out\unsigned.apk"
Push-Location "$out\dex"; try { Run "$env:JAVA_HOME\bin\jar.exe" @('uf', "$out\unsigned.apk", 'classes.dex') } finally { Pop-Location }
Run "$bt\zipalign.exe" @('-f', '4', "$out\unsigned.apk", "$out\aligned.apk")
if (-not (Test-Path $keystore)) {
    Run "$env:JAVA_HOME\bin\keytool.exe" @('-genkeypair', '-keystore', $keystore, '-storepass', 'android', '-keypass', 'android',
        '-alias', 'tvhome', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=tvhome')
}
Run "$bt\apksigner.bat" @('sign', '--ks', $keystore, '--ks-pass', 'pass:android', '--out', "$out\app.apk", "$out\aligned.apk")
"Built $out\app.apk"
