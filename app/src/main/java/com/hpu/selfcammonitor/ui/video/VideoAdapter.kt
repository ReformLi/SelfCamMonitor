package com.hpu.selfcammonitor.ui.video

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hpu.selfcammonitor.R
import java.io.File

class VideoAdapter(
    private val inflater: LayoutInflater,
    private var videos: List<File>,
    private val onVideoPlayClick: (File) -> Unit,          // 普通模式点击播放
    private val onEnterSelectMode: (File) -> Unit          // 长按进入多选模式
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    var isSelectMode = false
    var selectedVideos = mutableSetOf<File>()
    var onSelectionChanged: ((Int) -> Unit)? = null   // 选中数量变化回调

    private var dateTimeMap: Map<File, String> = emptyMap()
    private var durationMap: Map<File, String> = emptyMap()

    fun updateData(newVideos: List<File>) {
        this.videos = newVideos
        notifyDataSetChanged()
    }

    fun updateMetadata(dateTime: Map<File, String>, duration: Map<File, String>) {
        this.dateTimeMap = dateTime
        this.durationMap = duration
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = inflater.inflate(R.layout.item_recording, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        val video = videos[position]
        holder.bind(video, dateTimeMap[video] ?: "", durationMap[video] ?: "", isSelectMode, selectedVideos.contains(video))

        // 清除之前的监听器，避免重复
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
        holder.checkBox.setOnCheckedChangeListener(null)

        if (isSelectMode) {
            // 多选模式：点击 item 或 checkbox 都切换选中状态
            val clickListener = View.OnClickListener {
                val newChecked = !selectedVideos.contains(video)
                if (newChecked) {
                    selectedVideos.add(video)
                } else {
                    selectedVideos.remove(video)
                }
                holder.checkBox.isChecked = newChecked
                onSelectionChanged?.invoke(selectedVideos.size)
                // 注意：不需要 notifyDataSetChanged()，因为只改变复选框状态，可以局部更新
                // 如果担心其他数据变化，可以调用 notifyItemChanged(position)
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                // 防止重复触发
                if (isChecked && !selectedVideos.contains(video)) {
                    selectedVideos.add(video)
                    onSelectionChanged?.invoke(selectedVideos.size)
                } else if (!isChecked && selectedVideos.contains(video)) {
                    selectedVideos.remove(video)
                    onSelectionChanged?.invoke(selectedVideos.size)
                }
            }
        } else {
            // 普通模式：点击播放，长按进入多选
            holder.itemView.setOnClickListener { onVideoPlayClick(video) }
            holder.itemView.setOnLongClickListener {
                onEnterSelectMode(video)
                true
            }
        }
    }

    override fun getItemCount() = videos.size

    class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(android.R.id.text1)
        val tvFileSize: TextView = view.findViewById(android.R.id.text2)
        val tvDateTime: TextView = view.findViewById(R.id.tvDateTime)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)

        fun bind(video: File, dateTime: String, duration: String, isSelectMode: Boolean, isSelected: Boolean) {
            tvFileName.text = video.name
            tvFileSize.text = "${video.length() / 1024} KB"
            tvDateTime.text = dateTime
            tvDuration.text = duration
            checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
        }
    }
}