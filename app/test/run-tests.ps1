# Compiles and runs the plain-Java tests in app\test (no Android needed).
# Usage: .\app\test\run-tests.ps1 [path\to\captured-logcat.txt]
$ErrorActionPreference = 'Stop'
$app = Split-Path $PSScriptRoot
$out = "$app\build\test-classes"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$ErrorActionPreference = 'Continue'
& javac --release 11 -encoding UTF-8 -nowarn -d $out "$app\src\com\treadmill\home\IfitLog.java" "$app\test\IfitLogTest.java"
if ($LASTEXITCODE) { throw 'javac failed' }
& java -cp $out IfitLogTest @args
if ($LASTEXITCODE) { throw 'tests failed' }
