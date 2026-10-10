package io.github.romanvht.byedpi.zerotier

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
import io.github.romanvht.byedpi.R

class ZeroTierFragment : Fragment() {

    private lateinit var adapter: ZeroTierAdapter
    private lateinit var nodeStatusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_zerotier, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val manager = ZeroTierServiceManager.getInstance(requireContext())
        nodeStatusText = view.findViewById(R.id.nodeStatusText)
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        adapter = ZeroTierAdapter(
            manager.savedNetworks.toMutableList(),
            manager.enabledNetworks,
            onToggle = { nwid, enable -> manager.toggleNetwork(nwid, enable) },
            onClick = { nwid, config ->
                val details = """
                    Network ID: ${nwid}
                    Network Name: ${config?.name ?: "-"}
                    Status: ${config?.status?.name?.replace("NETWORK_STATUS_", "") ?: "NOT CONNECTED"}
                    Type: ${config?.type?.name?.replace("NETWORK_TYPE_", "") ?: "-"}
                    MAC: ${config?.mac?.let { String.format("%012x", it) } ?: "-"}
                    MTU: ${config?.mtu ?: "-"}
                    Broadcast: ${config?.isBroadcastEnabled ?: "-"}
                    Bridging: ${config?.isBridge ?: "-"}
                    Managed IPs: ${config?.assignedAddresses?.joinToString(", ") { it.address?.hostAddress ?: it.hostString } ?: "-"}
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
                        manager.removeNetwork(nwid)
                        adapter.removeNetwork(nwid)
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
                    val nwid = input.text.toString().trim()
                    if (nwid.matches(Regex("[0-9a-fA-F]{16}"))) {
                        manager.addNetwork(nwid.lowercase())
                        adapter.addNetwork(nwid.lowercase())
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
                val configs = manager.getConfigs()
                adapter.updateConfigs(configs, manager.enabledNetworks)
                
                val nodeAddress = manager.node?.address()?.let { String.format("%010x", it) } ?: "--------"
                val nodeOnline = manager.node?.status()?.isOnline == true
                nodeStatusText.text = "Node: $nodeAddress  |  Status: ${if(nodeOnline) "ONLINE" else "OFFLINE"}"
                
                recyclerView.postDelayed(this, 1500)
            }
        })
    }
}