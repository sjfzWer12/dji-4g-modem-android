# DJI Cellular Dongle 2 给安卓手机上网 —— 完整技术文档

> 版本：v1.0（2026-09-12）
> 目标：把大疆二代 4G 模块（Cellular Dongle 2）通过 USB-C OTG 插到手机上，让手机用它的蜂窝流量上网。
> 适用设备：红米 Turbo 5（澎湃 OS 3 / HyperOS 3 / Android 16），其他 Android 14+ 手机同理。

---

## 一、结论摘要（先看这里）

整个事情分四步，**前三步已经跑通，第四步卡在 ROM 限制上**：

| 步骤 | 状态 | 说明 |
|---|---|---|
| ① 硬件识别 | ✅ 成功 | 模块插入后内核生成 `usb0` 网卡 |
| ② 切换 ECM 网卡模式 | ✅ 成功 | `AT+GTUSBMODE=31` |
| ③ 给网卡配 IP | ✅ 成功 | 通过 Shizuku 调 netd 接口，`usb0` = `192.168.225.2/24` |
| ④ 加路由（让流量走模块） | ❌ 卡住 | HyperOS 3 把「注册网络/加路由」的系统接口全砍了 |

**结论**：不 root 的情况下，前三步可行、第四步被澎湃 OS 焊死。**root（Magisk）后第四步就是几条 `su` 命令，一分钟配完。** 本文档把两条路都写清楚。

---

## 二、硬件与前提

### 2.1 模块真实身份（关键，别搞错）

大疆二代 4G 模块（Cellular Dongle 2）**不是**一代的移远 Quectel EG25-G，而是：

```
Manufacturer: Fibocom Wireless Inc.（广和通）
Model:        NL668T-GL
Revision:     19906.5090.00.02.00.23
```

- USB 标识：`VID=2ca3`、`PID=4009`
- 蜂窝网络：默认走大疆内置 eSIM（实测注册中国联通 46001，LTE）
- 硬件上是标准 Cat 4 LTE 模块，不限制用途

### 2.2 USB 模式（只有两个可选）

这块模块的固件被大疆锁定，`AT+GTUSBMODE=?` 查询只返回两个模式：

```
+GTUSBMODE: (30-31)
```

| 模式 | 含义 | USB 接口 |
|---|---|---|
| **30** | 大疆私有模式（默认） | 5 个厂商私有接口（只有大疆 Windows 驱动认识） |
| **31** | **ECM 标准网卡模式** ✅ | 标准 CDC-ECM 以太网接口（`class=2 sub=6`），安卓内核原生支持 |

**结论**：一代用 Quectel 的 `AT+QCFG="usbnet",1`；**二代是 Fibocom，用 `AT+GTUSBMODE=31`**（`AT+QCFG` 会返回 ERROR）。

### 2.3 手机前提

- 红米 Turbo 5（澎湃 OS 3 / Android 16），**未 root**
- 已安装 **Shizuku**（GitHub 版 13.6.0，Play 版不支持 Android 16）并已授权
- 一条 USB-C OTG 线（模块是 USB-C 公头，需要 OTG 转接）
- 注意：澎湃 OS 会后台杀 Shizuku，需把 Shizuku 应用设为「省电策略→无限制」+ 最近任务卡片上锁

---

## 三、APK 使用说明

### 3.1 安装

侧载安装 `DJI4G上网-v3.6.apk`（本目录）。

### 3.2 按钮与操作顺序

```
⓪ 授权 Shizuku 权限（先点我）   ← 第一次要点，弹出授权框选「允许」
① 检测 USB 模块                ← 拿 USB 权限 + 列出设备/接口
② 识别芯片                    ← 跑 AT 命令读模块信息（ATI、GTUSBMODE 等）
③ 切换 USB 模式→31            ← 发 AT+GTUSBMODE=31，切 ECM
↩️ 恢复 DJI 模式→30           ← 切回 30（给无人机用）
④ 联网 (Shizuku 配网)          ← 尝试配网（目前卡在路由）
🔍 网卡诊断(免Shizuku)          ← 完整诊断（USB/内核网卡/服务/binder/配IP）
📋 复制全部日志                 ← 一键复制日志
```

**正确流程**：

1. 先打开 Shizuku 应用，确认服务「正在运行」（绿色）
2. 回本 App，点「⓪」授权（第一次）
3. 模块插上（模式 31 时，`①检测` 里能看到 `if4 class=2 sub=6` 的 ECM 接口）
4. 点「③ 切换 USB 模式→31」，等它返回 `OK`
5. **拔掉模块再插回**（模式切换要重启才生效）
6. 点「🔍 网卡诊断」看 `usb0` 是否出现、是否配上 IP

---

## 四、核心技术发现（踩坑记录，照着做能省大量时间）

### 4.1 Shizuku 客户端库的坑

**依赖版本**（`app/build.gradle`）：

```gradle
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
```

**必须同时加 `provider` 库 + manifest 声明 ShizukuProvider**，否则 binder 收不到（报 `IllegalStateException: binder haven't been received`）：

```xml
<!-- AndroidManifest.xml -->
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:multiprocess="false"
    android:enabled="true"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
```

> 注意：Maven 上 `api` 库最新就是 **13.1.5**（没有 13.6.0）。Shizuku-API 源码仓库的版本号也停在 13.1.5。所以 **13.1.5 客户端库连 13.6.0 服务端是官方兼容的，不是版本问题**。

### 4.2 `newProcess`（跑 shell 命令）被废弃

Shizuku 13 里 `Shizuku.newProcess()` 是 **private static**，且官方已标记废弃（"planned to be removed from API 14"）。在 13.6.0 服务端上调用会报：

```
request send failed: Permission denied
```

**这就是「想跑 `ip route add` 却跑不了」的根本原因**——不是授权问题（App 已授权），是 shell 进程生成这条通道被关了。

### 4.3 正确的提权方式：`SystemServiceHelper` + `ShizukuBinderWrapper`

跑不了 shell，但 **binder 事务这条路是通的**。正确用法：

```kotlin
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

// 拿系统服务 binder（带 shell 身份）
val binder = SystemServiceHelper.getSystemService("ethernet")
// 包一层，让事务以 shell 身份执行
val wrapped = ShizukuBinderWrapper(binder)
```

> `Shizuku.transactRemote(data, reply, code)` 直接调已经废弃（第三个参数是 flags 不是事务码，用错会返回 null）。**要用 `SystemServiceHelper` + `ShizukuBinderWrapper`**。

### 4.4 HyperOS 3 把三个关键系统接口砍成了空壳

这是「第四步卡住」的直接原因。实测（通过 `SystemServiceHelper` 拿到 binder 后 dump）：

| 服务 | 接口 | 实测状态 |
|---|---|---|
| `ethernet` | `android.net.IEthernetManager` | **空壳**：`Stub` 里连 `asInterface` 都没有，`getAvailableInterfaces()` 恒返回空 |
| `network_management` | `android.os.INetworkManagementService` | **被精简到 4 个方法**：`getInterfaceConfig` / `setInterfaceConfig` / `registerObserver` / `unregisterObserver`。**没有路由、没有 DNS、没有 setInterfaceUp** |
| `connectivity` | `android.net.IConnectivityManager` | **被精简到 3 个只读方法**：`getActiveLinkProperties` / `getActiveNetworkInfo` / `getAllNetworkInfo`。**没有 registerNetworkAgent** |
| `netd` | `android.net.INetd` | 直接不存在（`ClassNotFoundException`） |

> 结论：这是小米**故意**锁的——普通手机 ROM 默认禁止「接收外来 USB 网卡」给手机上网。逆向能做到「配 IP」已经是极限，**加路由的接口全没了**。

### 4.5 给 usb0 配 IP 的正确方法（在精简版 netd 上可行）

Android 16 的 `InterfaceConfiguration` 已经**没有 `ipAddr` 字段**了（旧教程会报 `NoSuchFieldException`），改成方法调用：

```kotlin
val nmBinder = SystemServiceHelper.getSystemService("network_management")
val wrapped = ShizukuBinderWrapper(nmBinder)
val stub = Class.forName("android.os.INetworkManagementService\$Stub")
val nm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)

val cfgClass = Class.forName("android.net.InterfaceConfiguration")
val cfg = cfgClass.getConstructor().newInstance()

// 设 IP：用 LinkAddress，不是字段
val laClass = Class.forName("android.net.LinkAddress")
val inet = java.net.InetAddress.getByName("192.168.225.2")
val la = laClass.getDeclaredConstructor(
    java.net.InetAddress::class.java, Int::class.javaPrimitiveType
).apply { isAccessible = true }.newInstance(inet, 24)
cfgClass.getMethod("setLinkAddress", laClass).invoke(cfg, la)
cfgClass.getMethod("setInterfaceUp").invoke(cfg)   // 拉起接口

nm.javaClass.getMethod("setInterfaceConfig", String::class.java, cfgClass)
    .invoke(nm, "usb0", cfg)
```

执行成功后读回：

```
mAddr=192.168.225.2/24 mFlags=[broadcast, running, up, multicast]
```

### 4.6 根本墙：安卓「策略路由」

配了 IP 还不够。网关测试时发现关键证据：

```
192.168.225.1:80 ❌ SocketTimeoutException ... from /10.0.1.117 ...
```

注意 `from /10.0.1.117` 是 **WiFi 的 IP**——说明手机把流量从 WiFi 发出去了，**根本没走 usb0**。

原因：安卓用「策略路由」，每个 App 的流量按它绑定的「默认网络」（WiFi/蜂窝）走。usb0 没注册成系统网络，所以：

- 系统没有 usb0 的路由表；
- 所有 App（包括 Clash/VPN）的流量都带 WiFi 标记，从 WiFi 出去。

**哪怕 usb0 有直连路由（192.168.225.0/24），App 也用不上。** 要让流量走 usb0，只有两条路：

1. 把 usb0 注册成系统网络（`registerNetworkAgent`）→ 接口被砍；
2. root 直接跑 `ip route add` → 绕过策略路由，直接跟内核说话。

---

## 五、不 root 的替代方案（都试过，均失败）

| 方案 | 结果 |
|---|---|
| Shizuku 跑 `ip route add` | ❌ `newProcess` 被废弃，shell 跑不了 |
| 以太网服务 `setEthernetEnabled` | ❌ 空壳，调用无效果 |
| `registerNetworkAgent` 注册网络 | ❌ 方法被砍 |
| Clash Meta 开全局 | ❌ 模块是 NAT 网关不是代理；且 Clash 出口流量同样走默认网络，绕不过策略路由 |

---

## 六、root 方案（推荐，可行）

### 6.1 为什么 root 能解决

root 后 App 能用 `su` 拿 **root shell（uid 0）**，直接跟内核说话，**完全绕开被砍的系统接口**：

```bash
# 给 usb0 配 IP（或保留 App 已配好的）
su -c "ip addr add 192.168.225.2/24 dev usb0 2>/dev/null"
su -c "ip link set usb0 up"

# 加默认路由（第四步，root 一句话搞定）
su -c "ip route add default via 192.168.225.1 dev usb0"

# 设 DNS（两条常用公共 DNS）
su -c "ndc resolver setnetdns 0 '' 8.8.8.8 114.114.114.114"
```

> 网关 `192.168.225.1` 是 Fibocom NL668 ECM 模式的常见默认值；如果 ping 不通，先用 App 的「②识别芯片」跑 `AT+CGDCONT?` 看模块侧 IP，或逐个试 `192.168.0.1` / `192.168.7.1` 等。

### 6.2 解锁 BL 的前置条件（国内）

- 小米账号需要达到 **5 级**（社区等级），且绑定设备满一定天数；
- 官方「小米解锁工具」解锁，需要等 **7 天**（168 小时）；
- 解锁会**清除全部数据**、失去保修，需自行评估。

### 6.3 刷 Magisk

1. 解锁 BL 后，下载 Magisk 官方 APK；
2. 提取当前系统的 `boot.img`，用 Magisk 打补丁；
3. `fastboot flash boot` 刷入补丁后的 boot；
4. 重启，Magisk 显示已安装即可。

（详细步骤网上教程很多，此处略，重点是解锁 BL 这个 5 级账号门槛。）

---

## 七、构建环境（要自己改 APK 的话）

| 项 | 值 |
|---|---|
| 项目目录 | `D:\dji4g`（Gradle 工程，Kotlin） |
| JDK | 17（`D:\Java\jdk-17`，不能用 JDK 25） |
| Gradle | 8.7（`D:\gradle-8.7`） |
| Android SDK | `D:\Android`（platforms/android-34、build-tools/34.0.0） |
| AGP | 8.5.2 |
| Kotlin | 1.9.24 |
| compileSdk / minSdk / targetSdk | 34 / 26 / 34 |
| 依赖 | `dev.rikka.shizuku:api:13.1.5`、`provider:13.1.5` |

**编译命令**（Git Bash / MSYS）：

```bash
export JAVA_HOME=D:/Java/jdk-17
export ANDROID_HOME=D:/Android
D:/gradle-8.7/bin/gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

**国内镜像**（代理已弃用，走镜像更快）：
- Maven 依赖：`maven.aliyun.com/repository/google`、`/central`
- Gradle 发行版：`mirrors.cloud.tencent.com/gradle/`
- Android SDK：`mirrors.cloud.tencent.com/AndroidSDK/`

### 7.1 核心源文件

| 文件 | 作用 |
|---|---|
| `MainActivity.kt` | 两屏 UI + 各按钮逻辑 + 崩溃捕获 |
| `NetworkHelper.kt` | Shizuku 提权、binder 反射、配 IP、各诊断 |
| `UsbModem.kt` | USB 检测 + AT 命令收发 |
| `AndroidManifest.xml` | 权限 + ShizukuProvider + USB host 特性 |
| `res/xml/device_filter.xml` | `<usb-device vendor-id="11427" />`（0x2ca3） |

---

## 八、关键 AT 命令速查

```
AT               → OK（探活）
ATI              → 读厂商/型号（Fibocom NL668T-GL）
AT+CGMM          → 型号
AT+GTUSBMODE?    → 当前 USB 模式（30 或 31）
AT+GTUSBMODE=?   → 支持的模式（只有 30-31）
AT+GTUSBMODE=31  → 切 ECM 网卡模式（切完要拔插重启生效）
AT+GTUSBMODE=30  → 切回大疆私有模式（给无人机用）
AT+CGDCONT?      → PDP 上下文（看蜂窝数据通没通、分配到的 IP）
AT+CSQ           → 信号强度（31 = 满格）
AT+CREG?         → 注册状态（0,1 = 已注册本网）
AT+COPS?         → 运营商（46001 = 中国联通，7 = LTE）
```

---

## 九、给别人试的时候，怎么快速判断

1. 模块插上，点「①检测」→ 看有没有 `VID=2ca3` 的设备和 `if4 class=2 sub=6`（ECM）接口；
2. 点「②识别芯片」→ 看 `ATI` 是不是 Fibocom NL668T-GL、`AT+GTUSBMODE?` 是不是 31；
3. 点「🔍网卡诊断」→ 看「内核网卡」里有没有 `usb0`、「配置 usb0 静态IP」读回是不是 `mAddr=192.168.225.2/24`；
4. 前三步全对 = 硬件和配 IP 都通了，**只差路由**；
5. 路由这步，不 root 到顶了，**等 root 后跑第六节的 `su` 命令即可**。

---

*文档结束。前三步成果已固化在 APK 里，可复用；root 版联网（第四节那几条 `su` 命令）等解锁 BL 后直接加上。*
