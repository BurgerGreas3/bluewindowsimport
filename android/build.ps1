$ErrorActionPreference = "Stop"
$SDK = "$env:LOCALAPPDATA\Android\Sdk"
if (-not (Test-Path $SDK)) { Write-Error "Android SDK not found at $SDK"; exit 1 }

$BUILD_TOOLS = (Get-ChildItem "$SDK\build-tools" | Sort-Object Name -Descending | Select-Object -First 1).FullName
$PLATFORM = (Get-ChildItem "$SDK\platforms" -Filter "android.jar" -Recurse | Sort-Object FullName -Descending | Select-Object -First 1).FullName

if (-not (Test-Path "debug.keystore")) {
    & keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 -dname "CN=Blue Debug"
}

Remove-Item -Recurse -Force -ErrorAction SilentlyContinue obj, classes.dex, base.apk, aligned.apk, compiled_res.zip
if (Test-Path "src\com\bluebridge\app\R.java") {
    Remove-Item -Force "src\com\bluebridge\app\R.java"
}
New-Item -ItemType Directory -Force -Path obj | Out-Null

& "$BUILD_TOOLS\aapt2.exe" compile --dir res -o compiled_res.zip
& "$BUILD_TOOLS\aapt2.exe" link -I $PLATFORM --manifest AndroidManifest.xml --java src compiled_res.zip -o base.apk

$javaFiles = Get-ChildItem "src\com\bluebridge\app\*.java" | ForEach-Object { $_.FullName }
& javac -encoding UTF8 -cp $PLATFORM -d obj $javaFiles

$classFiles = Get-ChildItem "obj\com\bluebridge\app\*.class" | ForEach-Object { $_.FullName }
& "$BUILD_TOOLS\d8.bat" --lib $PLATFORM --output . $classFiles

# Load .NET assembly for zip file manipulation
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$zip = [System.IO.Compression.ZipFile]::Open((Resolve-Path "base.apk").Path, [System.IO.Compression.ZipArchiveMode]::Update)
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Resolve-Path "classes.dex").Path, "classes.dex") | Out-Null
$zip.Dispose()

& "$BUILD_TOOLS\zipalign.exe" -f 4 base.apk aligned.apk
& "$BUILD_TOOLS\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out "blue.apk" aligned.apk
Write-Host "`n[+] Successfully generated blue.apk!" -ForegroundColor Green
