# 构建与测试

需要 JDK21、Android SDK37 和可访问官方构建依赖的网络。无需 Firebase 配置、演示密钥或作者服务配置。

单元测试还需公开的固定 [1.1.1 APK 签名样本](https://github.com/z979054461/Immich-Android-TV/releases/download/v1.1.1-private/ImmichTV-Private-1.1.1.apk)：下载到仓库外，SHA-256 必须为 `21153d9c46bf7865f3337af7e74a067f0b5bd7ff462ec53e1e693894d88630a3`，并把其绝对路径设置为环境变量 `IMMICH_UPDATE_TEST_APK`。无需私钥。缺少样本或摘要不符会明确失败，不能跳过签名测试。

```sh
python3 scripts/check_private_runtime.py
./gradlew :app:testDebugUnitTest :mediaslider:mediaslider:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

无签名环境变量时 release 输出**未签名 APK，不能直接安装**。要长期覆盖升级，需通过安全流程另行配置本应用专用签名密钥，不能复用别的应用的签名授权或把私钥提交到 Git。本仓库 CI 先运行检查，在本仓库的可信分支通过后才读取专用 Secrets 签名。PR 检查不读取签名凭据。CI 只产出检查报告及签名构建产物，不自动公开发布 GitHub Release，也不向 Play Store 上传。

有权限的维护者可在自己的安全构建环境配置 `RELEASE_KEYSTORE_PATH`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_ALIAS`、`RELEASE_KEY_PASSWORD` 后构建签名 release。不要在日志中输出这些值。CI 的持久 Secrets 为 `RELEASE_KEYSTORE_BASE64`、`RELEASE_KEYSTORE_PASSWORD`、`RELEASE_KEY_PASSWORD` 和公开证书指纹 `RELEASE_CERT_SHA256`；Java 会按指纹定位实际别名并验证私钥签名，不依赖 PKCS12 友好名。

隐私回归测试包括真实本机回环 HTTP 服务器，验证 301/302/303/307/308 不跟随、跨域请求在 DNS 前拒绝、Key 只在单个请求头里、未配置时禁止联网。网络测试只用合成数据；签名回归使用上述固定公开 APK，验证原件及篡改 DEX 后重新计算下载摘要仍被签名拒绝。静态检查不是抓包证明；最终 APK 仍需检查合并 Manifest、运行依赖和电视实机网络行为。

