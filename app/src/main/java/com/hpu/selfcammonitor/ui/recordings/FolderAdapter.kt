package com.hpu.selfcammonitor.ui.recordings

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.utils.FileSizeFormatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class FolderAdapter(
    private val folders: List<File>,
    private val onFolderClick: (File) -> Unit,
    private val onFolderLongClick: (File) -> Boolean
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    var isSelectMode = false
    var selectedFolders = mutableSetOf<File>()

    // 添加选中状态变化监听器
    var onSelectionChanged: ((Int) -> Unit)? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardItem: CardView = view.findViewById(R.id.cardItem)
        val tvName: TextView = view.findViewById(android.R.id.text1)
        val tvInfo: TextView = view.findViewById(android.R.id.text2)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)

        // 选中项卡片背景加浅主色 tint，未选中恢复白色
        fun applySelectionTint(selected: Boolean) {
            cardItem.setCardBackgroundColor(
                if (selected) Color.parseColor("#1A2196F3")
                else ContextCompat.getColor(itemView.context, R.color.surface)
            )
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        val fileCount = folder.listFiles()?.size ?: 0
        val totalSize = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val friendly = friendlyDate(folder.name)
        val info = "$fileCount 个视频，${FileSizeFormatter.format(totalSize)}"
        holder.tvName.text = folder.name
        holder.tvInfo.text = if (friendly.isNotEmpty()) "$friendly · $info" else info

        // 多选模式显示 CheckBox，否则隐藏
        holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = selectedFolders.contains(folder)
        holder.applySelectionTint(selectedFolders.contains(folder))

        // 避免重复设置监听器导致闪烁
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.itemView.setOnClickListener(null)

        if (isSelectMode) {
            // 多选模式：点击整个 item 或 checkbox 都切换选中状态
            val clickListener = View.OnClickListener {
                val newChecked = !selectedFolders.contains(folder)
                if (newChecked) {
                    selectedFolders.add(folder)
                } else {
                    selectedFolders.remove(folder)
                }
                holder.checkBox.isChecked = newChecked
                holder.applySelectionTint(newChecked)
                onSelectionChanged?.invoke(selectedFolders.size)
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                // 同步状态（防止重复）
                if (isChecked && !selectedFolders.contains(folder)) {
                    selectedFolders.add(folder)
                    holder.applySelectionTint(true)
                    onSelectionChanged?.invoke(selectedFolders.size)
                } else if (!isChecked && selectedFolders.contains(folder)) {
                    selectedFolders.remove(folder)
                    holder.applySelectionTint(false)
                    onSelectionChanged?.invoke(selectedFolders.size)
                }
            }
        } else {
            // 普通模式：点击进入详情，长按进入多选
            holder.itemView.setOnClickListener { onFolderClick(folder) }
            holder.itemView.setOnLongClickListener { onFolderLongClick(folder) }
        }
    }

    // 文件夹名（yyyy-MM-dd）友好化：今天/昨天加前缀，其余保持原样
    private fun friendlyDate(folderName: String): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        if (folderName == sdf.format(Date())) return "今天"
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        if (folderName == sdf.format(yesterday.time)) return "昨天"
        return ""
    }

    override fun getItemCount() = folders.size
}
