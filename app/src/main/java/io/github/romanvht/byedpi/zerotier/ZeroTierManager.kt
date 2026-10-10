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
            // Bind to default ZT port, or ephemeral if in use
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
                try { node?.join(it.toLong(16)) } catch (e: Exception) {}
            }

            // ZeroTier Background Task Loop
            thread(name = "ZeroTier-Background") {
                while (node?.isInited == true) {
                    val deadline = LongArray(1)
                    node?.processBackgroundTasks(System.currentTimeMillis(), deadline)
                    val sleepTime = (deadline[0] - System.currentTimeMillis()).coerceIn(50, 1000)
                    if (sleepTime > 0) Thread.sleep(sleepTime)
                }
            }

            // ZeroTier UDP Packet Receive Loop
            thread(name = "ZeroTier-UDP") {
                val buf = ByteArray(65536)
                val packet = DatagramPacket(buf, buf.size)
                while (node?.isInited == true) {
                    try {
                        socket?.receive(packet)
                        val data = packet.data.copyOf(packet.length)
                        val deadline = LongArray(1)
                        val addr = packet.socketAddress as InetSocketAddress
                        node?.processWirePacket(System.currentTimeMillis(), -1, addr, data, deadline)
                    } catch (e: Exception) {
                        // Ignore read timeouts/socket closures
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
            try { node?.join(nwid.toLong(16)) } catch (e: Exception) {}
        } else {
            enabled.remove(nwid)
            try { node?.leave(nwid.toLong(16)) } catch (e: Exception) {}
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

    private val getListener = DataStoreGetListener { name, out_buffer ->
        val file = File(dataStoreDir, name)
        if (!file.exists()) return@DataStoreGetListener -1
        val data = file.readBytes()
        val len = Math.min(data.size, out_buffer.size)
        System.arraycopy(data, 0, out_buffer, 0, len)
        len.toLong()
    }

    private val putListener = object : DataStorePutListener {
        override fun onDataStorePut(name: String, buffer: ByteArray, secure: Boolean): Int {
            File(dataStoreDir, name).writeBytes(buffer)
            return 0
        }
        override fun onDelete(name: String): Int {
            File(dataStoreDir, name).delete()
            return 0
        }
    }

    private val packetSender = PacketSender { _, remoteAddr, packetData, _ ->
        try {
            socket?.send(DatagramPacket(packetData, packetData.size, remoteAddr))
            0
        } catch (e: Exception) { -1 }
    }

    // Tells ZeroTier it's allowed to send packets to the designated IPs
    private val pathChecker = object : PathChecker {
        override fun onPathCheck(ztAddress: Long, localSocket: Long, remoteAddress: InetSocketAddress?): Boolean = true
        override fun onPathLookup(ztAddress: Long, ss_family: Int): InetSocketAddress? = null
    }

    private val eventListener = object : EventListener {
        override fun onEvent(event: Event?) { Log.i("ZeroTier", "Event: $event") }
        override fun onTrace(message: String?) { Log.d("ZeroTier", "Trace: $message") }
    }

    private val frameListener = VirtualNetworkFrameListener { _, _, _, _, _, _ -> }

    private val configListener = VirtualNetworkConfigListener { op, _, config ->
        Log.i("ZeroTier", "Config updated: $op, $config")
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