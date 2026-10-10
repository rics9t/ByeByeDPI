package io.github.romanvht.byedpi.zerotier

import android.content.Context
import android.util.Log
import com.zerotier.sdk.*
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket

class ZeroTierManager(private val context: Context) {
    private var node: Node? = null
    private val dataStoreDir = File(context.filesDir, "zerotier").apply { mkdirs() }
    private val socket = DatagramSocket()
    private val prefs = context.getSharedPreferences("zerotier_prefs", Context.MODE_PRIVATE)

    var networks: Set<String>
        get() = prefs.getStringSet("networks", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("networks", value).apply()

    fun init() {
        if (node != null) return
        try {
            node = Node(System.currentTimeMillis())
            node?.init(
                getListener,
                putListener,
                packetSender,
                eventListener,
                frameListener,
                configListener,
                null
            )
            
            networks.forEach {
                try {
                    node?.join(it.toLong(16))
                } catch (e: Exception) {}
            }

            Thread {
                while (node?.isInited == true) {
                    val deadline = LongArray(1)
                    node?.processBackgroundTasks(System.currentTimeMillis(), deadline)
                    Thread.sleep(1000)
                }
            }.start()
        } catch (e: Throwable) {
            Log.e("ZeroTier", "Failed to init ZeroTier. Is libZeroTierOneJNI.so missing?", e)
        }
    }

    fun join(networkId: String) {
        val mut = networks.toMutableSet()
        mut.add(networkId)
        networks = mut
        try {
            node?.join(networkId.toLong(16))
        } catch (e: Exception) {}
    }

    fun leave(networkId: String) {
        val mut = networks.toMutableSet()
        mut.remove(networkId)
        networks = mut
        try {
            node?.leave(networkId.toLong(16))
        } catch (e: Exception) {}
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

    private val packetSender = PacketSender { localSocket, remoteAddr, packetData, ttl ->
        try {
            socket.send(DatagramPacket(packetData, packetData.length, remoteAddr))
            0
        } catch (e: Exception) { -1 }
    }

    private val eventListener = object : EventListener {
        override fun onEvent(event: Event?) { Log.i("ZeroTier", "Event: $event") }
        override fun onTrace(message: String?) { Log.d("ZeroTier", "Trace: $message") }
    }

    private val frameListener = VirtualNetworkFrameListener { nwid, srcMac, destMac, etherType, vlanId, frameData -> }

    private val configListener = VirtualNetworkConfigListener { nwid, op, config ->
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