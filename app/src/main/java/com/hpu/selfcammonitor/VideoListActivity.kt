package com.hpu.selfcammonitor

import android.app.ProgressDialog
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.HashMap

class VideoListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoAdapter
    private lateinit var tvTitle: TextView
    private lateinit var tvFileCount: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnSelectAll: Button
    private lateinit var btnCancelSelect: Button
    private lateinit var btnDelete: Button
    private lateinit var btnExport: Button
    private lateinit var buttonCard: LinearLayout

    // 搜索相关
    private lateinit var searchBar: CardView
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnSearchConfirm: Button

    private var allVideos: List<File> = emptyList()
    private var currentVideos: List<File> = emptyList()
    private var isSelectMode = false
    private val selectedVideos = mutableSetOf<File>()

    // 元数据缓存
    private var dateTimeMap = HashMap<File, String>()
    private var durationMap = HashMap<File, String>()
    private var cachedFilesSignature = 0

    private lateinit var folderPath: String

    private val pickDocumentLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { exportSelectedVideos(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_list)

        folderPath = intent.getStringExtra("folder_path") ?: run {
            finish()
            return
        }

        initViews()
        loadVideos()
        setupAdapter()
        setupListeners()
        setupSearchBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isSelectMode) {
                    exitSelectMode()
                } else {
                    // 注意：这里不能直接调用 super.onBackPressed()
                    // 需要调用 finish() 或传递给上一个回调
                    finish()
                }
            }
        })
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        tvTitle = findViewById(R.id.tvTitle)
        tvFileCount = findViewById(R.id.tvFileCount)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnBack = findViewById(R.id.btnBack)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        btnDelete = findViewById(R.id.btnDelete)
        btnExport = findViewById(R.id.btnExport)
        buttonCard = findViewById(R.id.buttonCard)

        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnSearchConfirm = findViewById(R.id.btnSearchConfirm)

        val folderFile = File(folderPath)
        tvTitle.text = folderFile.name
    }

    private fun loadVideos() {
        val folder = File(folderPath)
        val newFiles = if (folder.exists()) {
            folder.listFiles()?.filter { it.extension == "mp4" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else emptyList()

        val newSignature = newFiles.hashCode()
        if (cachedFilesSignature != newSignature) {
            cachedFilesSignature = newSignature
            dateTimeMap.clear()
            durationMap.clear()
        }

        allVideos = newFiles
        currentVideos = allVideos

        if (dateTimeMap.isEmpty() || durationMap.isEmpty()) {
            loadMetadataAsync()
        }
    }

    private fun loadMetadataAsync() {
        Thread {
            val newDateTimeMap = HashMap<File, String>()
            val newDurationMap = HashMap<File, String>()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val retriever = MediaMetadataRetriever()

            for (file in allVideos) {
                val dateTimeStr = extractDateTimeFromFileName(file.name) ?: sdf.format(Date(file.lastModified()))
                newDateTimeMap[file] = dateTimeStr

                try {
                    retriever.setDataSource(file.absolutePath)
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    newDurationMap[file] = formatDuration(durationMs)
                } catch (e: Exception) {
                    newDurationMap[file] = "未知"
                }
            }
            retriever.release()

            runOnUiThread {
                dateTimeMap = newDateTimeMap
                durationMap = newDurationMap
                adapter?.updateMetadata(dateTimeMap, durationMap)
            }
        }.start()
    }

    private fun extractDateTimeFromFileName(fileName: String): String? {
        val motionPattern = Regex("motion_(\\d{12})_\\d{3}\\.mp4")
        motionPattern.find(fileName)?.let {
            val dateTimePart = it.groupValues[1]
            return try {
                val sdf = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                val date = sdf.parse(dateTimePart)
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
            } catch (e: Exception) {
                null
            }
        }

        val continuousPattern = Regex("continuous_(\\d+)\\.mp4")
        continuousPattern.find(fileName)?.let {
            val timestamp = it.groupValues[1].toLongOrNull()
            if (timestamp != null) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                return sdf.format(Date(timestamp))
            }
        }
        return null
    }

    private fun formatDuration(millis: Long): String {
        if (millis <= 0) return "0:00"
        val seconds = millis / 1000
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, secs)
        else String.format("%d:%02d", minutes, secs)
    }

    private fun setupAdapter() {
        adapter = VideoAdapter(
            currentVideos,
            onVideoClick = { video ->
                if (isSelectMode) toggleSelection(video)
                else playVideo(video)
            },
            onVideoLongClick = { video ->
                if (!isSelectMode) {
                    enterSelectMode(video)
                    true
                } else false
            }
        )
        adapter.isSelectMode = isSelectMode
        adapter.selectedVideos = selectedVideos
        adapter.updateMetadata(dateTimeMap, durationMap)
        recyclerView.adapter = adapter
        updateFileCountDisplay()
    }

    private fun setupSearchBar() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            if (etSearch.text.isNotEmpty()) {
                // 有文字：清空输入框，并触发搜索（刷新为全部列表）
                etSearch.text.clear()
                performSearch()   // 这会重新根据空关键词过滤，显示全部视频
                // 焦点保留，键盘不隐藏（clear 后焦点会自动保留）
            } else {
                // 无文字：隐藏键盘并清除焦点
                etSearch.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
            }
        }

        btnSearchConfirm.setOnClickListener {
            performSearch()
        }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }
    }

    private fun performSearch() {
        val keyword = etSearch.text.toString().trim().lowercase()
        currentVideos = if (keyword.isEmpty()) allVideos
        else allVideos.filter { it.name.lowercase().contains(keyword) }
        adapter.updateData(currentVideos)
        updateFileCountDisplay()
        currentFocus?.clearFocus()
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnSelectAll.setOnClickListener {
            if (selectedVideos.size == currentVideos.size) {
                selectedVideos.clear()
            } else {
                selectedVideos.clear()
                selectedVideos.addAll(currentVideos)
            }
            adapter.selectedVideos = selectedVideos
            adapter.notifyDataSetChanged()
            updateSelectedCount()
            updateDeleteButton()
        }

        btnCancelSelect.setOnClickListener { exitSelectMode() }
        btnDelete.setOnClickListener { deleteSelectedVideos() }
        btnExport.setOnClickListener { if (selectedVideos.isNotEmpty()) showExportSelector() }
    }

    private fun enterSelectMode(firstVideo: File) {
        selectedVideos.clear()
        selectedVideos.add(firstVideo)
        isSelectMode = true
        adapter.isSelectMode = true
        adapter.selectedVideos = selectedVideos
        adapter.notifyDataSetChanged()
        showSelectUI()
        updateSelectedCount()
        updateDeleteButton()
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedVideos.clear()
        adapter.isSelectMode = false
        adapter.selectedVideos = selectedVideos
        adapter.notifyDataSetChanged()
        hideSelectUI()
    }

    private fun toggleSelection(video: File) {
        if (selectedVideos.contains(video)) selectedVideos.remove(video)
        else selectedVideos.add(video)
        adapter.selectedVideos = selectedVideos
        adapter.notifyDataSetChanged()
        updateSelectedCount()
        updateDeleteButton()
    }

    private fun updateSelectedCount() {
        val count = selectedVideos.size
        tvSelectedCount.text = "已选 $count 项"
        // 调试日志
        android.util.Log.d("VideoList", "选中数量: $count")
    }

    private fun updateDeleteButton() {
        btnDelete.isEnabled = selectedVideos.isNotEmpty()
        updateButtonCardVisibility()
    }

    private fun updateButtonCardVisibility() {
        buttonCard.visibility = if (isSelectMode && selectedVideos.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSelectUI() {
        // 隐藏普通模式控件
        btnBack.visibility = View.GONE
        tvTitle.visibility = View.GONE
        tvFileCount.visibility = View.GONE
        // 隐藏搜索栏（新增）
        searchBar.visibility = View.GONE
        // 显示多选模式控件
        btnSelectAll.visibility = View.VISIBLE
        tvSelectedCount.visibility = View.VISIBLE
        btnCancelSelect.visibility = View.VISIBLE
        updateSelectedCount()
        updateButtonCardVisibility()
    }

    private fun hideSelectUI() {
        // 显示普通模式控件
        btnBack.visibility = View.VISIBLE
        tvTitle.visibility = View.VISIBLE
        tvFileCount.visibility = View.VISIBLE
        // 显示搜索栏（新增）
        searchBar.visibility = View.VISIBLE
        // 隐藏多选模式控件
        btnSelectAll.visibility = View.GONE
        tvSelectedCount.visibility = View.GONE
        btnCancelSelect.visibility = View.GONE
        buttonCard.visibility = View.GONE
        updateFileCountDisplay()
    }

    private fun updateFileCountDisplay() {
        val count = currentVideos.size
        tvFileCount.text = "共 $count 个视频"
    }

    private fun deleteSelectedVideos() {
        val toDelete = selectedVideos.toSet()
        if (toDelete.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("删除 ${toDelete.size} 个视频？")
            .setPositiveButton("删除") { _, _ ->
                for (video in toDelete) {
                    video.delete()
                }
                loadVideos()
                currentVideos = allVideos
                exitSelectMode()
                adapter.updateData(currentVideos)
                updateFileCountDisplay()
                loadMetadataAsync()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showExportSelector() {
        AlertDialog.Builder(this)
            .setTitle("导出选中视频")
            .setMessage("将 ${selectedVideos.size} 个视频导出到外部存储")
            .setPositiveButton("选择文件夹") { _, _ ->
                pickDocumentLauncher.launch(null)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportSelectedVideos(treeUri: android.net.Uri) {
        if (selectedVideos.isEmpty()) return
        val progress = ProgressDialog(this)
        progress.setMessage("正在导出 ${selectedVideos.size} 个视频...")
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        progress.setCancelable(false)
        progress.show()

        val rootDocument = DocumentFile.fromTreeUri(this, treeUri)
        var successCount = 0
        val failedFiles = mutableListOf<String>()

        for (video in selectedVideos) {
            try {
                if (copyFileToDocument(video, rootDocument)) {
                    successCount++
                } else {
                    failedFiles.add(video.name)
                }
            } catch (e: Exception) {
                failedFiles.add(video.name)
            }
        }

        progress.dismiss()
        val message = if (failedFiles.isEmpty()) "成功导出 $successCount 个文件"
        else "成功导出 $successCount 个文件，失败：${failedFiles.joinToString()}"
        AlertDialog.Builder(this)
            .setTitle("导出完成")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> exitSelectMode() }
            .show()
    }

    private fun copyFileToDocument(sourceFile: File, rootDocument: DocumentFile?): Boolean {
        return try {
            val destFile = rootDocument?.createFile("video/mp4", sourceFile.name) ?: return false
            val sourceStream = java.io.FileInputStream(sourceFile)
            val destStream = contentResolver.openOutputStream(destFile.uri) ?: return false
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (sourceStream.read(buffer).also { bytesRead = it } != -1) {
                destStream.write(buffer, 0, bytesRead)
            }
            sourceStream.close()
            destStream.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun playVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "播放视频"))
    }

    // 适配器
    inner class VideoAdapter(
        private var videos: List<File>,
        private val onVideoClick: (File) -> Unit,
        private val onVideoLongClick: (File) -> Boolean
    ) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

        var isSelectMode = false
        var selectedVideos = mutableSetOf<File>()
        private var dateTimeMap: Map<File, String> = emptyMap()
        private var durationMap: Map<File, String> = emptyMap()

        fun updateData(newVideos: List<File>) {
            videos = newVideos
            notifyDataSetChanged()
        }

        fun updateMetadata(dateTime: Map<File, String>, duration: Map<File, String>) {
            dateTimeMap = dateTime
            durationMap = duration
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false)
            return VideoViewHolder(view)
        }

        override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
            val video = videos[position]
            holder.tvFileName.text = video.name
            holder.tvFileSize.text = "${video.length() / 1024} KB"
            holder.tvDateTime.text = dateTimeMap[video] ?: ""
            holder.tvDuration.text = durationMap[video] ?: ""

            holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
            holder.checkBox.isChecked = selectedVideos.contains(video)

            holder.itemView.setOnClickListener { onVideoClick(video) }
            holder.itemView.setOnLongClickListener { onVideoLongClick(video) }
        }

        override fun getItemCount() = videos.size

        inner class VideoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName: TextView = view.findViewById(android.R.id.text1)
            val tvFileSize: TextView = view.findViewById(android.R.id.text2)
            val tvDateTime: TextView = view.findViewById(R.id.tvDateTime)
            val tvDuration: TextView = view.findViewById(R.id.tvDuration)
            val checkBox: CheckBox = view.findViewById(R.id.checkbox)
        }
    }
}