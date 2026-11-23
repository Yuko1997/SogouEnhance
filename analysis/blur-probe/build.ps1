[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ProjectRoot = (Resolve-Path $PSScriptRoot).Path
$BuildRoot = Join-Path $ProjectRoot 'build'
$SigningRoot = Join-Path $ProjectRoot 'signing'
New-Item -ItemType Directory -Force -Path $SigningRoot | Out-Null

if (Test-Path -LiteralPath $BuildRoot) {
    $resolvedBuild = (Resolve-Path -LiteralPath $BuildRoot).Path
    $expectedPrefix = $ProjectRoot.TrimEnd('\') + '\'
    if (-not $resolvedBuild.StartsWith(
            $expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected path: $resolvedBuild"
    }
    Remove-Item -LiteralPath $BuildRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null

$JdkBin = if ($env:JAVA_HOME) {
    Join-Path $env:JAVA_HOME 'bin'
} else {
    Split-Path (Get-Command javac.exe -ErrorAction Stop).Source
}

$AndroidSdk = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}

$BuildTools = Get-ChildItem (Join-Path $AndroidSdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$AndroidJar = Get-ChildItem (Join-Path $AndroidSdk 'platforms') -Directory |
    Sort-Object {
        $value = $_.Name -replace '^android-', ''
        if ($value -notmatch '\.') { $value = "$value.0" }
        [version]$value
    } -Descending |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1

$ClassesDir = Join-Path $BuildRoot 'classes'
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
$sources = Get-ChildItem (Join-Path $ProjectRoot 'src') `
    -Recurse -Filter '*.java' | ForEach-Object FullName

& (Join-Path $JdkBin 'javac.exe') `
    -encoding UTF-8 -source 8 -target 8 -nowarn `
    -cp $AndroidJar -d $ClassesDir $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$ClassesJar = Join-Path $BuildRoot 'classes.jar'
& (Join-Path $JdkBin 'jar.exe') cf $ClassesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

& (Join-Path $BuildTools 'd8.bat') `
    --min-api 28 --lib $AndroidJar --output $BuildRoot $ClassesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

$BaseApk = Join-Path $BuildRoot 'base.apk'
& (Join-Path $BuildTools 'aapt2.exe') link `
    -o $BaseApk -I $AndroidJar `
    --manifest (Join-Path $ProjectRoot 'AndroidManifest.xml') `
    --min-sdk-version 28 --target-sdk-version 36
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$UnsignedApk = Join-Path $BuildRoot 'unsigned.apk'
Copy-Item -LiteralPath $BaseApk -Destination $UnsignedApk
Push-Location $BuildRoot
try {
    & (Join-Path $BuildTools 'aapt.exe') add unsigned.apk classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'aapt add failed' }
} finally {
    Pop-Location
}

$AlignedApk = Join-Path $BuildRoot 'aligned.apk'
& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$Keystore = Join-Path $SigningRoot 'test.keystore'
if (-not (Test-Path -LiteralPath $Keystore)) {
    & (Join-Path $JdkBin 'keytool.exe') -genkeypair `
        -keystore $Keystore -alias probe -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname 'CN=BlurProbe, O=Local, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$ProbeApk = Join-Path $ProjectRoot 'BlurProbe.apk'
& (Join-Path $BuildTools 'apksigner.bat') sign `
    --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --v2-signing-enabled true --v3-signing-enabled true `
    --out $ProbeApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

& (Join-Path $BuildTools 'apksigner.bat') verify $ProbeApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ProbeApk).Hash
Write-Host "APK: $ProbeApk"
Write-Host "SHA256: $hash"
