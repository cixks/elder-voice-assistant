# 小伴安卓语音助手：跨会话交接

更新日期：2026-11-27（0.4.0 本地开发已写入，待用户配置固定签名 Secrets 并授权后触发云端编译）。

## 1. 项目与用户操作约束

- 本地：`F:\opencode项目\elder-voice-assistant`
- GitHub：`https://github.com/cixks/elder-voice-assistant`，分支 `main`。
- 0.3.0（提交 `1feed34`）云端构建 run `45948293895` 已成功，Release `trial-5-1` 下载直链：`https://github.com/cixks/elder-voice-assistant/releases/download/trial-5-1/elder-voice-assistant.apk`（大小 `56908` 字节）。
- 新一轮编译前仍需询问用户；外网文件不自行下载；PAT 仅从 `F:\opencode项目\gh_token.txt` 读取，绝不输出内容或写入仓库。
- 没有授权启动子代理。

## 2. 本轮 0.4.0 已实现的本地变更（未编译、未真机验证）

1. **回答不自动消失 + 重播按钮 + 点空白关闭**：
   - `AssistantActivity` 移除 `FLAG_NOT_TOUCH_MODAL` 并启用 `setFinishOnTouchOutside(true)`（截屏隐藏期间临时关闭防误关）。
   - 回答与“再说一遍”播报结束后不调用 `finish()`，保持展示文字答案并显示“重新播放当前语音”按钮。
2. **高德无地图界面步行导航**：
   - `gradle.properties` 开启 `android.useAndroidX=true` 与 `android.enableJetifier=true`；`app/build.gradle` 引入 `com.amap.api:navi-3dmap-location-search:latest.integration`，配置 `abiFilters 'armeabi-v7a', 'arm64-v8a'`，版本升至 `versionCode 4` / `versionName '0.4.0'`。
   - `AndroidManifest.xml` 增加定位权限、`FOREGROUND_SERVICE_LOCATION`、`WAKE_LOCK` 和 `WalkNaviService`。
   - 新增 `AmapNavigator`（高德隐私合规设置、动态 `setApiKey`、`AMapLocationClient` 城市定位、`PoiSearchV2` 检索与距离估算）和 `WalkNaviService`（前台定位服务 + 无地图 `AMapNavi` + `VoiceOutput` 导航焦点播报）。
   - `AssistantActivity` 支持多候选地点大按钮点选、无地图导航实时状态卡片、`重播指引`、`结束步行导航`；`SettingsActivity` 支持高德隐私同意勾选、高德 API Key 加密保存与定位权限申请。
3. **固定 APK 签名**：
   - `app/build.gradle` 与 `.github/workflows/build-apk.yml` 已配置 `SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD` 四个 GitHub Secrets 驱动的固定签名，并在 Actions 日志输出 `SHA1`/`SHA256`。
4. **拼多多黑图诊断**：
   - `ScreenCaptureService` 增加 `buffer.rewind()` 与捕获分辨率/行距/暗像素占比/耗时诊断信息；`AssistantActivity` 截屏时同时隐藏 DecorView；`SettingsActivity` 新增“截图预览测试”弹窗（仅内存解码显示并立即回收）。

## 3. 尚未完成 / 阻塞项

- **GitHub Secrets 尚未配置**：目前仓库 Actions Secrets 查询返回 403（PAT 无 Secrets 权限），需要用户先在 GitHub 仓库中添加 4 个固定签名 Secrets；否则 `.github/workflows/build-apk.yml` 会主动报错拦截，防止产出不固定签名 APK。
- **高德 Key 尚未配置**：需用户在得到固定签名 `SHA1` 后，于高德开放平台创建 Android Key（包名 `cn.family.littlecompanion` + 固定 `SHA1`），再在 App 设置页填入。
- **本地尚未提交/推送/触发编译**：等待用户确认签名配置与是否允许提交、推送并触发 0.4.0 云端编译。
