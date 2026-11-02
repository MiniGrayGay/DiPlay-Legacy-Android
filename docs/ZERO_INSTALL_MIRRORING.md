# 零安装投屏：Android 上还剩哪条路，门槛在哪

需求：**手机不装任何 app**，插线或进车就能把画面投到车机上，并且能在车机上反向操作手机。

这份文档回答两件事：为什么"不装 app"和"CarPlay 式独立桌面"互斥；以及剩下的每条路各自的硬门槛。
结论都带证据，代码实现放在 `shared/.../wfd`。

---

## 1. 一条铁律

CarPlay 的独立桌面是 **iOS 系统里跑着 CarPlay 的发送端**渲染出来的。Android 上同位置的东西是
`com.google.android.projection.gearhead`（或 AARE）。**只要手机里没有任何代码在渲染，就没有"车机
专用界面"可投——能投的只有手机自己那块屏幕。**

所以：

| 想要 | 手机要装东西吗 |
|---|---|
| CarPlay 式独立桌面（导航/音乐/语音各有车机版布局） | **必须**有发送端 |
| 手机屏幕原样投上去 + 车机反控 | **不需要** |

这两件事是同一枚硬币的两面，不是实现好坏的区别。要"零安装"，就只能是**镜像**。

---

## 2. 零安装还剩三条通路

| 通路 | 无线/有线 | 画面 | 反控 | 手机端动作 | 我方能否实现 |
|---|---|---|---|---|---|
| **WFD / Miracast（车机当 sink）** | 无线（Wi-Fi Direct） | 有 | **有（UIBC）** | 系统「投屏 / 无线显示」里选一次车机 | 协议能写，**发现受系统权限限制**，见 §3 |
| **蓝牙 HFP / A2DP / AVRCP / PBAP / MAP** | 无线 | 无 | 部分（媒体键） | 蓝牙配对一次 | 能，且零风险 |
| **adbd（`screenrecord` + `input`）** | 有线 | 有 | 有 | 打开 USB 调试 | 能，代码已有 |

第三条是唯一"有线 + 零安装 + 有画面"的组合；不开调试就只剩前两条，而第二条没有画面。

---

## 3. WFD sink 的硬门槛：被不被发现

**协议不是难点，被发现才是。** Miracast 的源端（手机）在 Wi-Fi Direct 探测帧里读取 **WFD IE**
（OUI `50:6F:9A`）来判断"这个对端是一台显示设备"。Android 的 `WifiP2pManager` **不允许应用注入
自定义 IE**，所以一个普通 APK 即使把 RTSP 和 RTP 全部实现正确，手机「投屏」列表里也不会出现它。

证据：`ivygroup/miracast-sink` 是这类项目里最完整的一个，它的编译说明第一条就是"把
`framework-modify/` 下的 patch 打到 framework、重编 `services.jar` 并替换系统文件"，而"不用修改
Framework，直接在程序里管理 Wi-Fi Direct 连接与发现"被列在**未完成**清单里。

所以 WFD sink 的成立条件是 **车机侧有系统权限**：

- root（可改 `services.jar` 或直接以 root 调系统 WFD 服务），或
- 平台签名 / 作为 `priv-app` 预装，或
- 车机厂商愿意在 ROM 里开 sink 能力

普通 APK + 普通用户权限，这条路走不通。这不是协议实现的难度问题。

---

## 4. 协议事实（已核实，实现按这个来）

### 4.1 控制面

**TCP 连接由 Sink 主动连 Source 的 7236**（Source 是 RTSP server，Sink 是 client）。连上之后，
一条 TCP 连接上**双方都会发 request**，靠 `CSeq` 配对。

| 消息 | 谁发 Request | 方法 | 关键参数 |
|---|---|---|---|
| M1 | **Source** | OPTIONS | `Require: org.wfa.wfd1.0` |
| M2 | **Sink** | OPTIONS | `Public:` 支持的 WFD 方法 |
| M3 | **Source** | GET_PARAMETER | 查 `wfd_video_formats` / `wfd_audio_codecs` / `wfd_uibc_capability` 等 |
| M4 | **Source** | SET_PARAMETER | 下发选定格式 + `wfd_presentation_URL` + `wfd_client_rtp_ports` |
| M5 | **Source** | SET_PARAMETER | `wfd_trigger_method: SETUP`，让 Sink 去发 SETUP |
| M6 | **Sink** | SETUP | `Transport: RTP/AVP/UDP;unicast;client_port=<rtp>,<rtcp>` |
| M7 | **Sink** | PLAY | `Session:` |
| M8 | Sink | TEARDOWN | 结束 |
| M14/M15 | 任一方 | SET_PARAMETER | UIBC 能力与开关（含 Source 监听的 UIBC TCP 端口） |
| M16 | **Source** | GET_PARAMETER（空 body） | 保活心跳 |

M5 只是"递个眼色"，**真正的 SETUP / PLAY 一律由 Sink 发出**——写反了会表现为连上之后一直黑屏。

### 4.2 数据面

H.264（+ 音频）→ PES → **MPEG2-TS** → RTP/UDP。首帧必须是带 SPS/PPS 的 IDR。开了 HDCP 的源
（播放 DRM 内容时）需要先协商 HDCP 2.x，否则画面黑屏——这是镜像方案的固有短板，不是 bug。

---

## 5. 车机上怎么验（一条命令决定值不值得做）

在车机上执行：

```sh
adb shell pm list features | grep -i wifi.direct      # 有没有 Wi-Fi Direct
adb shell dumpsys wifi | grep -iE "wfd|p2p" | head -40 # supplicant 是否已带 WFD 子元素
adb shell service list | grep -iE "display|wifi"       # 系统 WFD 服务在不在
adb shell which su && adb shell id                      # 有没有 root
adb shell getprop | grep -iE "wfd|miracast|sink"
```

判读：

- 没有 `android.hardware.wifi.direct` → **WFD sink 直接出局**，只剩蓝牙或 adbd。
- 有 P2P、`dumpsys wifi` 里出现 `wfd_subelem` / `wfd` 字样 → 系统本身已具备 sink 能力，普通 APK
  仍不可发现，但 root 后可直接调用/改配置。
- 有 `su` → 可以做 §3 里的三条补救。

---

## 6. 实现计划（分层，先做能吃单测的部分）

| 层 | 内容 | 可验证性 |
|---|---|---|
| `shared/.../wfd/WfdRtsp.kt` | RTSP 报文读写（两端都发 request）+ M1–M8 构造 | JVM 单测 |
| `shared/.../wfd/WfdVideoFormats.kt` | `wfd_video_formats` 的 profile/level 与分辨率位图 | JVM 单测 |
| `shared/.../wfd/WfdTsDemux.kt` | MPEG2-TS → H.264 NAL | JVM 单测（合成 TS 包） |
| `shared/.../wfd/Uibc.kt` | UIBC 公共包头 + Generic 触摸输入体 | JVM 单测 |
| `common/.../WfdSinkSession.kt` | P2P 建链 + RTSP 会话 + 解码 + UIBC | 需真机 |
| `common/.../WfdProjectionActivity.kt` | SurfaceView + 触摸映射 | 需真机 |

**模拟器不能验证整链**：`WifiP2pManager` 在模拟器上不可用，也没有第二台设备能当 source。这是与
AAP 那条链路最大的区别——那条能在两台模拟器上跑穿，这条不能，只能在真车上试。

---

## 7. 与已实现方案的关系

| 方案 | 手机装 | 独立桌面 | 状态 |
|---|---|---|---|
| CarPlay | 不装 | 是 | 已实现 |
| AAP + AARE | 装一次 | 是 | 已实现并双模拟器验证 |
| **WFD sink + UIBC** | **不装** | **否（镜像）** | 协议层待实现，门槛见 §3 |
| adbd | 不装（开调试） | 否（镜像） | 已实现，非主打 |
