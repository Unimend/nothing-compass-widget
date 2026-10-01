# Nothing Compass Widget — 构建脚本（Windows PowerShell）
# 依赖：JDK 17 + Android SDK build-tools 34.0.0 + platforms/android-34
# 用法：.\build.ps1

$ErrorActionPreference = "Stop"

# --- 定位 Android SDK ---
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$sdk\build-tools\34.0.0\aapt2.exe")) {
    Write-Error "找不到 Android SDK (build-tools 34.0.0)。请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME。"
}

$BT = "$sdk\build-tools\34.0.0"
$PLATFORM = "$sdk\platforms\android-34\android.jar"
if (-not (Test-Path $PLATFORM)) {
    Write-Error "找不到 android.jar，请安装 platforms/android-34。"
}

$PROJ = $PSScriptRoot
$OUT = "$PROJ\build"

Write-Host "===== 清理旧产物 =====" -ForegroundColor Cyan
Remove-Item "$OUT\compiled","$OUT\gen","$OUT\classes","$OUT\dex" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$OUT\compiled","$OUT\gen","$OUT\classes","$OUT\dex" | Out-Null

Write-Host "===== 1. aapt2 编译资源 =====" -ForegroundColor Cyan
& "$BT\aapt2.exe" compile --dir "$PROJ\res" -o "$OUT\compiled\res.zip"

Write-Host "===== 2. aapt2 链接（打包 assets 字体）=====" -ForegroundColor Cyan
& "$BT\aapt2.exe" link -o "$OUT\base.apk" -I $PLATFORM --manifest "$PROJ\AndroidManifest.xml" `
    -R "$OUT\compiled\res.zip" --java "$OUT\gen" -A "$PROJ\assets" --auto-add-overlay

Write-Host "===== 3. javac 编译 Java =====" -ForegroundColor Cyan
$srcs = (Get-ChildItem "$PROJ\src" -Recurse -Filter *.java).FullName
$rjava = (Get-ChildItem "$OUT\gen" -Recurse -Filter R.java).FullName
& javac -encoding UTF-8 -classpath $PLATFORM -d "$OUT\classes" (@($srcs) + @($rjava))

Write-Host "===== 4. d8 转 dex =====" -ForegroundColor Cyan
$classes = (Get-ChildItem "$OUT\classes" -Recurse -Filter *.class).FullName
& "$BT\d8.bat" --release --lib $PLATFORM --output "$OUT\dex" $classes

Write-Host "===== 5. 打包 dex 进 APK =====" -ForegroundColor Cyan
Copy-Item "$OUT\base.apk" "$OUT\unsigned.apk" -Force
Push-Location "$OUT\dex"
& jar -uf "$OUT\unsigned.apk" classes.dex
Pop-Location

Write-Host "===== 6. zipalign =====" -ForegroundColor Cyan
& "$BT\zipalign.exe" -f 4 "$OUT\unsigned.apk" "$OUT\aligned.apk"

Write-Host "===== 7. 生成密钥（如不存在）+ 签名 =====" -ForegroundColor Cyan
if (-not (Test-Path "$OUT\debug.keystore")) {
    & keytool -genkeypair -v -keystore "$OUT\debug.keystore" -alias androiddebugkey `
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}
& "$BT\apksigner.bat" sign --ks "$OUT\debug.keystore" --ks-pass pass:android `
    --key-pass pass:android --ks-key-alias androiddebugkey `
    --out "$OUT\compasswidget2.apk" "$OUT\aligned.apk"

Write-Host ""
Write-Host "✅ 构建完成：$OUT\compasswidget2.apk" -ForegroundColor Green
