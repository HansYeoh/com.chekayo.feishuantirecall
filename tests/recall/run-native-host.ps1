<# Compiles the production header against a host SQLite library in a fresh synthetic DB directory. #>
param(
    [Parameter(Mandatory = $true)][string]$SqliteLibrary,
    [string]$CompilerEnvironment = "${env:ProgramFiles(x86)}/Microsoft Visual Studio/2022/BuildTools/VC/Auxiliary/Build/vcvars64.bat"
)
$ErrorActionPreference = 'Stop'
$recallRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$recallLibrary = (Resolve-Path -LiteralPath $SqliteLibrary).Path
$recallEnvironment = (Resolve-Path -LiteralPath $CompilerEnvironment).Path
# Only obtain the compiler environment from cmd; file operations stay in PowerShell.
& $env:ComSpec /d /c "call `"$recallEnvironment`" >nul && set" | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
if ($LASTEXITCODE) { throw 'Cannot initialize compiler environment.' }
$recallOut = Join-Path $recallRoot ('tmp-out/native-regression-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $recallOut | Out-Null
Push-Location $recallOut
try {
    & cl.exe /nologo /EHsc /std:c++17 /utf-8 /O2 (Join-Path $recallRoot 'tests/recall/native_test.cpp') /Fe:native-test.exe /Fo:native-test.obj
    if ($LASTEXITCODE) { throw 'Native host test compilation failed.' }
    & ./native-test.exe $recallLibrary
    if ($LASTEXITCODE) { throw "Native tests failed; artifacts retained in $recallOut" }
    Write-Host "Synthetic test database: $recallOut"
} finally { Pop-Location }
