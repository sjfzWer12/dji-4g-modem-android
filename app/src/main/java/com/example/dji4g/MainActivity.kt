package com.example.dji4g

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : Activity() {

    private lateinit var logTv: TextView
    private lateinit var modeTv: TextView
    private lateinit var modem: UsbModem

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.example.dji4g.USB_PERMISSION") {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                log(if (granted) "✅ USB 权限已授予" else "❌ USB 权限被拒绝")
            }
        }
    }

    private val shizukuPermissionListener = object : Shizuku.OnRequestPermissionResultListener {
        override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
            log(if (grantResult == PackageManager.PERMISSION_GRANTED)
                "✅ Shizuku 已授权，可重新点「联网」" else "❌ Shizuku 未授权")
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val trace = sw.toString()
            val file = try {
                val f = File(filesDir, "crash.log")
                f.writeText(trace)
                f.absolutePath
            } catch (e: Exception) {
                "写入失败: ${e.message}"
            }
            try {
                android.util.Log.e("DJI4G", "CRASH", throwable)
            } catch (e: Exception) {}
            runOnUiThread {
                try {
                    AlertDialog.Builder(this)
                        .setTitle("崩溃：${throwable.javaClass.simpleName}")
                        .setMessage("${trace.take(5000)}\n\n[日志] $file")
                        .setPositiveButton("关闭") { _, _ ->
                            try { defaultHandler?.uncaughtException(thread, throwable) } catch (e: Exception) {}
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                        .setCancelable(false)
                        .show()
                } catch (e: Exception) {
                    try { defaultHandler?.uncaughtException(thread, throwable) } catch (e2: Exception) {}
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        buildUi()

        modem = UsbModem(this)

        try {
            val filter = IntentFilter("com.example.dji4g.USB_PERMISSION")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(usbReceiver, filter)
            }
        } catch (e: Exception) {
            log("USB receiver 注册失败: ${e.message}")
        }

        log("已启动。请用 USB-C OTG 线插入 DJI 4G 模块，再点「① 检测」。")
        try {
            Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    log("✅ Shizuku binder 已收到！服务端 v${Shizuku.getVersion()}")
                }
            })
        } catch (e: Throwable) {
            log("binder 监听注册失败: ${e.message}")
        }
        log("Shizuku: ${NetworkHelper.shizukuStatus()}")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 32, 24, 24)
        }

        val title = TextView(this).apply {
            text = "DJI 4G 模块上网"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        root.addView(title)

        modeTv = TextView(this).apply {
            text = "当前 USB 模式：未知（点②或③查看）"
            textSize = 18f
            setTextColor(Color.parseColor("#1565C0"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        root.addView(modeTv)

        fun addButton(label: String, action: () -> Unit) {
            val b = Button(this).apply {
                text = label
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
                setOnClickListener { action() }
            }
            root.addView(b)
        }

        addButton("⓪ 授权 Shizuku 权限（先点我）") {
            log("=== Shizuku 诊断 ===\n${NetworkHelper.shizukuDiagnostic()}")
            ensureShizuku()
        }
        addButton("① 检测 USB 模块") { detectUsb() }
        addButton("② 识别芯片 / 读诊断 (ATI)") { identify() }
        addButton("③ 切换 USB 模式→31") { switchRndis() }
        addButton("↩️ 恢复 DJI 模式→30") { restoreDji() }
        addButton("④ 联网 (Shizuku 配网)") { if (ensureShizuku()) connect() }
        addButton("🔍 网卡诊断(免Shizuku)") { diagInterfaces() }
        addButton("⑤ 一键全流程") { if (ensureShizuku()) fullFlow() }
        addButton("🟢 一键联网(root)") { rootOneClick() }
        addButton("📋 复制全部日志") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", logTv.text.toString()))
            Toast.makeText(this, "已复制全部日志", Toast.LENGTH_SHORT).show()
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = 12 }
        }
        logTv = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.BLACK)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(logTv)
        root.addView(scroll)

        setContentView(root)
    }

    private fun updateModeDisplay(modeResp: String) {
        val m = Regex("GTUSBMODE:\\s*(\\d+)").find(modeResp)?.groupValues?.get(1) ?: return
        val desc = when (m) {
            "31" -> "ECM 网卡模式 ✅"
            "30" -> "DJI 私有模式"
            else -> ""
        }
        runOnUiThread {
            modeTv.text = "当前 USB 模式：$m  $desc"
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            if (::logTv.isInitialized) {
                logTv.append(msg + "\n")
                logTv.post {
                    val p = logTv.parent
                    if (p is ScrollView) p.fullScroll(ScrollView.FOCUS_DOWN)
                }
            } else {
                android.util.Log.i("DJI4G", msg)
            }
        }
    }

    private fun ensureShizuku(): Boolean {
        return try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            when {
                !Shizuku.pingBinder() -> {
                    log("❌ Shizuku 服务未运行，请先打开 Shizuku 应用并启动")
                    false
                }
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                    Shizuku.requestPermission(1000)
                    log("已请求 Shizuku 授权，请在弹出对话框点「允许」，然后重新点此按钮")
                    false
                }
                else -> true
            }
        } catch (e: Throwable) {
            log("Shizuku 异常: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun bg(block: () -> String) {
        Thread { log(block()) }.start()
    }

    private fun detectUsb() = bg {
        val sb = StringBuilder("=== USB 设备列表 ===\n")
        val devices = modem.listDevices()
        if (devices.isBlank()) sb.append("(没有发现 USB 设备，请确认已用 OTG 线插入模块并已开机)\n")
        else sb.append(devices)
        val dj = modem.findDjiDevice()
        if (dj != null) {
            sb.append("目标模块: VID=${dj.vendorId.toString(16)} PID=${dj.productId.toString(16)}\n")
            if (!modem.hasPermission(dj)) {
                sb.append("正在请求 USB 权限...\n")
                modem.requestPermission(dj, usbReceiver)
            } else {
                sb.append("已有 USB 权限 ✅\n")
            }
        } else {
            sb.append("未识别到 DJI 模块 (VID 2CA3)\n")
        }
        sb.toString()
    }

    private fun identify() = bg {
        val dj = modem.findDjiDevice() ?: return@bg "❌ 未找到模块"
        if (!modem.hasPermission(dj)) return@bg "❌ 无 USB 权限，请先点「① 检测」"
        val port = modem.openAtPort(dj) ?: return@bg "❌ 找不到 AT 串口接口"
        val cmds = listOf(
            "AT", "ATI", "AT+CGMM", "AT+GTUSBMODE?", "AT+GTUSBMODE=?",
            "AT+CGDCONT?", "AT+CSQ", "AT+CREG?", "AT+COPS?", "AT+CIMI", "AT+CCID"
        )
        val sb = StringBuilder("=== 芯片诊断 ===\n")
        for (c in cmds) {
            val r = modem.sendAt(port.connection, port.epIn, port.epOut, c, 1500)
            sb.append("> $c\n$r\n\n")
            if (c == "AT+GTUSBMODE?") updateModeDisplay(r)
        }
        sb.toString()
    }

    private fun switchRndis() = bg {
        val dj = modem.findDjiDevice() ?: return@bg "❌ 未找到模块"
        if (!modem.hasPermission(dj)) return@bg "❌ 无 USB 权限，请先点「① 检测」"
        val port = modem.openAtPort(dj) ?: return@bg "❌ 找不到 AT 串口"
        val r1 = modem.sendAt(port.connection, port.epIn, port.epOut, "AT+GTUSBMODE=31", 2000)
        val r2 = modem.sendAt(port.connection, port.epIn, port.epOut, "AT+GTUSBMODE?", 1500)
        updateModeDisplay(r2)
        "=== 切换 USB 模式 31 ===\n> AT+GTUSBMODE=31\n$r1\n> AT+GTUSBMODE?\n$r2\n\n" +
            "本模块只支持 30/31，24(RNDIS)不可用。若返回 OK 已写入。\n" +
            "新配置需重启生效：请【拔掉模块再插回】，然后点「① 检测」看新出现的 USB 接口类型。"
    }

    private fun restoreDji() = bg {
        val dj = modem.findDjiDevice() ?: return@bg "❌ 未找到模块"
        if (!modem.hasPermission(dj)) return@bg "❌ 无 USB 权限"
        val port = modem.openAtPort(dj) ?: return@bg "❌ 找不到 AT 串口"
        val r1 = modem.sendAt(port.connection, port.epIn, port.epOut, "AT+GTUSBMODE=30", 2000)
        val r2 = modem.sendAt(port.connection, port.epIn, port.epOut, "AT+GTUSBMODE?", 1500)
        updateModeDisplay(r2)
        "=== 恢复 DJI 模式 30 ===\n> AT+GTUSBMODE=30\n$r1\n> AT+GTUSBMODE?\n$r2\n\n" +
            "已尝试切回 30，拔插模块后恢复 DJI 原有功能。"
    }

    private fun diagInterfaces() = bg {
        "=== 内核网卡(Java API) ===\n${NetworkHelper.listJavaInterfaces()}\n\n" +
        "=== 框架网络(ConnectivityManager) ===\n${NetworkHelper.listFrameworkNetworks(this)}\n\n" +
        "=== SystemServiceHelper 各服务 ===\n${NetworkHelper.testSystemServiceHelper()}\n\n" +
        "=== 启用以太网(正确方式) ===\n${NetworkHelper.tryEnableEthernet()}\n\n" +
        "=== raw transact 测试 ===\n${NetworkHelper.rawTransactGetAvailableInterfaces()}\n\n" +
        "=== 启用以太网(raw) ===\n${NetworkHelper.enableEthernetViaRawTransact()}\n\n" +
        "=== 网络服务名排查 ===\n${NetworkHelper.testMoreServices()}\n\n" +
        "=== network_management 接口详情 ===\n${NetworkHelper.dumpNetworkManagement()}\n\n" +
        "=== INetworkManagementService 方法+测试 ===\n${NetworkHelper.dumpAndTestNetworkManagement()}\n\n" +
        "=== 配置 usb0 静态IP ===\n${NetworkHelper.configureUsb0Static()}\n\n" +
        "=== 网关可达性测试 ===\n${NetworkHelper.testGateway()}\n\n" +
        "=== connectivity 路由方法 ===\n${NetworkHelper.dumpConnectivityRouteMethods()}\n\n" +
        "=== IConnectivityManager 全方法 ===\n${NetworkHelper.dumpAllConnectivityMethods()}\n\n" +
        "=== Shizuku ip 命令(对比) ===\n${NetworkHelper.runShell("ip -o link show")}"
    }

    private fun connect() = bg {
        val sb = StringBuilder("=== 联网 ===\n")
        if (!NetworkHelper.isShizukuReady()) {
            sb.append("❌ Shizuku 未就绪，无法配网\n")
            return@bg sb.toString()
        }
        sb.append("Shizuku ✅\n--- 尝试启用 EthernetManager ---\n")
        sb.append(NetworkHelper.tryEthernetManager())
        val iface = NetworkHelper.findUsbEthernetIface()
        if (iface == null) {
            sb.append("未发现 USB 网卡\n")
            sb.append("--- 内核所有网卡列表 ---\n${NetworkHelper.runShell("ip -o link show")}\n")
        } else {
            sb.append("发现网卡: $iface\n")
            sb.append(NetworkHelper.setInterfaceUp(iface))
            Thread.sleep(3000)
            sb.append("--- 网卡状态 ---\n${NetworkHelper.ifaceState(iface)}\n")
            sb.append("--- 手动配置(兜底) ---\n${NetworkHelper.configureManual(iface)}\n")
            sb.append("--- 验证: ping 网关 ---\n${NetworkHelper.runShell("ping -c 2 -W 2 192.168.225.1")}")
        }
        sb.toString()
    }

    private fun fullFlow() = bg {
        val sb = StringBuilder("=== 一键全流程 ===\n")
        val dj = modem.findDjiDevice()
        if (dj == null) { sb.append("❌ 未找到模块\n"); return@bg sb.toString() }
        if (!modem.hasPermission(dj)) { sb.append("❌ 无权限，先点「①」\n"); return@bg sb.toString() }
        val port = modem.openAtPort(dj)
        if (port == null) { sb.append("❌ 无 AT 口\n"); return@bg sb.toString() }
        sb.append("芯片: ${modem.sendAt(port.connection, port.epIn, port.epOut, "ATI", 1500)}\n")
        sb.append("切模式31: ${modem.sendAt(port.connection, port.epIn, port.epOut, "AT+GTUSBMODE=31", 2000)}\n")
        sb.append("当前模式: ${modem.sendAt(port.connection, port.epIn, port.epOut, "AT+GTUSBMODE?", 1500)}\n")
        sb.append("等待 10 秒让模块重枚举...\n")
        Thread.sleep(10000)
        if (!NetworkHelper.isShizukuReady()) { sb.append("❌ Shizuku 未就绪\n"); return@bg sb.toString() }
        sb.append(NetworkHelper.tryEthernetManager())
        val iface = NetworkHelper.findUsbEthernetIface()
        if (iface != null) {
            sb.append("网卡: $iface\n${NetworkHelper.setInterfaceUp(iface)}")
            Thread.sleep(3000)
            sb.append(NetworkHelper.ifaceState(iface))
            sb.append(NetworkHelper.configureManual(iface))
            sb.append("ping 网关:\n${NetworkHelper.runShell("ping -c 2 -W 2 192.168.225.1")}")
        } else {
            sb.append("未发现 USB 网卡\n${NetworkHelper.runShell("ip -o link show")}")
        }
        sb.toString()
    }

    private fun rootOneClick() = bg {
        "=== 🟢 一键联网(root) ===\n" + NetworkHelper.rootOneClick()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        try { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) } catch (e: Exception) {}
    }
}
