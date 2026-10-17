<# Build first. Runs real Android Views in an isolated shell app_process;
   all message IDs and marker files are synthetic. Does not open Feishu data. #>
param(
    [string]$Serial = '',
    [switch]$CompileOnly,
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk"
)
$ErrorActionPreference = 'Stop'
if (-not $CompileOnly -and $Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Pass -Serial or -CompileOnly.' }
$recallRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $recallRoot
try {
    if (-not (Test-Path build/app)) { throw 'Run build.ps1 first.' }
    $recallAndroidJar = (Get-ChildItem "$SdkRoot/platforms" -Filter android.jar -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1).FullName
    $recallOut = Join-Path $recallRoot ('build/translation-ui-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path "$recallOut/classes", "$recallOut/dex" | Out-Null
    & javac -encoding UTF-8 -source 8 -target 8 -cp "build/app;build/libxposed/classes.jar;$recallAndroidJar" `
        -d "$recallOut/classes" tests/recall/RecallTranslationDeviceTest.java
    if ($LASTEXITCODE) { throw 'UI test compilation failed.' }
    & jar cf "$recallOut/test.jar" -C "$recallOut/classes" . -C build/app .
    if ($LASTEXITCODE) { throw 'UI test packaging failed.' }
    # API classes belong only in this standalone test harness, never in the module APK.
    $recallDexArgs = @('--min-api', '29', '--lib', $recallAndroidJar,
        '--output', "$recallOut/dex", "$recallOut/test.jar", 'build/libxposed/classes.jar')
    if (Test-Path tools/r8.jar) {
        & java -cp tools/r8.jar com.android.tools.r8.D8 @recallDexArgs
    } else {
        $recallD8 = (Get-ChildItem "$SdkRoot/build-tools" -Filter d8.bat -Recurse |
            Sort-Object FullName -Descending | Select-Object -First 1).FullName
        if (-not $recallD8) { throw 'Android D8 not found.' }
        & $recallD8 @recallDexArgs
    }
    if ($LASTEXITCODE) { throw 'UI test dex conversion failed.' }
    & jar cf "$recallOut/test-dex.jar" -C "$recallOut/dex" .
    if ($LASTEXITCODE) { throw 'UI test dex packaging failed.' }
    if ($CompileOnly) { Write-Host "Compiled Android UI tests (not run): $recallOut/test-dex.jar"; return }
    $recallDeviceDir = '/data/local/tmp/feishukit-translation-test-' + [guid]::NewGuid().ToString('N')
    & adb -s $Serial shell mkdir $recallDeviceDir
    if ($LASTEXITCODE) { throw 'Cannot create device test directory.' }
    & adb -s $Serial push "$recallOut/test-dex.jar" "$recallDeviceDir/test.jar"
    if ($LASTEXITCODE) { throw 'Cannot push UI test.' }
    & adb -s $Serial shell "CLASSPATH=$recallDeviceDir/test.jar app_process /system/bin com.chekayo.feishuantirecall.RecallTranslationDeviceTest $recallDeviceDir"
    if ($LASTEXITCODE) { throw "UI tests failed; artifacts retained in $recallDeviceDir" }
} finally { Pop-Location }
