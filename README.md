# Immich TV Private

面向自托管 Immich 的 Android TV 私用分支。基于 [giejay/Immich-Android-TV 6.2.0](https://github.com/giejay/Immich-Android-TV/tree/6.2.0)，基线提交 `90f879c3eba843c9d976505fc463d236d846bd91`。上游该标签的 Gradle 版本字段仍是 6.1.0/code147；本分支独立版本为 `1.1.0-private`，包名 `io.github.z979054461.immichtv`，可与原版共存，不覆盖原版。

## 运行时隐私边界

- 只手动输入自己的服务器 URL 和专用 API Key，无作者配对网站、演示服务器或第三方登录。
- 删除 Firebase Analytics/Crashlytics、Google 登录、Google Play 计费及捐赠入口，没有统计或崩溃上传。
- API、Glide 图片和 Media3 视频/屏保使用同一受限 HTTP 客户端。每次请求在 DNS/连接前检查配置的协议、域名、端口和 `/api/` 路径。
- **所有 HTTP 重定向均拒绝**。反向代理需要直接提供最终 HTTPS 地址，不能转到另一个域名、端口或路径。外部 CDN 媒体也不支持。
- API Key 仅在 `x-api-key` 请求头中，绝不加到图片/视频 URL。移除外部播放器和系统桌面相册频道，不把媒体凭据交给其他应用。
- 不记录响应、URL 或凭据，不配置任何 Timber 输出。密钥由 AndroidKeyStore AES-GCM 加密；加密失败则拒绝保存，没有明文降级。
- 禁止云备份和设备迁移提取应用数据。使用系统/用户证书信任库，**没有“忽略证书验证”选项**。
- HTTP 仅在用户确认风险后可配置。强烈推荐 HTTPS；应用不能使明文局域网连接变得安全。

“无第三方运行服务”指应用代码的服务请求不依赖作者或其他云服务；不意味着 Android 操作系统、输入法、DNS、路由器或用户配置的代理不联网。构建时仍需从 Google/Maven/Gradle/GitHub 下载开源依赖，这与运行时服务依赖不同。

## 功能

保留上游的遥控器导航、相册/人脸/时间线、照片及视频播放、幻灯片、前台屏保预览与元数据显示。深色电视界面采用统一卡片、对比清晰的遥控器焦点和多人选择页。手动连接界面提供中文，密钥遮罩、禁用输入框状态保存，并在离开界面时清空输入框。

### 1.1.0：多账户和重新打开 PIN

- 最多保存 12 个独立本地档案。每个档案填写昵称、自己的服务器地址、该 Immich 用户的专用 API Key，以及独立的 6–12 位数字 PIN；这不是服务器邮箱/密码登录，也不创建 Immich 账户。
- 启动、从后台返回或在设置中切换账户均需重新输入 PIN。成功添加档案后也通过同一解锁入口进入。返回键不能进入上一个用户的页面。
- API Key 继续由 AndroidKeyStore AES-GCM 加密。PIN 不以明文保存，使用随机 16 字节 salt、600,000 次 PBKDF2-HMAC-SHA1、256 位摘要和恒定时间比较；SHA1 PRF 为兼容 Android 7/API24。第 5 次尝试后等待 30 秒，连续尝试递增至最多 5 分钟，计数与冷却时间跨进程保存。
- 锁定后取消网络请求，拒绝旧会话的请求/响应，清理媒体与元数据状态。时间线随用户的导航会话销毁。系统最近任务截图被屏蔽。
- 为防止共享服务器 URL 的图片缓存跨用户复用，关闭 Glide 持久/内存缓存；重复浏览会增加下载量。照片后台系统屏保主动退出，避免绕过 PIN；解锁后的前台屏保预览仍可用。
- 从 1.0.0 升级需重新填写服务器和 API Key 并设置 PIN，不会将已有免 PIN 凭据交给任何首次打开电视的人绑定。服务器照片不受影响。
- 忘记 PIN 可删除该电视上的本地档案，再用 API Key 添加；不会删除服务器账户或照片。当地用户能够删除本地档案，但无法因此解锁原档案。

**PIN 只是电视应用的本地访问锁，不是 Immich 密码，也不能防御 root、应用数据被篡改或已泄露的 API Key。** 不要把 PIN 告诉他人；遗失/泄露 Key 应在自己的 Immich 中撤销。

### 时间线空白与大型图库

时间线通过 Immich API 读取月份列表，再按需读取月份内容；不是网页嵌入，也不会在打开时下载全图库原图。已核对 Immich 3.3.5 的 `/api/timeline/buckets`、`/api/timeline/bucket`、`asset.read` 权限及参数。

1.1.0 修复回忆加载过早关闭时间线进度、首月失败后无法重试、异步布局结果竞争等问题。现在初始仅取一个月，对密集月份关闭相邻月份预取；元数据 API 读取等待调整为 60 秒、单次请求总上限 90 秒（不限制视频播放时长）；显示持续的加载、空图库、HTTP 400/401/403/404 提示和可用遥控器点击的重试按钮。

仅凭“空白”不能判定照片太多。仍需根据新版错误状态区分 Key/权限、服务器连接、接口或空图库。Immich 的月份接口没有分页参数，因此**单月照片特别多仍可能慢或占用较多内存**。没有访问用户 NAS/图库，也没有将静态兼容核对称为电视实机验证。

连接后如某个页面提示权限不足，应只补充该页面需要的权限，不授予 `all`。

## Immich 3.3.5 权限

按 [v3.3.5 控制器源码](https://github.com/immich-app/immich/tree/v3.3.5/server/src/controllers)核对：

| 用途 | 所需权限 |
|---|---|
| 相片元数据、搜索、时间线 | `asset.read` |
| 缩略图、视频播放 | `asset.view` |
| 原图浏览 | `asset.download` |
| 相册 | `album.read` |
| 人脸和人脸缩略图 | `person.read` |
| 回忆 | `memory.read` |
| 文件夹视图 | `folder.read` |
| 可选的收藏/取消收藏按钮 | `asset.update`，仅确实需要修改收藏时授予 |

不需要 API Key 管理、删除、上传、系统管理或 `all` 权限。建议为电视创建专用非管理员账户/密钥，只分享需要在电视显示的相册。**不要把真实密钥提交到仓库、构建参数或问题报告。**

上游宣称支持 Immich 3.0.0+；本分支按 3.3.5 的 API 接口及权限做静态核对，但在连接真实服务器和电视实测前，不宣称已完成 3.3.5 端到端兼容验证。未访问开发者/用户 NAS 或照片。

## 构建与测试

需要 JDK21、Android SDK37 和可访问官方构建依赖的网络。无需 Firebase 配置、演示密钥或作者服务配置。

```sh
python3 scripts/check_private_runtime.py
./gradlew :app:testDebugUnitTest :mediaslider:mediaslider:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

无签名环境变量时 release 输出**未签名 APK，不能直接安装**。要长期覆盖升级，需通过安全流程另行配置本应用专用签名密钥，不能复用别的应用的签名授权或把私钥提交到 Git。本仓库 CI 先运行检查，在本仓库的可信分支通过后才读取专用 Secrets 签名。PR 检查不读取签名凭据。CI 不发布 release、不向 Play Store 上传。

有权限的维护者可在自己的安全构建环境配置 `RELEASE_KEYSTORE_PATH`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后构建签名 release。不要在日志中输出这些值。CI 的持久 Secrets 为 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_PASSWORD` 和公开证书指纹 `RELEASE_CERT_SHA256`；Java 会按指纹定位实际别名并验证私钥签名，不依赖 PKCS12 友好名。

隐私回归测试包括真实本机回环 HTTP 服务器，验证 301/302/303/307/308 不跟随、跨域请求在 DNS 前拒绝、Key 只在单个请求头里、未配置时禁止联网。测试只用合成数据，不访问 NAS。静态检查不是抓包证明；最终 APK 仍需检查合并 Manifest、运行依赖和电视实机网络行为。

## 许可与来源

主项目保留 [GPLv3 LICENSE](LICENSE)，内置 mediaslider 保留其 [Apache-2.0 LICENSE](mediaslider/LICENSE.md)，其他原始版权声明也保留。[README.upstream.md](README.upstream.md) 是上游资料存档，不代表本分支仍提供其中所有功能。

分发 APK 时必须同时提供对应版本的完整源代码及构建说明，并遵守 GPLv3 与依赖许可。此为非官方个人分支，不是 Immich 官方电视客户端。
