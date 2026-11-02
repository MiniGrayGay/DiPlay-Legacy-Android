# 手机 → 车机互联：三个协议的实际可行性

需求是"按 CarLife / ICCOA Carlink / HiCar 实现与 CarPlay 一致的功能"。三个协议并不是三份等量的
工作：**只有一个能由第三方车机自行实现**，另外两个在车机侧是授权件，不拿到 SDK 连"不支持"都算不上
——手机根本不会识别未认证的车机。

本文记录结论、证据，以及已经落地的 CarLife 实现细节。

---

## 〇、先澄清一个前提：AAP 不等于"只能用 Google 的手机"

Android Auto 投影协议（AAP）本身是**协议**，不是 Google 独占的软件。Google 独占的是**车机证书的
签发权**（见 `ANDROID_AUTO_OFFLINE_AUTH.md` §1.1）。手机端那半个实现可以换掉：

- **Google 官方客户端**（`com.google.android.projection.gearhead`）：只连 Google 签发的车机，国内
  GMS 机型才有，且国内 AA 服务不可用 → 对本项目无意义。
- **AARE**：`an open-source reimplementation of the phone side of Android Auto`，一个普通 APK，
  **不依赖 Google Play Services**，不 root 也能用（root 只是"系统音频路由"的可选增强）。国产安卓
  ROM 直接装。

所以 AAP + 自签车机的组合与"手机是不是 Google 生态"无关，**只与"手机能不能装这个 APK"有关**。

## 〇之一、按手机类型选路

| 手机 | 走哪条 | 手机端要做什么 | 本项目状态 |
|---|---|---|---|
| iPhone | CarPlay | 什么都不装 | 已实现，`shared/transport` |
| 国产安卓（小米 / OPPO / vivo / 荣耀 / 一加，GMS 与否都行） | AAP + AARE | 装一次 AARE | 协议已跑通（双模拟器） |
| 国产安卓（同上） | 百度 CarLife | 装百度 CarLife App | 协议层 + 会话已实现，缺 Activity |
| 老安卓（无车机协议、不愿装 app） | ADB 投屏 | 开 USB 调试 | 已实现（本项目不作为主打） |
| 华为鸿蒙 4.x（AOSP 内核，可装 APK） | AAP + AARE / CarLife | 装一次 | 同上 |
| **纯血鸿蒙 NEXT（不支持 APK）** | HiCar（手机自带） | 装不了任何 APK | **做不了**，需 HiCar 认证 |

最后一行是唯一真正无解的情况：纯血鸿蒙不跑 APK，AARE 与 CarLife 都装不上，而 HiCar 车机端要准入。
这不是技术问题，见第一节。

> 待验证的加分项：小米 CarWith / OPPO、vivo 的"车机互联"若兼容 CarLife 通道，那几款手机可能
> **零安装**就能连上本项目的 CarLife 实现。这一点没有实测证据，不做承诺，但值得在真机上试一次。

---

## 一、结论

| 协议 | 车机端能否自行实现 | 依据 | 手机构建要求 |
|---|---|---|---|
| **百度 CarLife** | **能** | 车机端协议库与 Android 车机端 App 由百度以 Apache-2.0 开源 | 手机装"百度 CarLife"App（普通安装） |
| **Android Auto 投影（AAP）** | **能** | 沿用 CarPlay 同一套自签证书 + TOFU，协议已用 AARE 源码核实 | 手机装 AARE（APK，无需 GMS） |
| **华为 HiCar** | 不能 | 设备 SDK 官方未公开，需合作申请 → 准入函 → 签使用协议 → 认证验收 | 华为/荣耀手机自带 |
| **ICCOA Carlink** | 不能 | 车端 SDK 由联盟发放，且**未通过认证的车机手机端不识别** | 小米/OPPO/vivo 系统内置 |
| CarPlay（对照） | 能（已实现） | DiPlay 现有 `shared/transport` 9000+ 行 | 零安装，iOS 系统内置 |
| ADB 投屏（补充） | 能（已实现） | 手机自带 `screenrecord` / `input` | 零安装，只需开 USB 调试 |


### 为什么无法自研 HiCar

华为开发者联盟与 HiCar 情报站（非官方镜像）都明确写明：*"the required SDKs and tools have not been
publicly released. Please follow the partnership process to apply to Huawei"*。接入路径是：应用/设备
接入流程 → 准入申请（签使用协议）→ 集成开发 → 认证测试 → 验收后才能量产。公开报道中唯一的例外是
一汽软件拿到 HiCar SDK 源码，那是双边合作，不是可复制的路径。

### 为什么无法自研 ICCOA Carlink

联盟（小米、OPPO、vivo 发起）提供"成熟稳定的车端 SDK 与认证流程"。关键差异在手机侧：**未认证设备
将无法在合作品牌手机上被识别或稳定使用**。也就是说自研实现不会表现为"功能不全"，而是手机端压根不
发起连接，没有可调试的对象。

> 这两条不是技术难度问题，是授权问题。若后续拿到 SDK，落在 `PhoneLinkProtocol` 里把
> `available` 翻成 true 并实现对应会话即可，适配缝已经留好。

## 二、CarLife 落地（已完成部分）

### 2.1 链路：和百度车机端完全一致

百度车机端的 `CConnectManager::execSocketForward()` 在手机上执行六条命令，然后连本机端口：

```
adb forward tcp:7200 tcp:7240   # cmd
adb forward tcp:8200 tcp:8240   # video
adb forward tcp:9200 tcp:9240   # media
adb forward tcp:9201 tcp:9241   # tts
adb forward tcp:9202 tcp:9242   # vr
adb forward tcp:9300 tcp:9340   # touch(ctrl)
```

`adb forward tcp:A tcp:B` 的语义就是"在 A 上监听，连进来时对手机 OPEN tcp:B"。所以 DiPlay 不需要
绑任何本机端口，直接 `AdbSession.open("tcp:7240")` 拿到同一条隧道——**复用上一轮已经写好的 ADB
传输层**，USB bulk 与 TCP 两种承载都能用。

这条链路的代价：手机上要开 USB 调试，且 CarLife App 必须处于运行状态（端口由它持有）。

### 2.2 帧格式

头部小端，长度随通道变化（`CTranRecvPackageProcess.h`）：

| 通道 | 头长 | 字段 |
|---|---|---|
| CMD (1) | 8 | `dataSize:u32` `type:u32` |
| VIDEO (2) / MEDIA (3) / TTS (4) / VR (5) | 12 | `dataSize:u32` `timestamp:u32` `type:u32` |
| CTRL (6) | 8 | `dataSize:u32` `type:u32` |

载荷上限：cmd 40 KiB、video 600 KiB、media 100 KiB、tts/vr 50 KiB、ctrl 10 KiB。

### 2.3 消息类型

高半字节是通道、`0x…8xxx` 为车机→手机。已收录在 `CarLifeMessage`，例如
`HU_PROTOCOL_VERSION=0x00018001`、`VIDEO_ENCODER_INIT=0x00018007`、
`VIDEO_DATA=0x00020001`、`TOUCH_ACTION_DOWN=0x00068002`。

### 2.4 握手序列

```
HU → MD  HU_PROTOCOL_VERSION      { majorVersion, minorVersion }
MD → HU  PROTOCOL_VERSION_MATCH_STATUS { matchStatus }   // 0 = 匹配
HU → MD  HU_INFO                  CarlifeDeviceInfo（23 个字段，空的不要发）
HU → MD  VIDEO_ENCODER_INIT       { width, height, frameRate }
MD → HU  VIDEO_ENCODER_INIT_DONE  { width, height, frameRate }
HU → MD  VIDEO_ENCODER_START
```

`VIDEO_ENCODER_INIT_DONE` 必须先到再发 START：提前发会被静默丢弃，表现为黑屏且无报错。

### 2.5 触摸不是 protobuf

`S_TOUCH_ACTION_DOWN { u32 x; u32 y; }` 是定长结构体、手机端 `memcpy` 读取。所以触摸载荷是两个
小端 u32，8 字节。且 **DOWN / MOVE / UP 各有独立消息类型**，类型本身就表达了手势，不需要去猜
`S_TOUCH_ACTION.action` 的枚举值（那个只写在未公开的 SPC 文档里）。

### 2.6 代码位置

| 文件 | 作用 |
|---|---|
| `shared/.../carlife/CarLifeChannel.kt` | 六通道的端口、头长、载荷上限、`adb forward` 串 |
| `shared/.../carlife/CarLifeMessage.kt` | 消息类型码、手势→消息类型映射 |
| `shared/.../carlife/CarLifeFrames.kt` | 帧头编解码（8/12 字节，小端）、流式读取、写入 |
| `shared/.../carlife/CarLifeProto.kt` | 手写 protobuf wire 编解码 + 四条消息 + 触摸结构体 + 触摸坐标映射 |
| `shared/.../link/PhoneLinkProtocol.kt` | 五个协议的可用性表（HiCar/ICCOA 标注阻断原因） |
| `common/.../carlife/CarLifeAdbSession.kt` | 会话：开通道、握手、拉视频 NAL、发触摸 |

视频复用 `shared/.../adb/AnnexB.kt` 的流式 NAL 切分与 `common/.../adb/AnnexBDecoder.kt` 的
`MediaCodec` 解码——CarLife 与 `screenrecord` 一样给的是 Annex-B 裸流。

## 三、验证状态

**已用 JVM 单测钉住（25 + 5 个）**：头长度与字节序、两种头形状、超长载荷拒绝、头跨读断裂、截断
报错、varint 边界（0/127/128/16383/`Int.MAX_VALUE`/负数 10 字节）、四条消息往返、未知字段跳过、
触摸 8 字节布局、letterbox 黑边落点丢弃、端口表与百度常量一致。

**未验证（需要真机 + 装 CarLife 的手机）**：真实握手、视频出画、触摸回传、音频。模拟器装不了
CarLife 的完整服务（需要其账户与连接模式），所以这一层只能等真机。

## 四、下一步

1. `CarLifeActivity`：SurfaceView + letterbox + 触摸按画面矩形归一化（可直接照搬
   `AdbMirrorActivity` 的做法），接 `CarLifeAdbSession`。
2. 音频：MEDIA 通道 PCM → `AudioTrack`；TTS 通道单独一路，避免导航播报被音乐覆盖。
3. 入口：手机识别到"装了 CarLife 的安卓机"后给两个选项（ADB 投屏 / CarLife），并记住选择。
4. 真机抓日志：握手失败最常见的原因是 CarLife App 没进入 USB 模式，或手机侧端口没起来。

## 五、许可

CarLife 协议实现参考了 `ApolloAuto/apollo-DuerOS`（Apache-2.0，原仓库当前 404，镜像
`674809/carlife` 仍可访问）。本仓库只是按其公开的 `.proto` 与常量重新实现，未复制其 C++ 代码；
若后续引入其源码或 `.proto` 文件，需保留 Apache-2.0 的 NOTICE。
