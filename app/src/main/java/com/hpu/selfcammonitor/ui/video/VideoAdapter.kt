package com.hpu.selfcammonitor.ui.video

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
                holder.applySelectionTint(newChecked)   // 同步选中态背景
                onSelectionChanged?.invoke(selectedVideos.size)
            }
            holder.itemView.setOnClickListener(clickListener)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                // 防止重复触发
                if (isChecked && !selectedVideos.contains(video)) {
                    selectedVideos.add(video)
                    holder.applySelectionTint(true)
                    onSelectionChanged?.invoke(selectedVideos.size)
                } else if (!isChecked && selectedVideos.contains(video)) {
                    selectedVideos.remove(video)
                    holder.applySelectionTint(false)
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
        private val cardItem: CardView = view.findViewById(R.id.cardItem)
        val tvTitle: TextView = view.findViewById(android.R.id.text1)
        private val tvTypeBadge: TextView = view.findViewById(R.id.tvTypeBadge)
        private val tvMeta: TextView = view.findViewById(R.id.tvMeta)
        private val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)

        fun bind(video: File, dateTime: String, duration: String, isSelectMode: Boolean, isSelected: Boolean) {
            // 标题：录制时间（HH:mm:ss），元数据未就绪时回退显示文件名
            val timePart = dateTime.substringAfter(' ', "")
            tvTitle.text = timePart.ifEmpty { video.name }

            // 类型标签：从文件名前缀判断
            when {
                video.name.startsWith("motion_") -> {
                    tvTypeBadge.visibility = View.VISIBLE
                    tvTypeBadge.text = "运动触发"
                    tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_motion)
                    tvTypeBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.status_stopped))
                }
                video.name.startsWith("video_") -> {
                    tvTypeBadge.visibility = View.VISIBLE
                    tvTypeBadge.text = "连续录像"
                    tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_video)
                    tvTypeBadge.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
                }
                else -> tvTypeBadge.visibility = View.GONE
            }

            // 元信息行：日期 · 时长 · 大小
            val datePart = dateTime.substringBefore(' ', "")
            val meta = listOf(
                datePart.ifEmpty { "日期未知" },
                "时长 ${duration.ifEmpty { "--:--" }}",
                FileSizeFormatter.format(video.length())
            ).joinToString(" · ")
            tvMeta.text = meta

            // 文件名降级为次要信息
            tvFileName.text = "文件名：${video.name}"

            checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            checkBox.isChecked = isSelected
            applySelectionTint(isSelected)
        }

        // 选中项卡片背景加浅主色 tint，未选中恢复白色
        fun applySelectionTint(selected: Boolean) {
            cardItem.setCardBackgroundColor(
                if (selected) Color.parseColor("#1A2196F3")
                else ContextCompat.getColor(itemView.context, R.color.surface)
            )
        }
    }
}
