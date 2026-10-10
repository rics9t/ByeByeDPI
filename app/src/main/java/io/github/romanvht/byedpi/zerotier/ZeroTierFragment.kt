package io.github.romanvht.byedpi.zerotier

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.romanvht.byedpi.R

class ZeroTierFragment : Fragment() {

    private lateinit var adapter: ZeroTierAdapter

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
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerView)
        adapter = ZeroTierAdapter(manager.networks.toMutableList()) { nwid ->
            AlertDialog.Builder(requireContext())
                .setTitle("Delete Network")
                .setMessage("Remove network $nwid?")
                .setPositiveButton("Yes") { _, _ ->
                    manager.leave(nwid)
                    adapter.removeNetwork(nwid)
                }
                .setNegativeButton("No", null)
                .show()
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            val input = EditText(requireContext())
            AlertDialog.Builder(requireContext())
                .setTitle("Join Network")
                .setMessage("Enter 16-character Network ID")
                .setView(input)
                .setPositiveButton("Join") { _, _ ->
                    val nwid = input.text.toString().trim()
                    if (nwid.length == 16) {
                        manager.join(nwid)
                        adapter.addNetwork(nwid)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        recyclerView.post(object : Runnable {
            override fun run() {
                if (!isAdded) return
                val configs = manager.getConfigs()
                adapter.updateConfigs(configs)
                recyclerView.postDelayed(this, 2000)
            }
        })
    }
}