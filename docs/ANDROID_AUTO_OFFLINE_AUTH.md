# 手机互联：CarPlay / Android Auto / CarLife 的真实边界

这份文档回答一个问题：**在 Android 车机上做一个"跟 CarPlay 一样"的手机互联，发送端是 Android 手机，哪条路能走通。**

结论在前，证据在后。协议细节来自可核对的源码或逆向笔记，不是推测。

---

## 1. 结论

| 协议 | 车机端能否自行实现 | 手机端要装东西 | 认证是否离线 | 依据 |
|---|---|---|---|---|
| **Apple CarPlay** | 能，但要 MFi 认证芯片 | 不要 | **是（硬件芯片）** | 本项目已在做；LIVI README：芯片 "cannot be emulated and that LIVI neither ships nor bypasses" |
| **Android Auto（开源手机端）** | **能** | 要（AARE） | **是（自签证书 + 首次信任）** | AARE `docs/certificates.md`，逆向自 AA app 17.7 |
| **Android Auto（Google 官方手机端）** | 不能 | 不要 | 否（车机证书必须 Google 签发） | 同上：信任库只有一个锚点 |
| **百度 CarLife** | 能 | 要（CarLife App） | 是 | 车机端协议库 Apache-2.0 开源 |
| **华为 HiCar** | 不能 | — | — | SDK 未公开，需合作准入 + 认证 |
| **ICCOA Carlink** | 不能 | — | — | 联盟 SDK + 认证，未认证车机手机端不识别 |

**一句话**：CarPlay 的"离线认证"是 MFi 芯片；Android 侧的等价物是**自签证书 + 首次信任（TOFU）**。后者只在**开源手机端**成立 —— Google 官方 Android Auto 只认 Google 签发的车机证书。

---

## 2. "离线认证"在两侧分别是什么

### 2.1 CarPlay：一颗不可模拟的芯片

车机必须在 I²C 挂一颗 Apple MFi 认证协处理器。iPhone 发起挑战，芯片用出厂写入的私钥应答，全程不联网、不需要 Apple 账号。

- 芯片不能软件模拟，也没有公开绕过方案。
- 本项目（DiPlay / xcertplay）走的就是这条路：板载 MFi 芯片，或通过 CH341 外挂。
- 这解释了 CarPlay 车机端为什么"插线即用、零配置"：认证是硬件完成的，软件只负责把音视频通道跑起来。

### 2.2 Android Auto：双向 TLS

Android Auto 没有认证芯片，用 **TLS 双向认证**代替。逆向 AA app 17.7 得到的握手顺序：

```
VersionRequest / VersionResponse          （明文）
        ↓
TLS 1.2 握手，每一条记录封装在 ENCAPSULATED_SSL（channel 0, id 3）
        ↓
AuthComplete（status 0 = 通过，负数 = 拒绝）
        ↓
ServiceDiscoveryRequest / Response
        ↓
ChannelOpenRequest × N（video / input / audio / sensor …）
        ↓
媒体流（H.264 / PCM）
```

三个容易搞错的地方：

1. **手机是 TLS server，车机是 TLS client**，不是反过来。车机主动发 ClientHello。
2. **双向认证**，两端各验一次：车机验手机证书、手机验车机证书。
3. 两个检查互相独立，失败原因完全不同，必须分开判断。

### 2.3 手机证书：所有 AA 安装共用一张

Google 的 AA app 内嵌**同一张证书和同一把 RSA-2048 私钥**，不分机型、不做设备证明：

| 证书 | Subject | Issuer | 有效期 |
|---|---|---|---|
| 根 | `O=Google Automotive Link` | 自身 | 2014-06-06 → 2044-06-05 |
| 手机叶证书 | `O=CarService` | Google Automotive Link | 2014-07-04 → **2026-12-31** |

含义：

- 任何"不检查手机证书"的车机，都能和**任何**装了 AA 的手机连上。
- 检查的车机会把非 Google 签发的证书拒成 `status -3`。
- 叶证书 2026-12-31 到期，Google 可通过服务端开关远程更换；查有效期的车机报 `-24`。

### 2.4 车机证书：Google 只认自己的 CA

Google AA 的信任库只有**一个锚点**：`Google Automotive Link` 根。车机证书必须链到它并过有效期检查。它还会拒绝：

- 车机发来手机自己那张 `CarService` 证书
- 车机发来根证书本身

**后果**：Google 官方 AA 只和"证书由 Google 签发"的车机说话。车厂能拿到，个人项目拿不到。

这就是"自己写个 AA 车机端让官方 AA 连上来"不成立的原因 —— 不是协议不会写，是**证书签不出来**。

### 2.5 但开源手机端不检查签发者

AARE（开源手机侧 AA 实现）用 **TOFU（Trust On First Use）**：

- 不要求车机证书由 Google 签发。
- 每个车机记一条 SHA-256，第一次见弹 *New head unit*，司机点一次 *Trust*。
- 之后自动重连；证书变了（车机刷机）会再问一次。

各车机对手机证书的检查情况（AARE 实测 + 社区报告）：

| 车机 | 检查手机证书 | AARE 能用吗 |
|---|---|---|
| Google DHU（参考实现） | 是 | **不能**（`-3`） |
| 基于 Google 接收库的原厂车机 | 大概率是 | 大概率不能 |
| Denso Ten E-DA2（2026 丰田陆巡 79 原厂机） | **否** | **能** |
| openauto / Crankshaft | 否 | 能 |
| Headunit Revived | 否 | 能 |
| LIVI / aa-proxy-rs | 否 | 能 |
| 部分后装 / OEM 车机 | 不一 | 部分能 |

**关键推论**：车机端完全可以自签证书，**只要手机端跑开源实现**。这就把"离线认证"补齐了 —— 不联网、不申请、不激活，本地生成证书，手机点一次信任。

---

## 3. GitHub 上的现成项目（2026-11 核实）

| 项目 | 语言 | 角色 | 对本案的价值 |
|---|---|---|---|
| [prototux/AARE](https://github.com/prototux/AARE) | Kotlin | **手机侧** AA 协议栈，无 GMS | 协议参考；`aap/` 是纯 Kotlin/JVM；自带车机模拟器 |
| [f-io/LIVI](https://github.com/f-io/LIVI) | Rust | **车机侧** CarPlay + AA，Linux/macOS | 车机侧架构参考；证明 AA 路不需要 MFi |
| aa-proxy-rs | Rust | AA 代理 | 参考 |
| openauto / aasdk | C++ | 车机侧 AA | 最早的参考实现，protobuf 定义的来源 |
| [youcci/playport](https://github.com/youcci/playport) | Kotlin | CarPlay 服务端接收器（无线，浏览器出画面） | CarPlay 侧的无 dongle 方案 |
| [fython/wheelplay](https://github.com/fython/wheelplay) | Kotlin | Android 上的 CarPlay server | 同类参考 |

**AARE 协议层规模**：13 个 Kotlin 文件 + 256 个 `.proto`，`aap/src/main/kotlin/net/prototux/aare/aap/` 下为 `AapSession` / `Cryptor` / `Frame` / `FrameIo` / `MessageIds` / `PinStore` / `Transport` / `cert/SelfSignedCertificate` / `channels/*` / `wireless/*`。

许可：**GPL-3.0-or-later**，与本项目 LICENSE 一致，**许可兼容，可移植**。

---

## 4. 三条路径对照

| | A. ADB 投屏（已实现） | B. AA 协议 + AARE 手机端（本轮） | C. AA 协议 + 官方 AA |
|---|---|---|---|
| 手机端 | 零安装，开一次 USB 调试 | 装 AARE（开源，无 GMS） | 系统自带 |
| 覆盖机型 | 所有 Android | Android 8+，含国产机 | 有 GMS + AA 的手机 |
| 车机端 | 已有 | 需实现 head unit | 需 Google 签发证书 ❌ |
| 画面 | 原样投屏 | 原生 AA UI（导航卡、媒体、语音） | 原生 AA UI |
| 音频 | 无 | 有（多通道） | 有 |
| 触摸延迟 | 100–300 ms（`input` 进程） | 低（USB bulk + InputReport） | 低 |
| 授权 | 无 | 无（自签 + 首次信任） | 需 Google 授权 ❌ |

**C 不可行**（见 2.4）。**A 已跑通**，但本质是投屏。**B 是唯一能做到"跟 CarPlay 一致"的**，代价是手机装一次 App、首次点一次信任。

---

## 5. 已落地代码

`shared/src/main/java/com/shilapi/xcertplay/aap/`（纯 JVM，可单测）：

| 文件 | 内容 |
|---|---|
| `AapFrame.kt` | 帧编解码：1 字节 channel + 1 字节 flags + 2 字节**大端**长度；分片消息首帧带 4 字节总长；`AapFrameReader` 处理头跨读；`AapMessageAssembler` 重组并对半包/错序计数 |
| `AapMessageIds.kt` | 全部通道/服务 ID、`MessageStatus` 语义 |
| `Aoa.kt` | AOA 常量、版本小端解析、`AoaHandshake.plan()` 生成完整控制请求序列 |
| `HeadUnitCertificate.kt` | 本地生成 RSA-2048 自签 X.509 v3（手写 DER，不引入 BouncyCastle），SHA-256 指纹 |
| `AapTlsStream.kt` | **车机作 TLS client**；握手走 `ENCAPSULATED_SSL`，之后每消息独立加密并保留通道号；不验证手机链，捕获指纹做 TOFU |
| `AapTransport.kt` | 传输接缝（USB bulk / TCP）+ 内存回环管道 |

设计要点：

- **帧长度字段必须大端**。从 C++ 车机实现移植时最容易错的就是这里 —— 它们自己的私有帧用主机序，AAP 规定大端。
- **`MAX_PAYLOAD = 16128 - 8`**：官方手机把整帧卡在 16128 字节内，部分车机按 16 KiB 分配接收缓冲，超了就表现为"读到一个半帧然后错位"。
- **AOA 版本是小端**（`0x02 0x00`），按大端读会得到 512。
- **证书 notBefore 回退一天**：车机常无网络、时钟偏慢，证书"尚未生效"会被单独拒成 `-23`。
- **每台车机各自生成证书**：共用私钥一旦被信任，任何人都能冒充这台车机。
- **加密后仍分通道**：帧头在加密外，对端要先按通道路由再解密，所以一条消息对应一条 TLS 记录，不能把多条消息混进一个记录。

---

## 6. 后续

1. `AapSession`：版本协商 → TLS → AuthComplete → 服务发现 → 通道打开。
2. `UsbAccessoryTransport`（bulk 端点）与 `TcpTransport`（对 AARE 模拟器联调）。
3. Android 侧 `AapProjectionActivity`：SurfaceView + MediaCodec + 触摸 → InputReport。
4. 音频：MEDIA_SINK 多通道 → AudioTrack。
5. 真机：手机装 AARE，首次点 Trust，看 `Advanced > Connection log`。

## 7. 未解决

- Google 官方 AA app 无法连接自签车机，除非拿到 Google 签发的 HU 证书。这一点不受本实现影响。
- 无线路径（Bluetooth 引导 + Wi-Fi Direct）本轮未做。
- AARE 是早期软件，公开的真车验证仅两台；不同 OEM 车机对手机证书的检查策略差异较大。
