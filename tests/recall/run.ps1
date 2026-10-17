<# Run build.ps1 first. Native tests use only a fresh synthetic database on the
   explicitly selected arm64 Android device; no installed app data is opened. #>
param(
    [string]$Serial = '',
    [string]$SdkRoot = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$NdkPath = '',
    [string]$AndroidJar = ''
)
$ErrorActionPreference = 'Stop'
$recallRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $recallRoot
try {
    if (-not (Test-Path build/app)) { throw 'Run build.ps1 first.' }
    if (-not $AndroidJar) {
        $AndroidJar = (Get-ChildItem "$SdkRoot/platforms" -Filter android.jar -Recurse |
            Sort-Object FullName -Descending | Select-Object -First 1).FullName
    }
    if (-not $AndroidJar) { throw 'android.jar not found; pass -AndroidJar.' }
    $recallOut = Join-Path $recallRoot 'build/recall-tests'
    New-Item -ItemType Directory -Force -Path $recallOut | Out-Null
    $recallCp = "build/app;build/libxposed/classes.jar;$AndroidJar"
    $recallTests = @(
        'tests/recall/RecallBehaviorTest.java',
        'tests/recall/RecallAuditRegressionTest.java',
        'docs/libxposed-api-102-migration/records/phase-4/workbench/HookMigrationBehaviorTest.java',
        'docs/libxposed-api-102-migration/records/audit-fixes/workbench/AuditFixBehaviorTest.java'
    )
    & javac -encoding UTF-8 -source 8 -target 8 -cp $recallCp -d $recallOut $recallTests
    if ($LASTEXITCODE) { throw 'Java test compilation failed.' }
    foreach ($recallTest in @('RecallBehaviorTest', 'RecallAuditRegressionTest', 'HookMigrationBehaviorTest', 'AuditFixBehaviorTest')) {
        & java -cp "$recallOut;$recallCp" "com.chekayo.feishuantirecall.$recallTest"
        if ($LASTEXITCODE) { throw "$recallTest failed." }
    }
    if ($Serial) {
        if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial.' }
        if (-not $NdkPath) {
            $NdkPath = (Get-ChildItem "$SdkRoot/ndk" -Directory |
                Sort-Object Name -Descending | Select-Object -First 1).FullName
        }
        $recallClang = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
        $recallBinary = Join-Path $recallOut 'native-test'
        & $recallClang --target=aarch64-linux-android29 -std=c++17 -O2 -static-libstdc++ -fPIE -pie `
            -o $recallBinary tests/recall/native_test.cpp -ldl
        if ($LASTEXITCODE) { throw 'Native test compilation failed.' }
        $recallDeviceDir = '/data/local/tmp/feishukit-recall-test-' + [guid]::NewGuid().ToString('N')
        & adb -s $Serial shell mkdir $recallDeviceDir
        if ($LASTEXITCODE) { throw 'Cannot create device test directory.' }
        & adb -s $Serial push $recallBinary "$recallDeviceDir/native-test"
        if ($LASTEXITCODE) { throw 'Cannot push native test.' }
        & adb -s $Serial shell "chmod 755 $recallDeviceDir/native-test; cd $recallDeviceDir && ./native-test /system/lib64/libsqlite.so"
        if ($LASTEXITCODE) { throw "Native tests failed; artifacts retained in $recallDeviceDir" }
        Write-Host "Synthetic test database: $recallDeviceDir"
    }
} finally { Pop-Location }
