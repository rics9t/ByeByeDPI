package io.github.romanvht.byedpi.zerotier

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.romanvht.byedpi.R
import com.zerotier.sdk.VirtualNetworkConfig

class ZeroTierAdapter(
    private val networks: MutableList<String>,
    private var enabledNetworks: Set<String>,
    private val onToggle: (String, Boolean) -> Unit,
    private val onClick: (String, VirtualNetworkConfig?) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<ZeroTierAdapter.ViewHolder>() {

    private val configs = mutableMapOf<String, VirtualNetworkConfig>()

    @SuppressLint("NotifyDataSetChanged")
    fun updateConfigs(newConfigs: List<VirtualNetworkConfig>, newEnabled: Set<String>) {
        configs.clear()
        newConfigs.forEach {
            configs[String.format("%016x", it.nwid)] = it
        }
        enabledNetworks = newEnabled
        notifyDataSetChanged()
    }

    fun addNetwork(nwid: String) {
        if (!networks.contains(nwid)) {
            networks.add(nwid)
            notifyItemInserted(networks.size - 1)
        }
    }

    fun removeNetwork(nwid: String) {
        val idx = networks.indexOf(nwid)
        if (idx != -1) {
            networks.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_zerotier_network, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val nwid = networks[position]
        val config = configs[nwid]
        
        holder.networkId.text = nwid
        holder.networkName.text = config?.name ?: "-"

        holder.networkSwitch.setOnCheckedChangeListener(null)
        holder.networkSwitch.isChecked = enabledNetworks.contains(nwid)
        holder.networkSwitch.setOnCheckedChangeListener { _, isChecked ->
            onToggle(nwid, isChecked)
        }

        holder.itemView.setOnClickListener {
            onClick(nwid, config)
        }
        
        holder.itemView.setOnLongClickListener {
            onDelete(nwid)
            true
        }
    }

    override fun getItemCount() = networks.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val networkId: TextView = view.findViewById(R.id.networkId)
        val networkName: TextView = view.findViewById(R.id.networkName)
        val networkSwitch: SwitchMaterial = view.findViewById(R.id.networkSwitch)
    }
}