# 大疆 4G 模块（二代）给安卓手机上网

把 DJI Cellular Dongle 2 通过 USB-C OTG 直插手机，当 4G 网卡用。

## 结论速览

| 步骤 | 能否完成 | 手段 |
|---|---|---|
| ① 硬件识别 | ✅ | 内核原生支持，插上即出 `usb0` |
| ② 切换 ECM 网卡模式 | ✅ | `AT+GTUSBMODE=31`（无需 root） |
| ③ 给 usb0 配 IP | ✅ | root 用 `ip`；无 root 用 Shizuku 调 netd |
| ④ 加默认路由 | ⚠️ **需 root** | 无 root 被澎湃 OS/HyperOS 锁死 |

**核心结论**：有 root，四条命令完事；无 root，只能到第③步，第④步（路由）被 ROM 刻意封死。

---

## 硬件与模块身份

- 模块：**DJI Cellular Dongle 2**（二代，USB-C 口）
- 真实芯片：**Fibocom NL668T-GL**（不是一代的 Quectel EG25-G）
- USB 标识：`VID=2ca3 PID=4009`，7 个接口
- USB 模式：仅支持 `30`（大疆私有）和 `31`（ECM 标准网卡）；**不支持 RNDIS(24)**
- 目标模式：**31（ECM）**，Android 内核原生支持，插入后生成 `usb0`

---

## 完整方案（有 root，推荐）

### 1. 切 ECM 模式

模块插手机，发 AT 命令（本 App「③ 切换 USB 模式→31」按钮，或串口发）：

```
AT+GTUSBMODE=31
```

写后模块软重启、USB 重新枚举，蜂窝不断电。

### 2. 配置网络（root shell）

```sh
su -c "ip link set usb0 up"
su -c "ip addr replace 192.168.225.2/24 dev usb0"
su -c "ip route replace default via 192.168.225.1 dev usb0"
su -c "setprop net.dns1 8.8.8.8"
su -c "setprop net.dns2 114.114.114.114"
```

或直接点本 App 的「🟢 一键联网(root)」按钮，等价于上面五条。

> 网关 `192.168.225.1` 是移远/广和通类模块的常见默认值。若不通，用 `ip neigh show dev usb0` 或跑一次 DHCP（`busybox udhcpc -i usb0`）探测模块真实网关。

### 3. 验证

```sh
ip addr show usb0        # 有 192.168.225.2/24
ip route show            # 默认路由 via 192.168.225.1 dev usb0
ping -I usb0 8.8.8.8     # 通了即成功
```

---

## 无 root 方案（Shizuku，只到配 IP）

无 root 时，本 App 用 **Shizuku** 提权完成前两步 + 配 IP，但**加不了路由**。技术原因：

1. **安卓策略路由**：App 流量按「默认网络」走（WiFi/蜂窝），`usb0` 未注册成系统网络，其直连路由不被 App 使用——即使配了 IP，流量仍从 WiFi 出去（网关测试里 `from /10.0.1.117` 即证据）。
2. **澎湃 OS 3 把提权接口砍光了**（实测 dump）：
   - `ethernet`（IEthernetManager）：空壳，`getAvailableInterfaces()` 恒空，`Stub` 无 `asInterface`
   - `network_management`（INetworkManagementService）：仅 4 方法（`get/setInterfaceConfig`、`register/unregisterObserver`），**无路由/DNS 方法**
   - `connectivity`（IConnectivityManager）：仅 3 只读方法，**无 `registerNetworkAgent`**
   - Shizuku `newProcess`（跑 shell）：已废弃，13.6.0 服务端拒绝（`Permission denied`）

结论：**不 root 无法给 usb 网卡加路由**，这是 ROM 刻意的限制。

### 无 root 能做的（本 App 已实现）

- Shizuku 正确提权方式：`SystemServiceHelper.getSystemService(name)` + `ShizukuBinderWrapper`（不是过时的 `transactRemote` 直调）
- 配 IP（到第③步）：
  ```kotlin
  val nm = INetworkManagementService.Stub.asInterface(
      ShizukuBinderWrapper(SystemServiceHelper.getSystemService("network_management")))
  val cfg = InterfaceConfiguration()
  cfg.setLinkAddress(LinkAddress(InetAddress.getByName("192.168.225.2"), 24))
  cfg.setInterfaceUp()
  nm.setInterfaceConfig("usb0", cfg)
  ```
  > Android 16 的 `InterfaceConfiguration` 已无 `ipAddr` 字段，改用 `setLinkAddress(LinkAddress)` / `setInterfaceUp()`。

---

## AT 命令速查（Fibocom NL668）

| 命令 | 作用 |
|---|---|
| `AT+GTUSBMODE?` | 查当前 USB 模式 |
| `AT+GTUSBMODE=?` | 列出支持模式（返回 `(30-31)`） |
| `AT+GTUSBMODE=31` | 切 ECM 标准网卡 |
| `AT+GTUSBMODE=30` | 恢复大疆私有模式（电脑/遥控器用） |
| `AT+CGDCONT?` | 查 PDP 上下文（IP 已分配即蜂窝已通） |
| `AT+CSQ` | 信号质量 |
| `AT+COPS?` | 运营商 |

---

## 构建

- JDK 17、Gradle 8.7、Android SDK platform-34 / build-tools 34.0.0
- 依赖：`dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`
- manifest 需声明 `ShizukuProvider`（否则 binder 收不到）
- `minSdk 26`（Android 8.0+），**纯 Kotlin 无原生库，一个 APK 通吃所有 CPU 架构**

```sh
export JAVA_HOME=<jdk17> ANDROID_HOME=<sdk>
gradle assembleRelease
```

---

## 仓库结构

```
app/src/main/java/com/example/dji4g/
├── MainActivity.kt      # UI + 各按钮 + 日志
├── NetworkHelper.kt     # Shizuku 提权 + 配网 + 诊断核心
└── UsbModem.kt          # USB 串口 AT 通信
```
