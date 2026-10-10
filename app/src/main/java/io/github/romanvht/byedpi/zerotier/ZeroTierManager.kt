package io.github.romanvht.byedpi.zerotier

import android.content.Context
import android.net.VpnService
import android.util.Log
import com.google.gson.Gson
import com.zerotier.sdk.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class ZeroTierManager(private val context: Context) {
    var node: Node? = null
    var router: TunZeroTierRouter? = null
    private var vpnService: VpnService? = null

    private val dataStoreDir = File(context.filesDir, "zerotier").apply { mkdirs() }
    private val stateFile = File(context.filesDir, "zerotier_state.json")
    private var socket: DatagramSocket? = null
    private val prefs = context.getSharedPreferences("zerotier_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Volatile private var isOnline = false
    @Volatile private var cachedAddress: Long? = null

    data class ZtRoute(val nwid: Long, val net: Int, val mask: Int)
    private class NetInfo(val mac: Long, val ips: List<Int>, val routes: List<ZtRoute>)

    private val nets = ConcurrentHashMap<Long, NetInfo>()
    @Volatile
    private var routeCache: List<ZtRoute> = emptyList()

    fun findRouteForIp(ip: Int): ZtRoute? =
        routeCache.firstOrNull { (ip and it.mask) == it.net }

    fun getMac(nwid: Long): Long? = nets[nwid]?.mac

    fun getAssignedIpInts(nwid: Long): List<Int> = nets[nwid]?.ips ?: emptyList()

    private fun v4(addr: InetAddress?): Int? =
        (addr as? Inet4Address)?.address?.let { ByteBuffer.wrap(it).int }

    private fun updateCache(nwid: Long, op: VirtualNetworkConfigOperation, cfg: VirtualNetworkConfig) {
        if (op == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DOWN ||
            op == VirtualNetworkConfigOperation.VIRTUAL_NETWORK_CONFIG_OPERATION_DESTROY
        ) {
            nets.remove(nwid)
        } else {
            val routes = cfg.routes?.mapNotNull { r ->
                val t = r.target ?: return@mapNotNull null
                val ip = v4(t.address) ?: return@mapNotNull null
                val p = t.port
                if (p !in 1..32) return@mapNotNull null
                val mask = if (p == 32) -1 else (-1 shl (32 - p))
                ZtRoute(nwid, ip and mask, mask)
            } ?: emptyList()

            val ips = cfg.assignedAddresses?.mapNotNull { v4(it.address) } ?: emptyList()
            nets[nwid] = NetInfo(cfg.mac, ips, routes)
        }
        routeCache = nets.values.flatMap { it.routes }
    }

    var savedNetworks: Set<String>
        get() = prefs.getStringSet("saved_networks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("saved_networks", value).apply()

    var enabledNetworks: Set<String>
        get() = prefs.getStringSet("enabled_networks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("enabled_networks", value).apply()

    fun isAvailable(): Boolean = node != null && (node?.isInited == true)

    fun bindVpnService(service: VpnService?) {
        this.vpnService = service
        socket?.let { s ->
            try {
                service?.protect(s)
                Log.i("ZeroTier", "Protected ZeroTier UDP socket directly on physical network")
            } catch (e: Exception) {
                Log.w("ZeroTier", "Failed to protect socket", e)
            }
        }
    }

    fun init(service: VpnService? = null) {
        if (node != null) return
        this.vpnService = service
        try {
            try {
                socket = DatagramSocket(9993)
            } catch (_: Exception) {
                socket = DatagramSocket(0)
            }

            // Immediately protect socket so ZeroTier bypasses ByeDPI/VPN and talks directly to Wi-Fi/LTE
            socket?.let { s -> vpnService?.protect(s) }

            val now = System.currentTimeMillis()
            val n = Node(now)
            node = n

            // Pass null for pathChecker to eliminate JNI reflection errors
            val res = n.init(
                getListener,
                putListener,
                packetSender,
                eventListener,
                frameListener,
                configListener,
                null
            )

            if (res != ResultCode.RESULT_OK) {
                Log.e("ZeroTier", "Node init failed with code: $res")
                return
            }

            try {
                cachedAddress = n.address()
            } catch (_: Exception) {}

            syncNetworks()
            saveState()

            thread(name = "ZeroTier-Background") {
                while (node?.isInited == true) {
                    try {
                        val deadline = LongArray(1)
                        node?.processBackgroundTasks(System.currentTimeMillis(), deadline)
                        saveState()
                        val sleepTime = (deadline[0] - System.currentTimeMillis()).coerceIn(50, 1000)
                        if (sleepTime > 0) Thread.sleep(sleepTime)
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        try { Thread.sleep(500) } catch (_: InterruptedException) { break }
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
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Exception) {
                        try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("ZeroTier", "Failed to init ZeroTier", e)
        }
    }

    fun join(networkId: String) {
        try {
            val nwidLong = java.math.BigInteger(networkId, 16).toLong()
            node?.join(nwidLong)
            Log.i("ZeroTier", "Joined network $networkId ($nwidLong)")
        } catch (e: Exception) {
            Log.e("ZeroTier", "Failed to join network $networkId", e)
        }
    }

    fun leave(networkId: String) {
        try {
            val nwidLong = java.math.BigInteger(networkId, 16).toLong()
            node?.leave(nwidLong)
            Log.i("ZeroTier", "Left network $networkId")
        } catch (e: Exception) {
            Log.e("ZeroTier", "Failed to leave network $networkId", e)
        }
    }

    fun syncNetworks() {
        enabledNetworks.forEach { join(it) }
    }

    fun getConfigs(): List<VirtualNetworkConfig> {
        return try {
            if (node?.isInited == true) node?.networkConfigs()?.toList() ?: emptyList()
            else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class ExportedNetwork(
        val nwid: String,
        val name: String,
        val status: String,
        val type: String,
        val mac: String,
        val mtu: Int,
        val broadcast: Boolean,
        val bridge: Boolean,
        val ips: List<String>
    )

    data class SharedState(
        val nodeAddress: String,
        val isOnline: Boolean,
        val networks: List<ExportedNetwork>
    )

    @Synchronized
    fun saveState() {
        val n = node ?: return
        if (!n.isInited) return
        try {
            if (cachedAddress == null) {
                try { cachedAddress = n.address() } catch (_: Exception) {}
            }
            val addr = cachedAddress?.let { String.format("%010x", it) } ?: "--------"
            val online = isOnline || (try { n.status()?.isOnline == true } catch (_: Exception) { false })
            val exported = getConfigs().map { cfg ->
                ExportedNetwork(
                    nwid = String.format("%016x", cfg.nwid),
                    name = cfg.name ?: "-",
                    status = cfg.status?.name?.replace("NETWORK_STATUS_", "") ?: "NOT CONNECTED",
                    type = cfg.type?.name?.replace("NETWORK_TYPE_", "") ?: "-",
                    mac = String.format("%012x", cfg.mac),
                    mtu = cfg.mtu,
                    broadcast = cfg.isBroadcastEnabled,
                    bridge = cfg.isBridge,
                    ips = cfg.assignedAddresses?.mapNotNull { it.address?.hostAddress } ?: emptyList()
                )
            }
            val json = gson.toJson(SharedState(addr, online, exported))
            stateFile.writeText(json)
        } catch (e: Exception) {
            Log.w("ZeroTier", "Could not write state", e)
        }
    }

    private val getListener = DataStoreGetListener { name, out_buffer ->
        try {
            val file = File(dataStoreDir, name)
            if (!file.exists() || !file.isFile) return@DataStoreGetListener -1
            val data = file.readBytes()
            val len = Math.min(data.size, out_buffer.size)
            System.arraycopy(data, 0, out_buffer, 0, len)
            len.toLong()
        } catch (_: Exception) {
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
            } catch (_: Exception) {
                -1
            }
        }
    }

    private val packetSender = PacketSender { _, remoteAddr, packetData, _ ->
        try {
            socket?.send(DatagramPacket(packetData, packetData.size, remoteAddr))
            0
        } catch (_: Exception) { -1 }
    }

    private val eventListener = object : EventListener {
        override fun onEvent(event: Event?) {
            Log.i("ZeroTier", "Event: $event")
            when (event) {
                Event.EVENT_UP, Event.EVENT_ONLINE -> isOnline = true
                Event.EVENT_OFFLINE, Event.EVENT_DOWN -> isOnline = false
                else -> {}
            }
        }
        override fun onTrace(message: String?) {
            Log.d("ZeroTier", "Trace: $message")
        }
    }

    private val frameListener = VirtualNetworkFrameListener { nwid, _, _, etherType, _, frameData ->
        if (etherType == 0x0806L) {
            router?.handleArpFrame(nwid, frameData)
        } else if (etherType == 0x0800L) {
            router?.writeToTun(frameData)
        }
    }

    private val configListener = VirtualNetworkConfigListener { nwid, op, config ->
        Log.i("ZeroTier", "Config updated for $nwid: $op, status=${config.status}")
        updateCache(nwid, op, config)

        if (config.assignedAddresses != null) {
            val assignedSet = mutableSetOf<String>()
            for (addr in config.assignedAddresses) {
                val host = addr.address?.hostAddress ?: continue
                val prefix = addr.port
                assignedSet.add("$host/$prefix")

                val ipInt = v4(addr.address) ?: continue
                val ipLong = ipInt.toLong() and 0xFFFFFFFFL
                node?.multicastSubscribe(nwid, 0xFFFFFFFFFFFFL, ipLong)
            }
            prefs.edit().putStringSet("assigned_ips", assignedSet).apply()
        }
        0
    }
}

object ZeroTierServiceManager {
    private var manager: ZeroTierManager? = null

    @Synchronized
    fun getInstance(context: Context): ZeroTierManager {
        if (manager == null) {
            manager = ZeroTierManager(context.applicationContext)
        }
        return manager!!
    }
}