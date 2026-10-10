package io.github.romanvht.byedpi.zerotier

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.romanvht.byedpi.R
import com.zerotier.sdk.VirtualNetworkConfig

class ZeroTierAdapter(
    private val networks: MutableList<String>,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<ZeroTierAdapter.ViewHolder>() {

    private val configs = mutableMapOf<String, VirtualNetworkConfig>()

    @SuppressLint("NotifyDataSetChanged")
    fun updateConfigs(newConfigs: List<VirtualNetworkConfig>) {
        configs.clear()
        newConfigs.forEach {
            configs[String.format("%016x", it.nwid)] = it
        }
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
        holder.networkId.text = nwid
        val config = configs[nwid]
        holder.networkStatus.text = if (config != null) "Status: ${config.status} | Name: ${config.name}" else "Status: REQUESTING_CONFIGURATION"
        
        holder.itemView.setOnLongClickListener {
            onDelete(nwid)
            true
        }
    }

    override fun getItemCount() = networks.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val networkId: TextView = view.findViewById(R.id.networkId)
        val networkStatus: TextView = view.findViewById(R.id.networkStatus)
    }
}