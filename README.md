# Nothing Compass Widget 🧭

一个 **Nothing 风格（Ndot 点阵）的 Android 指南针桌面小组件**，从零手写、纯 Java 实现，不依赖 Gradle / Android Studio。

透明背景，点阵字体 + 点阵箭头，实时显示方向、角度和经纬度，点击可跳转到系统指南针 App。

## ✨ 功能特性

- **1×1 桌面小组件**，透明背景，融入任意壁纸
- **Ndot 点阵字体**（Nothing 风格）渲染 N / E / S / W 方位和数字
- **点阵箭头**（红色圆点拼成），实时指向磁北
- **平滑滤波**，指针不抖（低通滤波 + 360° 回绕处理）
- **实时显示角度 + 经纬度**（`纬度N 经度E`）
- **点击组件**跳转到系统自带指南针 App

## 🖼️ 预览

<video src="preview.mp4" controls width="320"></video>

```
        N
   W    ▲    E
        ·（点阵箭头随方向旋转）
   45  28.23N  121.00E
        S
```

## 📦 直接安装

仓库根目录的 [`compass-widget.apk`](./compass-widget.apk) 是已编译好的 APK（arm64 通用，签名已包含），可直接：

```bash
adb install compass-widget.apk
```

安装后：长按桌面空白 → 小组件 → 找到 **「指南针·透明」** → 拖到桌面。

## 🔨 从源码构建

**环境要求：**

- JDK 17（`javac`、`keytool`、`jar`）
- Android SDK `build-tools 34.0.0`（`aapt2`、`d8`、`zipalign`、`apksigner`）
- Android SDK `platforms/android-34`（`android.jar`）

**一键构建（Windows PowerShell）：**

```powershell
.\build.ps1
```

构建产物在 `build/compasswidget2.apk`。

### 手动构建步骤

本项目**刻意不用 Gradle**，只用 Android SDK 自带工具，构建流程完全透明：

```bash
BT="你的SDK路径/build-tools/34.0.0"
PLATFORM="你的SDK路径/platforms/android-34/android.jar"

# 1. 编译资源
$BT/aapt2.exe compile --dir res -o build/compiled/res.zip

# 2. 链接（生成 R.java + 基础 APK，打包 assets 字体）
$BT/aapt2.exe link -o build/base.apk -I $PLATFORM --manifest AndroidManifest.xml \
  -R build/compiled/res.zip --java build/gen -A assets --auto-add-overlay

# 3. 编译 Java
javac -encoding UTF-8 -classpath $PLATFORM -d build/classes \
  $(find src -name '*.java') build/gen/com/example/compasswidget2/R.java

# 4. 转 dex
$BT/d8.bat --release --lib $PLATFORM --output build/dex \
  $(find build/classes -name '*.class')

# 5. 打包 dex 进 APK
cp build/base.apk build/unsigned.apk
(cd build/dex && jar -uf ../unsigned.apk classes.dex)

# 6. 对齐 + 签名（首次需先生成 keystore）
$BT/zipalign.exe -f 4 build/unsigned.apk build/aligned.apk
$BT/apksigner.bat sign --ks build/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --out build/compasswidget2.apk build/aligned.apk
```

## 🛠️ 技术细节

| 模块 | 说明 |
|---|---|
| `CompassService.java` | 前台服务：读磁力计 + 加速度计算方位角，画点阵位图，更新组件 |
| `CompassWidgetProvider.java` | 组件生命周期：添加时启动服务，移除时停止 |
| `assets/ndot.otf` | Ndot 点阵字体（Nothing 风格） |
| 平滑算法 | 低通滤波（α=0.12）+ 角度回绕处理，消除抖动 |
| 点阵箭头 | 9×7 圆点矩阵拼成的箭头，`canvas.rotate` 旋转指向磁北 |

## ⚠️ 定位说明（重要）

组件会显示经纬度，需要**定位权限**。在部分 ColorOS / OPLUS 机型上，系统会拦截**后台应用**的定位请求（`OplusPermissionInterceptPolicy: not foreground app, reject it`），导致经纬度显示 `--.--`。

**解决（需要 root）：**

```bash
# 授予定位权限
adb shell su -c 'pm grant com.example.compasswidget2 android.permission.ACCESS_FINE_LOCATION'
adb shell su -c 'pm grant com.example.compasswidget2 android.permission.ACCESS_COARSE_LOCATION'

# 关闭 OPlus 后台权限拦截（关键！否则后台组件拿不到定位）
adb shell su -c 'setprop persist.sys.permission.enable false'
```

> `persist.sys.permission.enable=false` 是 persist 属性，重启后依然生效；副作用是其它后台应用也能访问权限（对去广告、深度定制的机器通常反而是好事）。恢复：`setprop persist.sys.permission.enable true`。

## 📄 字体来源

`assets/ndot.otf`（NDot57Caps）提取自 [rjwarrier/KWGT-Widgets](https://github.com/rjwarrier/KWGT-Widgets) 的 Nothing 风格预设（`.kwgt` 内的 `fonts/NDot57Caps.otf`）。该字体为 Nothing 风格的点阵字体，版权归原作者 / Nothing 所有，仅供学习使用。

## 📄 许可

MIT License，详见 [LICENSE](./LICENSE)。

---

*一个在 OnePlus 7T Pro（ColorOS 12.1 / Android 12，root）上从零定制、手写编译的 Nothing 风格组件。*
