# TV Media

**在 Android TV 上直接播放夸克网盘里的视频 —— 不需要 NAS，不需要电脑常开，不需要自建服务端。**

手机夸克 App 扫一次码登录，之后就能在电视上浏览自己的网盘目录，并以**源视频原始码率**硬解播放。

> **English TL;DR** — TV Media is an Android TV app that plays videos stored in your Quark
> (夸克网盘) cloud drive directly on a TV box, with no NAS, no always-on PC, and no self-hosted
> server. Log in once by scanning a QR code with the Quark mobile app; the app then browses your
> drive and plays the original-bitrate file through ExoPlayer. All traffic is between your device
> and Quark's own servers.

---

## 这是什么

夸克网盘里的视频，官方客户端在电视上没有好用的播放入口。常见的绕法是自建一套
[OpenList](https://github.com/OpenListTeam/OpenList)/Alist 挂在 NAS 或一台常开的电脑上，再让电视去连它
—— 代价是你要多维护一台机器，而且家里多了一个必须一直开着的服务。

TV Media 把这一整套都省掉了：**它自己就是那个客户端**。

| | 传统做法（OpenList / Alist + 电视播放器） | TV Media |
|---|---|---|
| 需要常开的机器 | 需要（NAS / 小主机 / 电脑） | **不需要** |
| 需要配置服务端地址、账号 | 需要 | **不需要**，只有一次扫码 |
| 电视侧要装的东西 | 一个支持 HTTP 直链的播放器 + 配好地址 | **只装这一个 APK** |
| 播放码率 | 取决于自建链路 | 源视频原始码率，本地硬解 |
| 凭证存在哪 | 你的服务器上 | **只在你电视本机，加密保存** |

应用本身**不连接任何第三方服务器**，没有遥测、没有账号体系、没有后端。
所有请求都是你的设备直连夸克自己的域名。

## 特性

- **扫码登录，零配置** —— 电视上打开内嵌的夸克网页登录页，用手机夸克 App 扫二维码即可。
  不需要填任何服务器地址、账号或密码。
- **源码率直出** —— 不做转码、不做降码率，直接取网盘里那个文件本身，交给设备硬解。
- **不占用「夸克 TV 设备」名额** —— 登录的是**网页版会话**，不是 TV 端会话。
  TV 端驱动每次扫码都会占掉一个「TV 设备」名额（上限 2 个），而且播放会被限速；网页版会话两者都没有。
- **凭证加密保存在本机** —— 使用 `EncryptedSharedPreferences`；设备加密存储不可用时会在设置页明确提示已降级为明文。
- **遥控器友好的两套播放方式**
  - *内置播放器*（默认）：ExoPlayer，专为遥控器重做的播控 —— 中央无按钮、左右键 ±10 秒（长按连发）、
    选项键弹出字幕/音轨面板、返回键两段语义、无操作 5 秒自动隐藏。
  - *系统播放器*：交给 MPV 等外部播放器，同样**无需在外部播放器里配置任何账号**。
- **回到原处** —— 播放结束返回列表时回到播放前所在的目录，焦点落在刚播的那一条：
  看下一集只需按一次「下」+ 确定。冷启动重开 app 也会回到上次的位置。
- **内嵌字幕** —— 自动选中中文字幕轨，可在播放中手动切换或关闭。

## 安装

到 [Releases](../../releases/latest) 页面下载最新的 APK，然后任选一种方式安装：

**方式一：U 盘 / 文件管理器**

把 APK 拷进 U 盘，插到电视或盒子上，用文件管理器点击安装（需要在系统设置里允许「未知来源应用」）。

**方式二：adb**

```bash
adb connect <电视或盒子的 IP>:5555
adb install -r tvmedia-v0.1.8-debug.apk
```

`-r` 表示覆盖安装，升级时无需先卸载。

> **关于签名**：目前发布的 APK 是 **debug 签名**的构建（见下方「已知限制」）。
> 它可以正常长期使用，但如果将来换成 release 签名，**需要先卸载再安装**。
> 从旧版本升级时请始终用同一来源的包，否则会因签名不一致而安装失败。

**系统要求**

| 项 | 要求 |
|---|---|
| 系统版本 | Android 7.0（API 24）及以上 |
| 架构 | 无原生库，任何架构都可以 |
| 必备组件 | 系统 WebView（用于扫码登录页）；几乎所有设备都自带 |
| 网络 | 能访问夸克网盘（`pan.quark.cn` / `drive.quark.cn` 及其 CDN） |
| 解码 | 视频解码能力取决于设备芯片；H.264 基本都有，HEVC/H.265 看具体机型 |

## 首次使用

1. 安装后从电视应用列表启动 **TV Media**。
2. 应用会自动打开夸克登录页（内嵌浏览器），并自动点开「登录账号」弹窗。
3. 用**手机上的夸克 App** 扫描弹窗里的二维码。
   如果没看到二维码，按遥控器的确定键点一下「显示登录二维码」。
4. 手机确认后，登录页会自动返回，直接进入你的网盘根目录。

登录状态会保存在本机，重启应用不需要重新扫码。凭证会随使用自动续期，正常使用不会过期。

> 登录页是夸克网页版，**没有为遥控器做焦点样式**，所以直接用方向键会看不清焦点在哪。
> 应用已经强制给登录页加上蓝色描边，按方向键就能看到当前位置。

## 使用

| 操作 | 行为 |
|---|---|
| 方向键 | 移动焦点 |
| 确定 | 进入文件夹 / 播放视频 |
| 返回 | 目录内逐级回退；根目录再按则退出应用 |
| 设置 | 列表右上角「设置」入口 |

非视频文件会置灰且不可聚焦，不会干扰方向键移动。

### 内置播放器的遥控器操作

播控 UI 只有两块，且都是**只读**的：顶部是文件名（含快进/快退的瞬时反馈），
底部只有时间轴（已播 / 进度 / 总时长）。**中央永远为空**，没有任何按钮或状态文字。

| 按键 | 行为 |
|---|---|
| ← / → | 快退 / 快进 **10 秒**；**长按**转为连续快退 / 快进 |
| ↑ / ↓ | 唤出顶部标题条 + 底部时间轴 |
| 确定 | 播放 / 暂停 |
| 菜单（选项） | 左侧弹出**字幕 / 音轨**面板；上下选行、左右换组、确定选中 |
| 返回 | 面板可见时只收起面板；已收起时第一次提示「再按一次返回退出播放」，第二次才退出 |

无操作 **5 秒**自动隐藏（播放中与暂停中一样），任何按键都会重新计时。

### 设置

- **播放方式**：内置播放器 / 系统播放器，随时可切换，立即生效。
- **夸克账号**：显示登录状态、重新登录、退出登录（退出会一并清除本机浏览器里的登录状态）。

> **两种播放方式都会走本机的预读代理**。这是为了让外部播放器不必知道任何账号信息：
> 应用先向夸克换取已签名的直链，再由本机代理把所需的请求头补齐。
> 系统播放器模式下会额外启动一个前台服务保活，通知栏可见，播放结束即可关闭。

## 它是怎么工作的

```
遥控器
  │
  ▼
TV Media（TV / 盒子 / 手机）
  │
  ├─ 列目录   GET  /file/sort       ─┐
  ├─ 取直链   POST /file/download   ─┴─▶ 夸克网盘 API（drive.quark.cn）
  │
  └─ 本机预读代理（127.0.0.1）
        ▲
        │  播放器请求  http://127.0.0.1:<port>/_u/...
        │
     ExoPlayer 或外部播放器（MPV）
        │
        └─▶ 代理向上游发有界 8 MiB Range 请求（带 Cookie / Referer / UA）──▶ 夸克 CDN
```

三件事值得说明：

**1. 为什么登录要用网页版而不是 TV 端。**
夸克在 OpenList 里有多个驱动，TV 端驱动虽然"支持扫码"，但每次扫码都会占用一个「夸克 TV 设备」名额
（上限 2 个，超了要去管理后台清理），而且播放会被限速。所以这里改成在电视上用内嵌浏览器打开
**夸克网页登录页**，扫码后把 cookie 取回来 —— 既有扫码体验，拿到的又是网页版会话。

**2. 为什么必须有一个本机代理。**
夸克 CDN 的直链要求带上 `Cookie` + `Referer` + `UA`（裸请求会被回 `412`），
而且它对**无界请求**（播放器默认发的 `bytes=N-`）**限速到约 0.1 MiB/s**，
对**有界请求**却很快（实测 8~128 MiB 的块能跑到 7.5~12.7 MiB/s，3 路并发可到 32 MiB/s）。

所以代理做三件事：

1. 只向上游发**有界 8 MiB** 请求，绝不发无界请求；
2. **长度按请求算** —— 对播放器报的是"从这个偏移到文件末尾"的真实剩余长度，并边收边发。
   只回一块会骗过**不读 `Content-Range` 的播放器**（它以为整个文件只有一块那么大，播几秒就退出）；
3. 上游提前结束时**从断点续拉**，绝不当作文件结束。

代理只监听 `127.0.0.1`，不对局域网开放。

**3. 为什么凭证是安全的。**
登录 cookie 加密保存在设备本地，应用不会把它发给除夸克域名以外的任何地址。
预读代理也只在回环地址上工作，构造 `/_u/` 请求的能力仅限本机应用。

## 常见问题

**Q：一定要用电视吗？手机能装吗？**
能。应用同时注册了 TV 和手机的启动入口，手机上也能用，只是播控 UI 是按遥控器设计的。

**Q：安装后桌面图标显示「图标解析中」？**
那是桌面还没完成资源索引的瞬态现象，等几秒或重启桌面就会变成正常图标。图标本身是标准自适应图标。

**Q：播放 4K HEVC 报错 `播放失败：设备无法解码该视频编码`？**
这是设备解码能力问题，不是应用问题。Android 模拟器通常没有 HEVC 解码器；电视真机一般可以硬解。
换 H.264 片源或换设备即可。

**Q：卡顿怎么办？**
先切到「系统播放器」用 MPV 试试，两者的取流方式一致，但解码器不同。
如果两种都卡，多半是片源码率超过了设备解码能力或网络带宽。

**Q：登录失效了？**
应用会明确提示并引导重新扫码，不会静默失败。在设置页点「登录夸克网盘」重新扫一次即可。

**Q：需要 OpenList / Alist 吗？**
不需要。当前 `master` 分支是直接连接夸克的实现。

## 已知限制

- **只支持夸克网盘**，不支持多网盘；不支持上传 / 删除 / 重命名 / 转存 / 分享。
- **只取源视频直链，不做转码**。码率超过设备解码能力时会卡或无法播放。
- **没有播放进度记忆**，不支持续播。系统播放器模式下尤其拿不到进度。
- **外挂字幕不支持**（同目录的 `.srt` / `.ass` 不会被加载）。内嵌字幕可以。
  内嵌 **ASS 特效样式不会还原**（只做基础文本渲染），想要完整字幕效果请切「系统播放器」用 MPV。
- **发布的 APK 目前是 debug 签名**，不是 release 签名。
- **大目录会一次性加载完**（分页上限每页 100 条，会自动翻完所有页），数千条的目录首次进入会慢一些。
- **夸克协议是移植实现**：夸克一旦修改接口，需要跟进修改 `quark/` 包。
  这是自研相比直接用 OpenList 的唯一真实成本。

## 从源码构建

需要 JDK 17 与 Android SDK（compileSdk 34、build-tools 34.0.0）。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

./gradlew assembleDebug       # 产物：app/build/outputs/apk/debug/tvmedia-v<version>-debug.apk
./gradlew testDebugUnitTest   # 单元测试
./gradlew lintDebug           # 静态检查
```

或者在项目根目录建 `local.properties` 写 `sdk.dir=/path/to/android-sdk`（该文件已被 gitignore）。

**版本号只有一个来源**：`app/build.gradle.kts` 里的 `appVersionName` 一行。
`versionCode`（`major*10000 + minor*100 + patch`）与 APK 产物名都从它派生。

## 项目结构

```
app/src/main/java/com/tvmedia/openlist/
├── Config.kt         UA / 超时 / 缓冲（没有任何服务器地址或账号）
├── data/
│   ├── model/        Entry, MediaTypes, EntrySorting, NaturalOrder
│   ├── settings/     SettingsStore（播放方式）, TokenStore（加密凭证）
│   └── source/       MediaSource, MediaSourceRegistry, QuarkSession
├── quark/            夸克网页版协议：QuarkProtocol（常量）, QuarkRequests, QuarkApiClient,
│                     QuarkSource, QuarkFile, QuarkCookies, QuarkHtml, QuarkJson, QuarkHttp
├── log/              AppLog（只进 Logcat 的门面）
├── proxy/            LocalProxy, ProxyUpstream, LocalProxyServer, ProxyForegroundService
└── ui/
    ├── auth/         QuarkLoginActivity（WebView 登录，抓 cookie）, QuarkWebSession
    ├── main/         MainActivity, EntryAdapter, BrowsePath
    ├── player/       PlayerActivity（TV 遥控器操控层）
    └── settings/     SettingsActivity
```

UI 只持有 `MediaSource` 接口，完全不认识夸克协议包；列表 UI、排序、播放分发与预读代理都只消费 `Entry`。
换成别的网盘只需要新增一个 `MediaSource` 实现。

`app/src/debug/` 下有三个**只进 debug 构建**的探针（`QuarkProbeInstrumentation` /
`ProxyProbeInstrumentation` / `PlayerProbeInstrumentation`），用于在真机上实测上游行为与代理契约：

```bash
adb shell am instrument -w com.tvmedia.openlist/com.tvmedia.openlist.ProxyProbeInstrumentation
adb logcat -d -s ProxyProbe
```

## 免责声明

- 本项目是**非官方**的第三方客户端，与夸克 / UC 没有任何关联，也未获得其授权或认可。
- 本项目只读取**你自己账号下**的网盘内容，不提供任何破解、绕过付费或共享账号的功能。
- 请遵守夸克网盘的服务条款，以及你所在地区的法律法规。使用者需自行承担使用风险。
- 夸克接口可能随时变更，届时应用可能失效。

## 许可证与致谢

本项目以 **GNU Affero General Public License v3.0（AGPL-3.0）** 发布，全文见 [LICENSE](LICENSE)。

`app/src/main/java/com/tvmedia/openlist/quark/` 下的夸克协议实现移植自
[OpenList](https://github.com/OpenListTeam/OpenList) v4.2.2 的 `drivers/quark_uc/`（同样以 AGPL-3.0 发布），
各文件头部均注明了对应关系。感谢 OpenList / AList 的作者与贡献者把夸克协议的细节公开出来。

第三方依赖各自遵循其原始许可证（AndroidX、Kotlin、OkHttp、Retrofit、media3/ExoPlayer 等）。
