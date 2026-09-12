package com.example.inventory.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.inventory.Item
import com.example.inventory.R

class ItemAdapter(
    private val items: MutableList<Item>,
    private val onQtyChanged: (Item, Int) -> Unit
) : RecyclerView.Adapter<ItemAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val info: TextView = view.findViewById(R.id.rowInfo)
        val qty: EditText = view.findViewById(R.id.rowQty)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.info.text = "${item.barcode}  |  ${item.name}  |  ${item.price}"
        holder.qty.setTag(item.barcode)
        holder.qty.tag = item.barcode
        holder.qty.removeTextChangedListener(holder.qty.getTag(R.id.text_watcher_tag) as? android.text.TextWatcher)
        if (holder.qty.text.toString() != item.qty.toString()) {
            holder.qty.setText(item.qty.toString())
        }
        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                if (holder.qty.tag == item.barcode) {
                    val newQty = s?.toString()?.toIntOrNull() ?: 0
                    onQtyChanged(item, newQty)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        holder.qty.setTag(R.id.text_watcher_tag, watcher)
        holder.qty.addTextChangedListener(watcher)
    }

    override fun getItemCount() = items.size
}
