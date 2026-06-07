package com.hpu.selfcammonitor.ui.recordings

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hpu.selfcammonitor.R
import java.io.File

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
        val tvName: TextView = view.findViewById(android.R.id.text1)
        val tvInfo: TextView = view.findViewById(android.R.id.text2)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        val fileCount = folder.listFiles()?.size ?: 0
        val totalSize = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val sizeMB = totalSize / (1024 * 1024)
        holder.tvName.text = folder.name
        holder.tvInfo.text = "$fileCount 个视频，${sizeMB} MB"

        // 多选模式显示 CheckBox，否则隐藏
        holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = selectedFolders.contains(folder)

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
                onSelectionChanged?.invoke(selectedFolders.size)
                // 也可以通知 Activity 刷新计数，由外部处理
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                // 同步状态（防止重复）
                if (isChecked && !selectedFolders.contains(folder)) {
                    selectedFolders.add(folder)
                    onSelectionChanged?.invoke(selectedFolders.size)
                } else if (!isChecked && selectedFolders.contains(folder)) {
                    selectedFolders.remove(folder)
                    onSelectionChanged?.invoke(selectedFolders.size)
                }
            }
        } else {
            // 普通模式：点击进入详情，长按进入多选
            holder.itemView.setOnClickListener { onFolderClick(folder) }
            holder.itemView.setOnLongClickListener { onFolderLongClick(folder) }
        }
    }

    override fun getItemCount() = folders.size
}