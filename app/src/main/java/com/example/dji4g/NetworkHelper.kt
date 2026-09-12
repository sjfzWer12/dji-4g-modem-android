package com.example.dji4g

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

object NetworkHelper {

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun shizukuStatus(): String {
        return try {
            when {
                !Shizuku.pingBinder() -> "❌ Shizuku 服务未运行（请打开 Shizuku 应用并启动）"
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    "⚠️ Shizuku 已运行但未授权本应用（点④联网时会请求授权）"
                else -> "✅ Shizuku 已连接已授权"
            }
        } catch (e: Throwable) {
            "❌ Shizuku 异常: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun listJavaInterfaces(): String {
        return try {
            val sb = StringBuilder()
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                val addrs = ni.inetAddresses?.toList()?.joinToString { it.hostAddress ?: "?" } ?: "(none)"
                sb.append("${ni.name}  up=${ni.isUp}  addrs=[$addrs]\n")
            }
            sb.toString().ifBlank { "(无接口)" }
        } catch (e: Throwable) { "ERR: ${e.message}" }
    }

    fun listFrameworkNetworks(ctx: Context): String {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val sb = StringBuilder()
            for (n in cm.allNetworks) {
                val cap = cm.getNetworkCapabilities(n)
                val lp = cm.getLinkProperties(n)
                val eth = cap?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                val wifi = cap?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                val cell = cap?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                sb.append("net${n.hashCode()}: eth=$eth wifi=$wifi cell=$cell iface=${lp?.interfaceName} addrs=${lp?.linkAddresses}\n")
            }
            sb.toString().ifBlank { "(无框架网络)" }
        } catch (e: Throwable) { "ERR: ${e.message}" }
    }

    fun tryEnableEthernet(): String {
        val sb = StringBuilder()
        try {
            val binder = SystemServiceHelper.getSystemService("ethernet")
            if (binder == null) return "❌ ethernet 服务返回 null"
            val wrapped = ShizukuBinderWrapper(binder)
            sb.append("✅ 拿到 ethernet binder\n")
            // 打印类方法签名（诊断）
            try {
                val stub = Class.forName("android.net.IEthernetManager\$Stub")
                sb.append("--- Stub 方法 ---\n")
                stub.declaredMethods.forEach { m -> sb.append("  " + m.name + "(" + m.parameterTypes.joinToString(",") { p -> p.simpleName } + ")\n") }
                val iface = Class.forName("android.net.IEthernetManager")
                sb.append("--- IEthernetManager 方法 ---\n")
                iface.declaredMethods.forEach { m -> sb.append("  " + m.name + "(" + m.parameterTypes.joinToString(",") { p -> p.simpleName } + ")\n") }
            } catch (e: Throwable) { sb.append("dump 异常: ${e.message}\n") }
            // 健壮的 asInterface 查找
            try {
                val stub = Class.forName("android.net.IEthernetManager\$Stub")
                val asIf = (stub.methods + stub.declaredMethods).firstOrNull { it.name == "asInterface" && it.parameterCount == 1 }
                if (asIf == null) {
                    sb.append("❌ 找不到 asInterface 方法\n")
                } else {
                    asIf.isAccessible = true
                    val iEth = asIf.invoke(null, wrapped)
                    sb.append("✅ asInterface 调用成功\n")
                    for (mname in listOf("setEthernetEnabled", "setInterfaceUp")) {
                        val m = iEth.javaClass.methods.firstOrNull { it.name == mname }
                        if (m != null) {
                            m.invoke(iEth, *(if (mname == "setEthernetEnabled") arrayOf(true) else arrayOf("usb0", true)))
                            sb.append("✅ $mname 调用成功\n")
                        } else {
                            sb.append("❌ 找不到 $mname\n")
                        }
                    }
                }
            } catch (e: Throwable) {
                sb.append("asInterface 异常: ${e.javaClass.simpleName}: ${e.message} | cause=${e.cause?.message}\n")
            }
        } catch (e: Throwable) {
            sb.append("外层异常: ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    fun testTransactRemoteServices(): String {
        val sb = StringBuilder()
        for (name in listOf("activity", "netd", "connectivity", "wifi", "ethernet")) {
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                data.writeInterfaceToken("android.os.IServiceManager")
                data.writeString(name)
                Shizuku.transactRemote(data, reply, 1) // GET_SERVICE_TRANSACTION
                val b = reply.readStrongBinder()
                reply.recycle()
                data.recycle()
                sb.append("$name -> ${if (b != null) "✅ 有 binder" else "❌ null"}\n")
            } catch (e: Throwable) {
                sb.append("$name -> ❌ ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        return sb.toString()
    }

    fun testSystemServiceHelper(): String {
        val sb = StringBuilder()
        for (name in listOf("activity", "ethernet", "netd", "connectivity", "wifi")) {
            try {
                val binder = SystemServiceHelper.getSystemService(name)
                sb.append("$name -> ${if (binder != null) "✅ 有 binder" else "❌ null"}\n")
            } catch (e: Throwable) {
                sb.append("$name -> ❌ ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        return sb.toString()
    }

    fun rawTransactGetAvailableInterfaces(): String {
        return try {
            val binder = SystemServiceHelper.getSystemService("ethernet") ?: return "❌ ethernet binder null"
            val wrapped = ShizukuBinderWrapper(binder)
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken("android.net.IEthernetManager")
            val ok = wrapped.transact(1, data, reply, 0) // code 1 = getAvailableInterfaces
            reply.setDataPosition(0)
            val ifaces = reply.createStringArray()
            val s = if (ifaces != null) ifaces.joinToString(",") else "(null)"
            val r = "transact(1) ok=$ok 接口=[$s]"
            reply.recycle(); data.recycle()
            r
        } catch (e: Throwable) {
            "❌ ${e.javaClass.simpleName}: ${e.message} | cause=${e.cause?.message}"
        }
    }

    fun enableEthernetViaRawTransact(): String {
        val sb = StringBuilder()
        try {
            val binder = SystemServiceHelper.getSystemService("ethernet") ?: return "❌ null"
            val wrapped = ShizukuBinderWrapper(binder)
            // 13 = setEthernetEnabled(boolean)
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                data.writeInterfaceToken("android.net.IEthernetManager")
                data.writeInt(1)
                val ok = wrapped.transact(13, data, reply, 0)
                reply.recycle(); data.recycle()
                sb.append("setEthernetEnabled(true) ok=$ok\n")
            } catch (e: Throwable) { sb.append("setEthernetEnabled 异常: ${e.message}\n") }
            // 11 = enableInterface(String, receiver)
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                data.writeInterfaceToken("android.net.IEthernetManager")
                data.writeString("usb0")
                data.writeStrongBinder(null)
                val ok = wrapped.transact(11, data, reply, 0)
                reply.recycle(); data.recycle()
                sb.append("enableInterface(usb0) ok=$ok\n")
            } catch (e: Throwable) { sb.append("enableInterface 异常: ${e.message}\n") }
            // 14 = getInterfaceList()
            try {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                data.writeInterfaceToken("android.net.IEthernetManager")
                val ok = wrapped.transact(14, data, reply, 0)
                reply.setDataPosition(0)
                val list = ArrayList<String>()
                reply.readStringList(list)
                sb.append("getInterfaceList() ok=$ok 列表=[${list.joinToString(",")}]\n")
                reply.recycle(); data.recycle()
            } catch (e: Throwable) { sb.append("getInterfaceList 异常: ${e.message}\n") }
        } catch (e: Throwable) {
            sb.append("外层异常: ${e.message}\n")
        }
        return sb.toString()
    }

    fun testMoreServices(): String {
        val sb = StringBuilder()
        val names = listOf(
            "netd", "network_management", "network_stack", "netpolicy",
            "ipsec", "vpn_management", "tethering", "netstats", "network_score",
            "ethernet", "connectivity", "servicediscovery", "dnsresolver"
        )
        for (name in names) {
            try {
                val b = SystemServiceHelper.getSystemService(name)
                sb.append("$name -> ${if (b != null) "✅ 有" else "❌ null"}\n")
            } catch (e: Throwable) { sb.append("$name -> ❌ ${e.javaClass.simpleName}: ${e.message}\n") }
        }
        return sb.toString()
    }

    fun dumpNetworkManagement(): String {
        val sb = StringBuilder()
        try {
            val binder = SystemServiceHelper.getSystemService("network_management")
            if (binder == null) return "❌ network_management binder null"
            sb.append("✅ network_management binder 拿到\n")
            try {
                val desc = binder.interfaceDescriptor
                sb.append("接口描述符: $desc\n")
            } catch (e: Throwable) { sb.append("描述符异常: ${e.message}\n") }
            for (cls in listOf("android.os.INetworkManagementService\$Stub", "android.net.INetd\$Stub")) {
                try {
                    val c = Class.forName(cls)
                    sb.append("=== $cls ===\n")
                    c.declaredMethods.forEach { m -> sb.append("  " + m.name + "(" + m.parameterTypes.joinToString(",") { p -> p.simpleName } + ")\n") }
                } catch (e: Throwable) { sb.append("$cls: ${e.javaClass.simpleName}\n") }
            }
        } catch (e: Throwable) {
            sb.append("异常: ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    fun dumpAndTestNetworkManagement(): String {
        val sb = StringBuilder()
        try {
            val binder = SystemServiceHelper.getSystemService("network_management") ?: return "❌ null"
            val wrapped = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.os.INetworkManagementService\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val nm = asInterface.invoke(null, wrapped)
            sb.append("✅ INetworkManagementService 接口拿到\n")
            val iface = Class.forName("android.os.INetworkManagementService")
            sb.append("=== INetworkManagementService 方法 ===\n")
            iface.declaredMethods.forEach { m -> sb.append("  " + m.name + "(" + m.parameterTypes.joinToString(",") { p -> p.simpleName } + ")\n") }
            try {
                val setUp = nm.javaClass.getMethod("setInterfaceUp", String::class.java)
                setUp.invoke(nm, "usb0")
                sb.append("✅ setInterfaceUp(usb0) 调用成功\n")
            } catch (e: Throwable) { sb.append("setInterfaceUp 异常: ${(e.cause ?: e).message}\n") }
        } catch (e: Throwable) {
            sb.append("异常: ${e.javaClass.simpleName}: ${e.message} | cause=${e.cause?.message}\n")
        }
        return sb.toString()
    }

    fun configureUsb0Static(): String {
        val sb = StringBuilder()
        try {
            val binder = SystemServiceHelper.getSystemService("network_management") ?: return "❌ null"
            val wrapped = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.os.INetworkManagementService\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val nm = asInterface.invoke(null, wrapped)
            val cfgClass = Class.forName("android.net.InterfaceConfiguration")
            sb.append("=== InterfaceConfiguration 字段 ===\n")
            for (f in cfgClass.declaredFields) sb.append("  " + f.name + " : " + f.type.simpleName + "\n")
            sb.append("=== InterfaceConfiguration 方法 ===\n")
            for (m in cfgClass.declaredMethods) sb.append("  " + m.name + "(" + m.parameterTypes.joinToString(",") { p -> p.simpleName } + ")\n")
            val cfg = cfgClass.getConstructor().newInstance()
            val laClass = Class.forName("android.net.LinkAddress")
            val inet = java.net.InetAddress.getByName("192.168.225.2")
            val laCtor = laClass.getDeclaredConstructor(java.net.InetAddress::class.java, Int::class.javaPrimitiveType)
            laCtor.isAccessible = true
            val la = laCtor.newInstance(inet, 24)
            cfgClass.getMethod("setLinkAddress", laClass).invoke(cfg, la)
            sb.append("✅ setLinkAddress(192.168.225.2/24) 成功\n")
            cfgClass.getMethod("setInterfaceUp").invoke(cfg)
            sb.append("✅ setInterfaceUp() 成功\n")
            val setCfg = nm.javaClass.getMethod("setInterfaceConfig", String::class.java, cfgClass)
            setCfg.invoke(nm, "usb0", cfg)
            sb.append("✅ setInterfaceConfig(usb0) 调用成功\n")
            try {
                val getCfg = nm.javaClass.getMethod("getInterfaceConfig", String::class.java)
                val r = getCfg.invoke(nm, "usb0")
                sb.append("读回: $r\n")
            } catch (e: Throwable) { sb.append("读回异常: ${(e.cause ?: e).message}\n") }
        } catch (e: Throwable) {
            sb.append("❌ ${e.javaClass.simpleName}: ${(e.cause ?: e).message}\n")
        }
        return sb.toString()
    }

    fun shizukuDiagnostic(): String {
        val sb = StringBuilder()
        try {
            val ping = Shizuku.pingBinder()
            sb.append("pingBinder     = $ping\n")
            if (ping) {
                sb.append("binder         = 已取得\n")
                sb.append("服务端版本      = ${Shizuku.getVersion()}\n")
                sb.append("本app权限状态   = ${Shizuku.checkSelfPermission()} (${if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) "已授权" else "未授权"})\n")
                sb.append("SELinux        = ${Shizuku.getSELinuxContext()}\n")
            } else {
                sb.append("binder         = 未取得（Shizuku 服务未运行 / 未连接）\n")
            }
        } catch (e: Throwable) {
            sb.append("诊断异常: ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    fun testGateway(): String {
        val sb = StringBuilder()
        for (gw in listOf("192.168.225.1", "192.168.0.1", "192.168.7.1")) {
            try {
                val s = java.net.Socket()
                s.connect(java.net.InetSocketAddress(gw, 80), 2000)
                sb.append("$gw:80 ✅ 可达\n")
                s.close()
            } catch (e: Throwable) {
                sb.append("$gw:80 ❌ ${e.javaClass.simpleName}: ${e.message}\n")
            }
        }
        return sb.toString()
    }

    fun dumpConnectivityRouteMethods(): String {
        val sb = StringBuilder()
        try {
            val cls = Class.forName("android.net.IConnectivityManager")
            sb.append("=== IConnectivityManager 路由/网络相关方法 ===\n")
            for (m in cls.declaredMethods) {
                val n = m.name.lowercase()
                if (n.contains("route") || n.contains("default") || n.contains("linkprop") || n.contains("interface") || n.contains("register")) {
                    sb.append("  " + m.name + "(" + m.parameterTypes.joinToString(",") { p -> p.simpleName } + ")\n")
                }
            }
        } catch (e: Throwable) {
            sb.append("异常: ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    fun dumpAllConnectivityMethods(): String {
        val sb = StringBuilder()
        try {
            val cls = Class.forName("android.net.IConnectivityManager")
            val names = cls.declaredMethods.map { it.name }.toSortedSet()
            sb.append("IConnectivityManager 方法数=${names.size}\n")
            sb.append(names.joinToString(", "))
            sb.append("\n\n父接口:\n")
            for (i in cls.interfaces) sb.append("  " + i.name + "\n")
            val sup = cls.superclass
            if (sup != null) sb.append("父类: " + sup.name + "\n")
            try {
                val na = Class.forName("android.net.NetworkAgent")
                sb.append("\nNetworkAgent 类存在 ✅ 父类=" + na.superclass?.name + "\n")
            } catch (e: Throwable) { sb.append("\nNetworkAgent 类: ${e.javaClass.simpleName}\n") }
        } catch (e: Throwable) {
            sb.append("异常: ${e.javaClass.simpleName}: ${e.message}\n")
        }
        return sb.toString()
    }

    fun runShell(cmd: String): String {
        return try {
            // Shizuku 13.1.5 hides newProcess as private static; invoke via reflection.
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            out.trim()
        } catch (e: Throwable) {
            "ERR: ${e.message}"
        }
    }

    /** Prefer rndis > usb > eth (eth0 may be a real NIC on tablets). */
    fun findUsbEthernetIface(): String? {
        val out = runShell("ip -o link show")
        Regex("(rndis\\d+)").find(out)?.value?.let { return it }
        Regex("(usb\\d+)").find(out)?.value?.let { return it }
        return Regex("(eth\\d+)").find(out)?.value
    }

    fun ifaceState(iface: String): String {
        return runShell("ip -o addr show dev $iface; echo ---; ip route show dev $iface")
    }

    private fun getEthernetManagerBinder(): Any? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "ethernet") as IBinder
            val wrapped = ShizukuBinderWrapper(binder)
            val stub = Class.forName("android.net.IEthernetManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, wrapped)
        } catch (e: Throwable) {
            null
        }
    }

    fun tryEthernetManager(): String {
        val sb = StringBuilder()
        val iEth = getEthernetManagerBinder()
        if (iEth == null) {
            sb.append("ERR: 无法获取 EthernetManager binder（可能被 ROM 限制）\n")
            return sb.toString()
        }
        val cls = iEth.javaClass
        try {
            cls.getMethod("setEthernetEnabled", Boolean::class.javaPrimitiveType).invoke(iEth, true)
            sb.append("setEthernetEnabled(true): OK\n")
        } catch (e: Exception) {
            sb.append("setEthernetEnabled: ${(e.cause ?: e).message}\n")
        }
        return sb.toString()
    }

    fun setInterfaceUp(iface: String): String {
        val sb = StringBuilder()
        val iEth = getEthernetManagerBinder()
        if (iEth == null) return "ERR: 无 EthernetManager binder\n"
        val cls = iEth.javaClass
        try {
            cls.getMethod("setInterfaceUp", String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(iEth, iface, true)
            sb.append("setInterfaceUp($iface,true): OK\n")
        } catch (e: Exception) {
            sb.append("setInterfaceUp: ${(e.cause ?: e).message}\n")
        }
        return sb.toString()
    }

    /** Manual static config fallback (module NAT gateway = 192.168.225.1). */
    fun configureManual(iface: String): String {
        val cmds = listOf(
            "ip link set $iface up",
            "ip addr flush dev $iface 2>/dev/null || true",
            "ip addr add 192.168.225.2/24 dev $iface 2>/dev/null || true",
            "ip route replace default via 192.168.225.1 dev $iface 2>/dev/null || true",
            "ndc resolver setnetdns $iface '' 223.5.5.5 8.8.8.8 2>/dev/null || true"
        )
        return cmds.joinToString("\n") { "$it\n  => ${runShell(it)}" }
    }
}
