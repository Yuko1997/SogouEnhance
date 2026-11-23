[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ModuleRoot = (Resolve-Path $PSScriptRoot).Path
$BuildRoot = Join-Path $ModuleRoot 'build'
$SigningRoot = Join-Path $ModuleRoot 'signing'
New-Item -ItemType Directory -Force -Path $SigningRoot | Out-Null
if (Test-Path -LiteralPath $BuildRoot) {
    $resolvedBuild = (Resolve-Path -LiteralPath $BuildRoot).Path
    $expectedPrefix = $ModuleRoot.TrimEnd('\') + '\'
    if (-not $resolvedBuild.StartsWith(
            $expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected path: $resolvedBuild"
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
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

$BuildToolsRoot = Join-Path $AndroidSdk 'build-tools'
$BuildTools = Get-ChildItem -LiteralPath $BuildToolsRoot -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$PlatformsRoot = Join-Path $AndroidSdk 'platforms'
$AndroidJar = Get-ChildItem -LiteralPath $PlatformsRoot -Directory |
    Sort-Object {
        $value = $_.Name -replace '^android-', ''
        if ($value -notmatch '\.') { $value = "$value.0" }
        [version]$value
    } -Descending |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } |
    Where-Object { Test-Path -LiteralPath $_ } |
    Select-Object -First 1
$ApiJar = Join-Path $ModuleRoot 'libs\libxposed-api-102.jar'

$required = @(
    (Join-Path $JdkBin 'javac.exe'),
    (Join-Path $JdkBin 'jar.exe'),
    (Join-Path $JdkBin 'keytool.exe'),
    $AndroidJar,
    $ApiJar,
    (Join-Path $BuildTools 'd8.bat'),
    (Join-Path $BuildTools 'aapt.exe'),
    (Join-Path $BuildTools 'aapt2.exe'),
    (Join-Path $BuildTools 'zipalign.exe'),
    (Join-Path $BuildTools 'apksigner.bat')
)
foreach ($path in $required) {
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing build dependency: $path"
    }
}

$ClassesDir = Join-Path $BuildRoot 'classes'
New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
$GeneratedDir = Join-Path $BuildRoot 'generated'
New-Item -ItemType Directory -Force -Path $GeneratedDir | Out-Null

$CompiledRes = Join-Path $BuildRoot 'resources.zip'
& (Join-Path $BuildTools 'aapt2.exe') compile `
    --dir (Join-Path $ModuleRoot 'res') -o $CompiledRes
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

$BaseApk = Join-Path $BuildRoot 'base.apk'
& (Join-Path $BuildTools 'aapt2.exe') link `
    -o $BaseApk -I $AndroidJar `
    --manifest (Join-Path $ModuleRoot 'AndroidManifest.xml') `
    --min-sdk-version 28 --target-sdk-version 36 `
    --java $GeneratedDir `
    $CompiledRes
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$sources = Get-ChildItem -LiteralPath (Join-Path $ModuleRoot 'src') `
    -Recurse -Filter '*.java' | ForEach-Object FullName
$generatedSources = Get-ChildItem -LiteralPath $GeneratedDir `
    -Recurse -Filter '*.java' | ForEach-Object FullName
& (Join-Path $JdkBin 'javac.exe') `
    -encoding UTF-8 -source 8 -target 8 -nowarn `
    -cp "$AndroidJar;$ApiJar" -d $ClassesDir `
    ($sources + $generatedSources)
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$ClassesJar = Join-Path $BuildRoot 'classes.jar'
& (Join-Path $JdkBin 'jar.exe') cf $ClassesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

& (Join-Path $BuildTools 'd8.bat') `
    --min-api 28 --lib $AndroidJar --output $BuildRoot $ClassesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

$UnsignedApk = Join-Path $BuildRoot 'unsigned.apk'
Copy-Item -LiteralPath $BaseApk -Destination $UnsignedApk
Copy-Item -LiteralPath (Join-Path $ModuleRoot 'meta-inf\META-INF') `
    -Destination $BuildRoot -Recurse -Force
Push-Location $BuildRoot
try {
    & (Join-Path $BuildTools 'aapt.exe') add unsigned.apk classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'aapt add failed' }
    & (Join-Path $BuildTools 'aapt.exe') add unsigned.apk `
        META-INF/xposed/java_init.list `
        META-INF/xposed/module.prop `
        META-INF/xposed/scope.list
    if ($LASTEXITCODE -ne 0) { throw 'aapt add metadata failed' }
} finally {
    Pop-Location
}

$AlignedApk = Join-Path $BuildRoot 'aligned.apk'
& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$Keystore = Join-Path $SigningRoot 'test.keystore'
if (-not (Test-Path -LiteralPath $Keystore)) {
    & (Join-Path $JdkBin 'keytool.exe') -genkeypair `
        -keystore $Keystore -alias mod -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname 'CN=SogouEnhance, O=Local, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$ModuleApk = Join-Path $BuildRoot 'SogouEnhance-v1.2.1.apk'
& (Join-Path $BuildTools 'apksigner.bat') sign `
    --ks $Keystore --ks-pass pass:android --key-pass pass:android `
    --v2-signing-enabled true --v3-signing-enabled true `
    --out $ModuleApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner sign failed' }

& (Join-Path $BuildTools 'apksigner.bat') verify --print-certs $ModuleApk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$FinalApk = Join-Path $ModuleRoot 'SogouEnhance-v1.2.1.apk'
Copy-Item -LiteralPath $ModuleApk -Destination $FinalApk -Force
$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $FinalApk).Hash
Remove-Item -LiteralPath $BuildRoot -Recurse -Force
Write-Host ""
Write-Host "APK: $FinalApk"
Write-Host "SHA256: $hash"
