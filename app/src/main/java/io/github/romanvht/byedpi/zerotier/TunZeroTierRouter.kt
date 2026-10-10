package io.github.romanvht.byedpi.zerotier

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class TunZeroTierRouter(
    private val realTunPfd: ParcelFileDescriptor,
    private val ztManager: ZeroTierManager
) {
    companion object {
        private const val TAG = "TunZeroTierRouter"
        private const val BUFFER_SIZE = 32768
    }

    private val realTunFd: FileDescriptor = realTunPfd.fileDescriptor
    private val sv = Array(2) { FileDescriptor() }
    private var hevPfd: ParcelFileDescriptor? = null
    private val tunWriteLock = Any()
    private val hevWriteLock = Any()

    @Volatile
    private var isRunning = false

    private val arpTable = ConcurrentHashMap<Int, Long>()

    init {
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, sv[0], sv[1])
        try {
            Os.setsockoptInt(sv[0], OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 1048576)
            Os.setsockoptInt(sv[0], OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1048576)
            Os.setsockoptInt(sv[1], OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 1048576)
            Os.setsockoptInt(sv[1], OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1048576)
        } catch (_: Exception) {}

        hevPfd = ParcelFileDescriptor.dup(sv[1])
        try { Os.close(sv[1]) } catch (_: Exception) {}

        ztManager.router = this
    }

    fun getHevTunnelFd(): Int = hevPfd!!.fd

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "TunRouter-Outbound") {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isRunning) {
                try {
                    val bytesRead = Os.read(realTunFd, buffer, 0, buffer.size)
                    if (bytesRead <= 0) break

                    val isIpv4 = ((buffer[0].toInt() shr 4) and 0x0F) == 4
                    if (isIpv4 && bytesRead >= 20) {
                        val destIp = ((buffer[16].toInt() and 0xFF) shl 24) or
                                ((buffer[17].toInt() and 0xFF) shl 16) or
                                ((buffer[18].toInt() and 0xFF) shl 8) or
                                (buffer[19].toInt() and 0xFF)

                        val ztRoute = ztManager.findRouteForIp(destIp)
                        if (ztRoute != null) {
                            val packet = buffer.copyOf(bytesRead)
                            sendToZeroTier(ztRoute.nwid, destIp, packet)
                            continue
                        }
                    }

                    synchronized(hevWriteLock) {
                        try {
                            Os.write(sv[0], buffer, 0, bytesRead)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }

        thread(name = "TunRouter-InboundHev") {
            val buffer = ByteArray(BUFFER_SIZE)
            while (isRunning) {
                try {
                    val bytesRead = Os.read(sv[0], buffer, 0, buffer.size)
                    if (bytesRead <= 0) break

                    synchronized(tunWriteLock) {
                        try {
                            Os.write(realTunFd, buffer, 0, bytesRead)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    if (!isRunning) break
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        ztManager.router = null
        try { Os.close(sv[0]) } catch (_: Exception) {}
        try { hevPfd?.close() } catch (_: Exception) {}
    }

    fun writeToTun(data: ByteArray) {
        if (!isRunning) return
        synchronized(tunWriteLock) {
            try {
                Os.write(realTunFd, data, 0, data.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write ZeroTier packet to TUN", e)
            }
        }
    }

    private fun sendToZeroTier(nwid: Long, destIp: Int, packet: ByteArray) {
        val config = ztManager.getConfigs().firstOrNull { it.nwid == nwid } ?: return
        val ourMac = config.mac
        var destMac = arpTable[destIp]

        if (destMac == null) {
            sendArpRequest(nwid, ourMac, destIp)
            destMac = 0xFFFFFFFFFFFFL
        }

        val deadline = LongArray(1)
        ztManager.node?.processVirtualNetworkFrame(
            System.currentTimeMillis(),
            nwid,
            ourMac,
            destMac,
            0x0800,
            0,
            packet,
            deadline
        )
    }

    fun handleArpFrame(nwid: Long, frameData: ByteArray) {
        if (frameData.size < 28) return
        val opcode = ((frameData[6].toInt() and 0xFF) shl 8) or (frameData[7].toInt() and 0xFF)
        val senderMac = macBytesToLong(frameData, 8)
        val senderIp = bytesToIp(frameData, 14)
        val targetIp = bytesToIp(frameData, 24)

        arpTable[senderIp] = senderMac

        if (opcode == 1) {
            val config = ztManager.getConfigs().firstOrNull { it.nwid == nwid } ?: return
            val ourAssigned = ztManager.getAssignedIpInts(nwid)

            if (ourAssigned.contains(targetIp)) {
                val reply = ByteArray(28)
                reply[0] = 0; reply[1] = 1; reply[2] = 8; reply[3] = 0
                reply[4] = 6; reply[5] = 4
                reply[6] = 0; reply[7] = 2

                longToMacBytes(config.mac, reply, 8)
                ipToBytes(targetIp, reply, 14)
                longToMacBytes(senderMac, reply, 18)
                ipToBytes(senderIp, reply, 24)

                val deadline = LongArray(1)
                ztManager.node?.processVirtualNetworkFrame(
                    System.currentTimeMillis(),
                    nwid,
                    config.mac,
                    senderMac,
                    0x0806,
                    0,
                    reply,
                    deadline
                )
            }
        }
    }

    private fun sendArpRequest(nwid: Long, ourMac: Long, targetIp: Int) {
        val ourIp = ztManager.getAssignedIpInts(nwid).firstOrNull() ?: return
        val req = ByteArray(28)
        req[0] = 0; req[1] = 1; req[2] = 8; req[3] = 0
        req[4] = 6; req[5] = 4
        req[6] = 0; req[7] = 1

        longToMacBytes(ourMac, req, 8)
        ipToBytes(ourIp, req, 14)
        ipToBytes(targetIp, req, 24)

        val deadline = LongArray(1)
        ztManager.node?.processVirtualNetworkFrame(
            System.currentTimeMillis(),
            nwid,
            ourMac,
            0xFFFFFFFFFFFFL,
            0x0806,
            0,
            req,
            deadline
        )
    }

    private fun macBytesToLong(bytes: ByteArray, offset: Int): Long {
        var mac = 0L
        for (i in 0 until 6) {
            mac = (mac shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return mac
    }

    private fun longToMacBytes(mac: Long, bytes: ByteArray, offset: Int) {
        for (i in 5 downTo 0) {
            bytes[offset + i] = ((mac shr ((5 - i) * 8)) and 0xFF).toByte()
        }
    }

    private fun bytesToIp(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun ipToBytes(ip: Int, bytes: ByteArray, offset: Int) {
        bytes[offset] = ((ip ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((ip ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((ip ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (ip and 0xFF).toByte()
    }
}