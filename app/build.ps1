# Builds app\build\treadmill-home.apk without Gradle (aapt2 + javac + d8 + apksigner).
# Usage: .\app\build.ps1
$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$bt = "$sdk\build-tools\35.0.0"
$androidJar = "$sdk\platforms\android-28\android.jar"
$app = $PSScriptRoot
$out = "$app\build"

if (Test-Path $out) { Get-ChildItem $out | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

# Manifest + assets (GlassOS client certs from ..\glassos\certs) -> base APK
# (flat: aapt2 on Windows stores subfolder assets with backslashes, which Android can't open)
New-Item -ItemType Directory -Force "$out\assets" | Out-Null
Copy-Item "$app\..\glassos\certs\client_cert.pem", "$app\..\glassos\certs\client_key.pem" "$out\assets\"
& "$bt\aapt2.exe" compile --dir "$app\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest "$app\AndroidManifest.xml" -A "$out\assets" "$out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

# Java -> classes -> dex
$ErrorActionPreference = 'Continue'  # native tools write warnings to stderr; exit codes are checked instead
$sources = @(Get-ChildItem -Recurse "$app\src" -Filter *.java | ForEach-Object FullName)
& javac --release 11 -encoding UTF-8 -nowarn -classpath $androidJar -d "$out\classes" $sources
if ($LASTEXITCODE) { throw 'javac failed' }
$classFiles = @(Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName)
& "$bt\d8.bat" --min-api 28 --lib $androidJar --output "$out\dex" $classFiles
if ($LASTEXITCODE) { throw 'd8 failed' }

# Add classes.dex to the APK, align, sign with a local debug key
Copy-Item "$out\base.apk" "$out\unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out\unsigned.apk", 'Update')
[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out\dex\classes.dex", 'classes.dex') | Out-Null
$zip.Dispose()
& "$bt\zipalign.exe" -f 4 "$out\unsigned.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

$keystore = "$app\debug.keystore"
if (-not (Test-Path $keystore)) {
    & keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias debug `
        -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Treadmill Home Debug' | Out-Null
}
& "$bt\apksigner.bat" sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --out "$out\treadmill-home.apk" "$out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
"Built $out\treadmill-home.apk"
