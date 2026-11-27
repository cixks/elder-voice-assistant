# 小伴安卓语音助手：跨会话交接

更新日期：2026-11-27。请先读本文，再检查实际 Git 状态和 GitHub Actions；本文不是实时构建状态。

## 1. 项目与用户操作约束

- 本地：`F:\opencode项目\elder-voice-assistant`
- GitHub：`https://github.com/cixks/elder-voice-assistant`，分支 `main`。
- 用户需要家庭自用 APK，不公开运营；面向不擅长手机操作的长辈，小米或 OPPO 安卓机，确切型号/系统版本尚未提供。
- 需要编译 APK、安装手机等操作时先问用户。用户已明确授权本轮0.3.0编译；未来新修改的构建继续先确认。
- 外网文件不自行下载：提供链接和用途，由用户下载。云端构建下载依赖已在此前获得明确许可。不把APK或SDK自动下载到本机。
- 用户要求PAT仅从 `F:\opencode项目\gh_token.txt` 读取；此文件在仓库外，仅一行。绝不输出内容、不移动、不写入仓库或remote配置。
- 推送方式：运行时将PAT临时拼入HTTPS push URL，Git命令加 `-c credential.helper= -c credential.interactive=false`，不用GCM/gh CLI。捕获输出并脱敏，关闭Git追踪，避免命令错误泄露认证URL。
- 用户起初要求绕过代理，之后明确表示开启了代理让重试；沿用现有可用网络，不擅自改全局代理配置。网络波动失败时用户明确要求多试几次。
- 远程 origin 永远为无密钥地址。提交身份此前使用 `cixks` / `cixks@users.noreply.github.com`（命令级配置，不改全局）。
- 不要覆盖其他项目或读取同目录其他token文件。没有授权启动子代理。

## 2. 用户真正要的产品

1. 首页仅“打开悬浮窗”“关闭悬浮窗”，另有“设置”入口，其余配置全收进设置。
2. 点悬浮球后显示麦克风，不播报“您想问什么”，不自动收音。
3. 设置选择两种录音方式：点击开始再点结束；按住说话松开发送。
4. 录完助手简短复述需求。**最新要求：复述后显示两个稍大的勾/叉按钮，不再语音确认。** 勾执行；叉清空错误需求，显示麦克风重新说。
5. 回答短，一到两句为主，不要长篇大论。普通话不标准、混少量方言。
6. 识图首要场景是拼多多等购物页面价格，不能编造价格；区分起售价、券后价、分期价和已选规格。
7. 用户明确要求减少屏幕授权弹窗，接受保留当前共享会话；不能绕过系统许可。
8. **重要未完成要求：不打开地图页面就能直接语音步行导航。** 用户不接受打开已有地图搜索地点/路线页面冒充导航；此问题已多次指出，下一主任务应优先研究并实现真实导航能力。
9. 体积、RAM、缓存和待机耗电尽量小。不跑本地大模型、不全天监听。集成导航SDK的额外开销需解释。

## 3. 技术与文件

- 原生 Android Java（不是Kotlin），无第三方应用运行库。minSdk26、compile/target35、Java17、AGP8.6.1、Gradle8.7。
- 没有Gradle Wrapper二进制；本机PATH此前没有java/gradle/adb，未做本地构建。
- 包名：`cn.family.littlecompanion`。最新源码versionCode3 / versionName0.3.0。
- `app/src/main/java/cn/family/littlecompanion/`：
  - `MainActivity` 简洁首页。
  - `SettingsActivity` 配置独立页面；语速SeekBar50%～150%，步长1%。
  - `OverlayService` 可拖动悬浮球，specialUse前台服务；关闭时结束屏幕共享。
  - `AssistantActivity` 半透明底部面板、录音状态、意图、勾叉确认、屏幕授权、地图入口。
  - `VoiceInput` 云端AAC/m4a录音、系统SpeechRecognizer；云端手动结束最长60秒，系统可能自行断句。
  - `VoiceOutput` 系统中文TTS、音频焦点；缺少中文引擎会提示。
  - `ApiClient` HttpURLConnection，HTTPS OpenAI兼容接口，无SDK，不跟随重定向。
  - `AssistantBrain` 结构化意图解析和简短回答规则；没有真正联网搜索/天气工具。
  - `Settings` SharedPreferences，Keystore AES-GCM加密不同API密钥。
  - `ScreenCaptureService` MediaProjection + VirtualDisplay + ImageReader，临时图片进程内传递，避免Binder大图限制。
  - `MapLauncher` 仅外部地图入口，**不是独立导航实现**。
- `README.md` 说明；`docs/TEST-CHECKLIST.md` 验收清单，部分描述落后于0.3.0，需要更新。
- 录音结束后删除；进程异常残留在下次启动清理。截图不落盘。上一条回答仅进程内存。

## 4. API配置和实际故障经验

- 对话、视觉、ASR已支持不同base URL / 加密key / model ID。视觉和ASR各有“沿用对话”选项，默认不沿用。
- URL填基础地址，**不填完整endpoint**；代码自动加 `/chat/completions` 或 `/audio/transcriptions`。
- 对话DeepSeek截图曾误填模型 `DeepSeek`，建议官方接口实际ID `deepseek-chat`，不是品牌名。
- 不允许把DeepSeek聊天地址直接当转写接口。
- 用户现用硅基流动ASR，截图配置错误曾为完整endpoint和 `sensevoice-small`，已指导改：
  - base：`https://api.siliconflow.cn/v1`
  - model：`FunAudioLLM/SenseVoiceSmall`
  - 独立硅基流动key，云端勾选、沿用对话不勾选，保存后再测。
- 官方文档：`https://docs.siliconflow.cn/docs/api/audio-transcriptions-post`。
- 云端ASR目前仅OpenAI兼容multipart接口。代码额外发送language/prompt，未验证硅基流动对额外参数和m4a兼容性；如改正配置后仍失败需针对文档核查。
- HTTP400/422提示模型ID/格式，404/405提示接口/模型不存在，401/403提示密钥权限。未实现完整服务商原始错误脱敏显示或独立连接测试。
- 不要让用户在聊天贴密钥，不使用用户API进行未经授权的付费测试。

## 5. 屏幕授权/黑图：用户报告和本地修改

- 用户报告拼多多识图黑屏、每次都弹“要开始使用小伴语音助手录制或投射内容吗”。截图为系统“取消/立即开始”弹窗，没有单应用/整个屏幕选项；不要再要求用户选择不存在的选项。
- 用户称其他都没问题，手动截图能显示商品页，但不等于MediaProjection一定能获取该页。
- 0.2.0每次取一帧后停止projection，故反复授权。
- **0.3.0本地修改（待实际运行验证）**：
  - 保留一个用户授权的projection和virtual display；后续只切换同一display的surface，不复用授权token创建新display。
  - 按需连接surface取帧，空闲断开surface，不编码/上传画面。
  - 共享常驻通知可关闭；关闭悬浮球也结束共享。系统结束projection时清理；下次重新授权。
  - 近乎纯黑帧检测，不上传空帧，限时等待后续帧；持续黑屏明确报错，不能绕过FLAG_SECURE。
  - 大图用进程内静态槽传递，10秒过期，不能塞进ResultReceiver Bundle。
- **尚未确认拼多多黑图根因/修复有效性**。可能启动空帧、透明Activity被捕获为黑底、受保护层、尺寸/旋转、buffer转换等。需要真实捕获预览诊断，不能继续只猜。
- 曾承诺设置页增加“截图预览测试”，**还没有实现**。
- 持续会话的surface断开/重连是否在目标设备可靠需测试；系统共享提示和资源开销不能隐瞒。

## 6. 导航的严重功能差距（优先后续）

- 当前高德为POI关键词搜索，百度/腾讯为步行路线入口；仍需用户去地图选地点/开始。
- 用户目标：说“去XX”→助手复述→点勾→直接真实步行导航播报，尽量不出现地图页面。
- 需核查地图官方导航SDK是否允许无地图视图/后台步行导航、所需应用key/签名SHA1/包名授权、费用和合规要求；不凭空承诺高德肯定支持。
- 目的地搜索/歧义确认、定位、路线计算、进度/偏航/到达、后台定位前台服务、熄屏音频都未实现。
- 当前应用甚至没有定位权限，因为定位交给外部地图。要真实导航必须补设计。
- 大模型只解析需求，不生成方向；禁止模拟点击代替可靠导航或胡乱报路。
- 如要下载SDK先给用户链接用途，不自行拉取文件。未确认官方能力前不要随便加依赖。

## 7. 发布与签名风险

- GitHub Actions：`.github/workflows/build-apk.yml`，仅 `workflow_dispatch`，push不会自动编译。
- 构建Ubuntu24.04、setup-java@v5、setup-gradle@v4；显式find SDK下sdkmanager，不依赖PATH。
- 流程assembleDebug，上传artifact，再用actions/github-script创建release并上传APK。
- release tag `trial-${run_number}-${run_attempt}`，asset `elder-voice-assistant.apk`。
- **签名尚未固定**：每个云端runner自动生成debug.keystore，后续构建可能签名不一致，不能保证覆盖安装。此前已告知用户不要先卸载，卸载会清配置。
- 以后应安全配置固定签名并备份，不把私钥/口令写进仓库。首版私钥未保存，不能声称能恢复覆盖。
- 已成功发布：
  - 0.1.0：`https://github.com/cixks/elder-voice-assistant/releases/download/trial-3-1/elder-voice-assistant.apk`
  - 0.2.0：`https://github.com/cixks/elder-voice-assistant/releases/download/trial-4-1/elder-voice-assistant.apk`
- 最近0.2.0构建run：45824163338，成功。
- 初期setup-android失败、sdkmanager PATH exit127，现已修复，别回退旧流程。

## 8. 本次操作状态与接手步骤

- 文档编写前最新提交 `c98c161`：0.3.0大勾叉确认、复用屏幕会话、黑屏过滤。
- 之前推送 `c98c161` 网络reset失败；用户最新要求“先推送，失败多试几次”，随后要求本交接文档。两项任务都继续，不把写文档视为取消推送。
- 当前会话计划提交本文并推送，然后触发此前用户已授权的0.3.0构建。**接手务必查实际远程和Actions，避免重复触发；本文不会自我更新构建结果。**
- 推送失败保留本地提交，有限次数重试。不得force push覆盖远程未知变更。
- PAT现已具备Actions触发权限；GitHub REST dispatch曾403，用户修正权限后已可用。
- 触发：POST `https://api.github.com/repos/cixks/elder-voice-assistant/actions/workflows/build-apk.yml/dispatches`，JSON `{"ref":"main"}`，Authorization Bearer从外部文件读，不输出header。
- 查workflow runs时按head_sha匹配本次提交，避免拿旧run冒充新构建。
- 构建成功查对应tag的release assets，提供**实际存在**的browser_download_url，不编造下载链接。
- 异步shell会自动通知，启动后台监测后不要反复查输出文件。用户没有授权在本地自动下载日志ZIP或APK；用API读取annotations等元数据诊断。
- 后续重点：真机确认大按钮流程、二次识图不重复授权、黑图实际根因；真实无地图界面导航选型；固定签名。

## 9. 尚未完成/不要误报

- 本项目只是家庭试用原型，编译成功≠真机功能通过。
- 无地图界面导航、固定签名、截图预览诊断、完善连接测试均未实现。
- 没有测量RAM、APK/安装空间或待机电量，不许承诺具体占用。
- 不能声称价格识别100%准确；不能让长辈首次无人陪同依赖导航。
- API设置旧新字段迁移和文档有待整理；保持用户已有key，不打印或清除。
