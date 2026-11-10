# Immich TV Private

基于 [Immich Android TV 6.2.0](https://github.com/giejay/Immich-Android-TV/tree/6.2.0) 的非官方 Android TV 客户端，连接自托管 Immich，可与上游应用并存。

[下载 1.3.0 APK 与对应源码](https://github.com/z979054461/Immich-Android-TV/releases/tag/v1.3.0-private) · [更新记录](RELEASE_NOTES.md)

当前为预发布版本，尚未完成电视实机验收。

## 功能

- 相册、人物、时间线、照片与视频播放、幻灯片。
- 多账户与四位 PIN，可设置后台免 PIN 宽限和立即锁定。
- 日期与人物组合筛选、手动连接诊断。
- 账户隔离的缩略图缓存，可调整容量或清空。
- 手动检查应用更新，下载校验后交给 Android 系统确认安装。

## 使用

1. 下载 APK，在 Android 7.0 及以上的电视安装。
2. 输入 Immich 服务器的最终 HTTPS 地址和专用 API Key。
3. 设置本地 PIN，选择需要浏览的账户。

1.1.1 可直接覆盖升级。旧版长 PIN 需先完整验证，再改为四位；从 1.0.0 升级需重新添加本地档案。忘记 PIN 可删除电视上的本地档案后重新添加，不会删除服务器照片。

## API Key 权限

以下权限按 Immich 3.3.5 接口核对，按需要授予：

| 用途 | 权限 |
|---|---|
| 元数据、时间线、搜索 | `asset.read` |
| 缩略图、视频 | `asset.view` |
| 原图 | `asset.download` |
| 相册 | `album.read` |
| 人物及其缩略图 | `person.read` |
| 回忆 | `memory.read` |
| 文件夹 | `folder.read` |
| 修改收藏（可选） | `asset.update` |

不需要 `all`、管理、删除或上传权限。建议使用专用非管理员账户与密钥，不要把密钥写入仓库或问题报告。日期/人物筛选需要 Immich 3.2 及以上；其他服务端版本的兼容性仍需验证。

## 设置与限制

- **PIN 宽限**：立即、1、5、15 分钟，默认 5 分钟。共享电视可设为立即锁定；PIN 是本地访问锁，不是 Immich 密码。
- **缩略图缓存**：关闭、64、128、256 MiB，默认 128 MiB。只复用当前前台会话，后台、锁定或切换账户后失效，不缓存原图和视频。
- **连接**：不支持 HTTP 重定向或外部 CDN 媒体；HTTP 需确认风险，推荐 HTTPS。
- **隐私**：无统计或崩溃上传，API Key 加密保存在本机。媒体请求只访问配置的服务器；主动检查或下载更新会访问本仓库 GitHub Releases。
- **浏览**：时间线按月加载，特别大的月份仍可能较慢。共享相册请从“相册”进入。筛选按电视时区计算日期，和主时间线的日期口径可能不同。
- 后台照片屏保停用，解锁后的前台预览保留。

## 来源与许可

保留上游 [giejay/Immich-Android-TV](https://github.com/giejay/Immich-Android-TV) 的 [GPLv3](LICENSE)、原始版权与署名，以及 mediaslider 的 [Apache-2.0](mediaslider/LICENSE.md)。

分发安装包时须提供对应版本的完整源码和构建材料。开发环境与命令见[构建说明](docs/BUILDING.md)。
