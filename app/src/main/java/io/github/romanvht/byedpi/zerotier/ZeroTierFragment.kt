package io.github.romanvht.byedpi.zerotier

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.Gson
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import java.io.File

class ZeroTierFragment : Fragment() {

    private lateinit var adapter: ZeroTierAdapter
    private lateinit var nodeStatusText: TextView
    private val gson = Gson()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_zerotier, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().getSharedPreferences("zerotier_prefs", Context.MODE_PRIVATE)
        nodeStatusText = view.findViewById(R.id.nodeStatusText)

        fun getSaved(): Set<String> = prefs.getStringSet("saved_networks", emptySet()) ?: emptySet()
        fun getEnabled(): Set<String> = prefs.getStringSet("enabled_networks", emptySet()) ?: emptySet()

        fun sendZtBroadcast(op: String, nwid: String) {
            val intent = Intent(ZeroTierActionReceiver.ACTION_ZT).apply {
                setPackage(requireContext().packageName)
                putExtra(ZeroTierActionReceiver.EXTRA_OP, op)
                putExtra(ZeroTierActionReceiver.EXTRA_NWID, nwid)
            }
            requireContext().sendBroadcast(intent)
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        adapter = ZeroTierAdapter(
            getSaved().toMutableList(),
            getEnabled(),
            onToggle = { nwid, enable ->
                val set = getEnabled().toMutableSet()
                if (enable) set.add(nwid) else set.remove(nwid)
                prefs.edit().putStringSet("enabled_networks", set).apply()

                if (appStatus.first == AppStatus.Running) {
                    sendZtBroadcast(if (enable) "join" else "leave", nwid)
                } else if (enable) {
                    Toast.makeText(requireContext(), "Network enabled. Connect VPN to start ZeroTier.", Toast.LENGTH_SHORT).show()
                }
            },
            onClick = { nwid, _ ->
                val state = loadState()
                val net = state?.networks?.firstOrNull { it.nwid.equals(nwid, ignoreCase = true) }
                val details = """
                    Network ID: $nwid
                    Network Name: ${net?.name ?: "-"}
                    Status: ${net?.status ?: if (getEnabled().contains(nwid)) "REQUESTING_CONFIGURATION" else "DISABLED"}
                    Type: ${net?.type ?: "-"}
                    MAC: ${net?.mac ?: "-"}
                    MTU: ${net?.mtu ?: "-"}
                    Broadcast: ${net?.broadcast ?: "-"}
                    Bridging: ${net?.bridge ?: "-"}
                    Managed IPs: ${net?.ips?.joinToString(", ") ?: "-"}
                """.trimIndent()

                AlertDialog.Builder(requireContext())
                    .setTitle("Details for $nwid")
                    .setMessage(details)
                    .setPositiveButton("OK", null)
                    .show()
            },
            onDelete = { nwid ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete Network")
                    .setMessage("Forget configuration for network $nwid?")
                    .setPositiveButton("Yes") { _, _ ->
                        val saved = getSaved().toMutableSet()
                        saved.remove(nwid)
                        val enabled = getEnabled().toMutableSet()
                        enabled.remove(nwid)
                        prefs.edit()
                            .putStringSet("saved_networks", saved)
                            .putStringSet("enabled_networks", enabled)
                            .apply()
                        adapter.removeNetwork(nwid)
                        if (appStatus.first == AppStatus.Running) {
                            sendZtBroadcast("leave", nwid)
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            val input = EditText(requireContext())
            AlertDialog.Builder(requireContext())
                .setTitle("Add Network")
                .setMessage("Enter 16-character Network ID")
                .setView(input)
                .setPositiveButton("Add") { _, _ ->
                    val nwid = input.text.toString().trim().lowercase()
                    if (nwid.matches(Regex("[0-9a-f]{16}"))) {
                        val saved = getSaved().toMutableSet()
                        saved.add(nwid)
                        val enabled = getEnabled().toMutableSet()
                        enabled.add(nwid)
                        prefs.edit()
                            .putStringSet("saved_networks", saved)
                            .putStringSet("enabled_networks", enabled)
                            .apply()
                        adapter.addNetwork(nwid)

                        if (appStatus.first == AppStatus.Running) {
                            sendZtBroadcast("join", nwid)
                            Toast.makeText(requireContext(), "Joining network $nwid...", Toast.LENGTH_SHORT).show()
                        } else {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Start Connection?")
                                .setMessage("Connect VPN now so ZeroTier can send the join request to the controller?")
                                .setPositiveButton("Connect Now") { _, _ ->
                                    ServiceManager.start(requireContext(), Mode.VPN)
                                }
                                .setNegativeButton("Later", null)
                                .show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "Invalid 16-character Network ID", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        recyclerView.post(object : Runnable {
            override fun run() {
                if (!isAdded) return
                val state = loadState()
                val isVpnRunning = appStatus.first == AppStatus.Running

                if (!isVpnRunning) {
                    nodeStatusText.text = "VPN is Disconnected (Start VPN on home screen)"
                } else if (state != null) {
                    nodeStatusText.text = "Node: ${state.nodeAddress}  |  Status: ${if (state.isOnline) "ONLINE" else "OFFLINE"}"
                } else {
                    nodeStatusText.text = "Connecting to ZeroTier..."
                }

                recyclerView.postDelayed(this, 1500)
            }
        })
    }

    private fun loadState(): ZeroTierManager.SharedState? {
        return try {
            val file = File(requireContext().filesDir, "zerotier_state.json")
            if (file.exists()) {
                gson.fromJson(file.readText(), ZeroTierManager.SharedState::class.java)
            } else null
        } catch (_: Exception) {
            null
        }
    }
}