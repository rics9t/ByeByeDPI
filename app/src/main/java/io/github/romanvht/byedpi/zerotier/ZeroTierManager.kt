package io.github.romanvht.byedpi.zerotier

import android.content.Context
import android.util.Log
import com.zerotier.sdk.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class ZeroTierManager(private val context: Context) {
    var node: Node? = null
    var router: TunZeroTierRouter? = null

    private val dataStoreDir = File(context.filesDir, "zerotier").apply { mkdirs() }
    private var socket: DatagramSocket? = null
    private val prefs = context.getSharedPreferences("zerotier_prefs", Context.MODE_PRIVATE)

    var savedNetworks: Set<String>
        get() = prefs.getStringSet("saved_networks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("saved_networks", value).apply()

    var enabledNetworks: Set<String>
        get() = prefs.getStringSet("enabled_networks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("enabled_networks", value).apply()

    fun init() {
        if (node != null) return
        try {
            try {
                socket = DatagramSocket(9993)
            } catch (e: Exception) {
                socket = DatagramSocket(0)
            }

            node = Node(System.currentTimeMillis())
            node?.init(
                getListener,
                putListener,
                packetSender,
                eventListener,
                frameListener,
                configListener,
                pathChecker
            )

            // Join networks that are toggled "ON"
            enabledNetworks.forEach {
                try { node?.join(java.math.BigInteger(it, 16).toLong()) } catch (e: Exception) {}
            }

            thread(name = "ZeroTier-Background") {
                while (node?.isInited == true) {
                    try {
                        val deadline = LongArray(1)
                        node?.processBackgroundTasks(System.currentTimeMillis(), deadline)
                        val sleepTime = (deadline[0] - System.currentTimeMillis()).coerceIn(50, 1000)
                        if (sleepTime > 0) Thread.sleep(sleepTime)
                    } catch (e: Exception) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) {}
                    }
                }
            }

            thread(name = "ZeroTier-UDP") {
                val buf = ByteArray(65536)
                val packet = DatagramPacket(buf, buf.size)
                while (node?.isInited == true) {
                    try {
                        val s = socket
                        if (s == null || s.isClosed) {
                            Thread.sleep(1000)
                            continue
                        }
                        s.receive(packet)
                        val data = packet.data.copyOf(packet.length)
                        val deadline = LongArray(1)
                        val addr = packet.socketAddress as InetSocketAddress
                        node?.processWirePacket(System.currentTimeMillis(), -1, addr, data, deadline)
                    } catch (e: Exception) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) {}
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ZeroTier", "Failed to init ZeroTier", e)
        }
    }

    fun toggleNetwork(nwid: String, enable: Boolean) {
        val enabled = enabledNetworks.toMutableSet()
        if (enable) {
            enabled.add(nwid)
            try { node?.join(java.math.BigInteger(nwid, 16).toLong()) } catch (e: Exception) {}
        } else {
            enabled.remove(nwid)
            try { node?.leave(java.math.BigInteger(nwid, 16).toLong()) } catch (e: Exception) {}
        }
        enabledNetworks = enabled
    }

    fun addNetwork(nwid: String) {
        val saved = savedNetworks.toMutableSet()
        saved.add(nwid)
        savedNetworks = saved
        toggleNetwork(nwid, true)
    }

    fun removeNetwork(nwid: String) {
        toggleNetwork(nwid, false)
        val saved = savedNetworks.toMutableSet()
        saved.remove(nwid)
        savedNetworks = saved
    }

    fun getConfigs(): List<VirtualNetworkConfig> {
        return try {
            node?.networkConfigs()?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class ZtRoute(val nwid: Long, val targetIp: Int, val prefix: Int)

    /**
     * Finds if a target IP matches any active ZeroTier network routes
     */
    fun findRouteForIp(ip: Int): ZtRoute? {
        for (cfg in getConfigs()) {
            for (route in cfg.routes) {
                if (route.target != null) {
                    val addr = route.target.address ?: continue
                    val targetIpInt = ipStringToInt(addr.hostAddress)
                    val prefix = route.target.port
                    if (isInSubnet(ip, targetIpInt, prefix)) {
                        return ZtRoute(cfg.nwid, targetIpInt, prefix)
                    }
                }
            }
        }
        return null
    }

    fun getAssignedIpInts(nwid: Long): List<Int> {
        val cfg = getConfigs().firstOrNull { it.nwid == nwid } ?: return emptyList()
        return cfg.assignedAddresses.mapNotNull {
            val addr = it.address ?: return@mapNotNull null
            ipStringToInt(addr.hostAddress)
        }
    }

    private fun isInSubnet(ip: Int, net: Int, prefix: Int): Boolean {
        if (prefix <= 0) return false
        val mask = -1 shl (32 - prefix)
        return (ip and mask) == (net and mask)
    }

    private fun ipStringToInt(ipStr: String): Int {
        val parts = ipStr.split(".")
        if (parts.size != 4) return 0
        return ((parts[0].toInt() and 0xFF) shl 24) or
                ((parts[1].toInt() and 0xFF) shl 16) or
                ((parts[2].toInt() and 0xFF) shl 8) or
                (parts[3].toInt() and 0xFF)
    }

    private val getListener = DataStoreGetListener { name, out_buffer ->
        try {
            val file = File(dataStoreDir, name)
            if (!file.exists() || !file.isFile) return@DataStoreGetListener -1
            val data = file.readBytes()
            val len = Math.min(data.size, out_buffer.size)
            System.arraycopy(data, 0, out_buffer, 0, len)
            len.toLong()
        } catch (e: Exception) {
            -2
        }
    }

    private val putListener = object : DataStorePutListener {
        override fun onDataStorePut(name: String, buffer: ByteArray, secure: Boolean): Int {
            return try {
                val file = File(dataStoreDir, name)
                file.parentFile?.mkdirs()
                file.writeBytes(buffer)
                0
            } catch (e: Exception) {
                Log.e("ZeroTier", "onDataStorePut error for $name", e)
                -1
            }
        }

        override fun onDelete(name: String): Int {
            return try {
                val file = File(dataStoreDir, name)
                if (file.exists()) file.delete()
                0
            } catch (e: Exception) {
                -1
            }
        }
    }

    private val packetSender = PacketSender { _, remoteAddr, packetData, _ ->
        try {
            socket?.send(DatagramPacket(packetData, packetData.size, remoteAddr))
            0
        } catch (e: Exception) { -1 }
    }

    private val pathChecker = object : PathChecker {
        override fun onPathCheck(ztAddress: Long, localSocket: Long, remoteAddress: InetSocketAddress?): Boolean = true
        override fun onPathLookup(ztAddress: Long, ss_family: Int): InetSocketAddress? = null
    }

    private val eventListener = object : EventListener {
        override fun onEvent(event: Event?) { Log.i("ZeroTier", "Event: $event") }
        override fun onTrace(message: String?) { Log.d("ZeroTier", "Trace: $message") }
    }

    // INCOMING FRAME ROUTER: Dispatches frames from peers into Android TUN
    private val frameListener = VirtualNetworkFrameListener { nwid, _, _, etherType, _, frameData ->
        if (etherType == 0x0806L) {
            router?.handleArpFrame(nwid, frameData)
        } else if (etherType == 0x0800L) {
            router?.writeToTun(frameData)
        }
    }

    private val configListener = VirtualNetworkConfigListener { nwid, op, config ->
        Log.i("ZeroTier", "Config updated for $nwid: $op, status=${config.status}")

        // Multicast subscribe so peers can discover us via ARP
        if (config.assignedAddresses != null) {
            val assignedSet = mutableSetOf<String>()
            for (addr in config.assignedAddresses) {
                val host = addr.address?.hostAddress ?: continue
                val prefix = addr.port
                assignedSet.add("$host/$prefix")

                // Subscribe to 0xFFFFFFFFFFFFL with the IP as ADI for ARP
                val ipLong = (ipStringToInt(host).toLong()) and 0xFFFFFFFFL
                node?.multicastSubscribe(nwid, 0xFFFFFFFFFFFFL, ipLong)
            }
            prefs.edit().putStringSet("assigned_ips", assignedSet).apply()
        }
        0
    }
}

object ZeroTierServiceManager {
    var manager: ZeroTierManager? = null
    fun getInstance(context: Context): ZeroTierManager {
        if (manager == null) {
            manager = ZeroTierManager(context.applicationContext)
            manager?.init()
        }
        return manager!!
    }
}