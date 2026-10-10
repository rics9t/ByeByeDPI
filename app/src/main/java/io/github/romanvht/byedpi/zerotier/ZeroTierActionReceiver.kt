package io.github.romanvht.byedpi.zerotier

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ZeroTierActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_ZT = "io.github.romanvht.byedpi.ZT_ACTION"
        const val EXTRA_OP = "op"
        const val EXTRA_NWID = "nwid"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ZT) return
        val op = intent.getStringExtra(EXTRA_OP) ?: return
        val nwid = intent.getStringExtra(EXTRA_NWID) ?: ""
        val manager = ZeroTierServiceManager.getInstance(context)

        Log.i("ZeroTierReceiver", "Received ZT action: op=$op, nwid=$nwid")
        when (op) {
            "join" -> if (nwid.isNotBlank()) manager.join(nwid)
            "leave" -> if (nwid.isNotBlank()) manager.leave(nwid)
            "sync" -> manager.syncNetworks()
        }
    }
}