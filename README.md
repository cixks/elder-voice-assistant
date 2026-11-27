# 小伴语音助手（家庭试用原型）

原生 Android / Java，最低 Android 8.0（API 26），目标 API 35。不内置大模型、不使用 WebView。

**当前状态：0.3.0 已在 GitHub 云端编译发布（`trial-5-1`）；本地 0.4.0 修改尚未编译，也未进行真机验证。** 按用户要求，进入新一轮 APK 编译前必须先向用户确认；且固定签名 Secrets 配置完成后方可编译。

## 0.4.0 本地修改（待编译）

1. **回答不自动关闭 + 重播按钮 + 点空白关闭**：
   - 语音回答播报结束后保留面板和文字答案，显示“重新播放当前语音”按钮，可随时重听。
   - 移除 `FLAG_NOT_TOUCH_MODAL` 并启用 `setFinishOnTouchOutside(true)`，点击面板外空白区域即可关闭窗口（不误触背后应用）。
2. **高德无地图界面步行导航（`AmapNavigator` + `WalkNaviService`）**：
   - 接入 `com.amap.api:navi-3dmap-location-search:latest.integration`（仅打包 `armeabi-v7a`、`arm64-v8a`）。
   - 不加载 `AMapNaviView` 地图控件，通过 `AMapLocationClient` 城市定位、`PoiSearchV2` 地点检索（同名多结果时大按钮点选确认）和 `WalkNaviService` 定位前台服务 + `AMapNavi` 实现后台/熄屏语音步行导航。
   - 高德 API Key 在“设置”中加密保存，运行时动态设置，不写进仓库源码；未填写高德密钥时回退外部地图入口。
3. **固定 APK 签名支持**：
   - `app/build.gradle` 与 `.github/workflows/build-apk.yml` 支持读取 GitHub Actions 仓库 Secrets（`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`）进行固定签名，并在构建日志输出 `SHA1` 供高德开放平台绑定。
   - **首次启用固定签名与旧版（`0.1.0`~`0.3.0`）临时 debug 签名不同，需卸载旧版一次；之后所有新版本即可直接覆盖安装。**
4. **拼多多黑图诊断增强**：
   - `ScreenCaptureService` 增加 `buffer.rewind()` 和分辨率/行距/暗像素占比/耗时诊断记录。
   - `AssistantActivity` 截屏隐藏时同时隐藏窗口 DecorView。
   - `SettingsActivity` 增加“截图预览测试”，支持仅内存预览捕获画面并查看诊断信息（识别是页面 `FLAG_SECURE` 防截屏还是窗口遮挡）。

## 固定签名配置步骤（GitHub Secrets，不写入仓库）

在本机或任意装有 JDK 的环境中生成一次专用签名并妥善备份（勿提交到仓库）：

```bash
keytool -genkeypair -v -keystore littlecompanion-fixed.jks -alias littlecompanion -keyalg RSA -keysize 2048 -validity 10000
```

然后将其转为单行 Base64，在 GitHub 仓库 **Settings → Secrets and variables → Actions** 中添加 4 个 Repository Secrets：
- `SIGNING_KEYSTORE_BASE64`：`.jks` 文件的 Base64 内容
- `SIGNING_STORE_PASSWORD`：keystore 密码
- `SIGNING_KEY_ALIAS`：`littlecompanion`
- `SIGNING_KEY_PASSWORD`：key 密码

## 权限与隐私

- 悬浮窗：家人手动开启，前台服务提供常驻通知。
- 麦克风：仅在可见语音面板内按需录制；切换到其他 APP 时停止，屏幕授权流程除外。
- 定位：仅在同意高德隐私说明并使用无地图步行导航时申请 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`，由 `WalkNaviService` 前台定位服务在导航期间使用。
- 通知：Android 13+ 建议允许，方便了解和关闭悬浮球、屏幕共享与步行导航服务。
- 屏幕：首次授权后当前会话复用，关闭悬浮窗或共享通知结束；截图不落盘。
- 家庭自用限制：高德个人开发者免费配额仅限非商业个人/家庭自用，不得用于商业运营；首次导航务必由家人陪同测试。

## 文件结构

`MainActivity` 简洁首页；`SettingsActivity` 家人设置与截图预览诊断；`OverlayService` 悬浮球；`AssistantActivity` 对话状态、重播、点空白关闭与导航确认；`VoiceInput` 录音/识别；`VoiceOutput` 系统播报与导航语音焦点；`ApiClient` HTTPS 接口；`AssistantBrain` 意图和回答规则；`ScreenCaptureService` 单帧屏幕捕获；`AmapNavigator` 高德定位与 POI 搜索；`WalkNaviService` 无地图前台步行导航服务；`MapLauncher` 外部地图备用入口。

验收项目见 `docs/TEST-CHECKLIST.md`。
