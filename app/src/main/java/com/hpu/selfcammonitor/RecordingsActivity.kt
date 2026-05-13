package com.hpu.selfcammonitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import android.view.MotionEvent



class RecordingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecordingsAdapter

    // 工具栏组件
    private lateinit var btnSelectAll: Button
    private lateinit var searchBar: LinearLayout          // 搜索栏整体
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnSearchConfirm: Button
    private lateinit var spacer: View                     // 占位
    private lateinit var btnSearch: Button
    private lateinit var btnCancelSelect: Button
    private lateinit var btnDelete: Button

    // 数据
    private var allFiles: List<File> = emptyList()
    private var currentFiles: List<File> = emptyList()
    private var isSelectMode = false
    private val selectedFiles = mutableSetOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        btnSelectAll = findViewById(R.id.btnSelectAll)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnSearchConfirm = findViewById(R.id.btnSearchConfirm)
        spacer = findViewById(R.id.spacer)
        btnSearch = findViewById(R.id.btnSearch)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        btnDelete = findViewById(R.id.btnDelete)

        loadAllFiles()
        updateCurrentFiles(allFiles)

        // ---------- 搜索相关 ----------
        btnSearch.setOnClickListener {
            enterSearchMode()
        }

        btnSearchConfirm.setOnClickListener {
            performSearch()
        }
        // 键盘搜索键
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        // 清除按钮
        btnClearSearch.setOnClickListener {
            etSearch.text.clear()
        }

        // 当输入框为空且点击列表时，取消搜索
        recyclerView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN &&
                searchBar.visibility == View.VISIBLE &&           // 搜索模式
                etSearch.text.isEmpty()) {
                cancelSearch()
                recyclerView.requestFocus()  // 转移焦点，收起键盘
                return@setOnTouchListener true
            }
            false
        }
        // 点击活动根布局空白区域（如果 RecycleView 没有消耗事件）也可以取消搜索
        findViewById<View>(android.R.id.content).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN &&
                searchBar.visibility == View.VISIBLE &&
                etSearch.text.isEmpty()) {
                cancelSearch()
                return@setOnTouchListener true
            }
            false
        }

        // ---------- 多选相关 ----------
        btnSelectAll.setOnClickListener {
            selectedFiles.clear()
            selectedFiles.addAll(currentFiles)
            adapter.selectedFiles = selectedFiles
            adapter.notifyDataSetChanged()
            updateDeleteButton()
        }

        btnCancelSelect.setOnClickListener {
            exitSelectMode()
        }

        btnDelete.setOnClickListener {
            deleteSelectedFiles()
        }
    }

    // ======================= 模式管理 =======================
    private fun enterSearchMode() {
        // 如果正在多选，先退出多选
        if (isSelectMode) {
            exitSelectMode()
        }
        searchBar.visibility = View.VISIBLE
        btnSearch.visibility = View.GONE
        btnCancelSelect.visibility = View.GONE
        btnSelectAll.visibility = View.GONE
        spacer.visibility = View.GONE  // 让搜索栏占据空间
        etSearch.requestFocus()
    }

    private fun cancelSearch() {
        searchBar.visibility = View.GONE
        etSearch.text.clear()
        btnSearch.visibility = View.VISIBLE
        spacer.visibility = View.VISIBLE
        // 恢复原始列表（退出搜索过滤）
        updateCurrentFiles(allFiles)
    }

    private fun performSearch() {
        val keyword = etSearch.text.toString().trim().lowercase()
        val filtered = if (keyword.isEmpty()) allFiles
        else allFiles.filter { it.name.lowercase().contains(keyword) }
        updateCurrentFiles(filtered)
        // 隐藏键盘
        currentFocus?.clearFocus()
    }

    private fun enterSelectMode(firstFile: File) {
        // 如果正在搜索，先取消搜索
        if (searchBar.visibility == View.VISIBLE) {
            cancelSearchDirectly()   // 强制退出搜索模式，不清除关键词? 需求说取消搜索时多选也取消，这里我们先退出搜索但不执行搜索，回到原始列表再进多选
        }
        selectedFiles.clear()
        selectedFiles.add(firstFile)
        isSelectMode = true
        adapter.isSelectMode = true
        adapter.selectedFiles = selectedFiles
        adapter.notifyDataSetChanged()
        showSelectUI()
        updateDeleteButton()
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedFiles.clear()
        adapter.isSelectMode = false
        adapter.selectedFiles = selectedFiles
        adapter.notifyDataSetChanged()
        hideSelectUI()
    }

    private fun showSelectUI() {
        btnSelectAll.visibility = View.VISIBLE
        btnCancelSelect.visibility = View.VISIBLE
        btnDelete.visibility = View.VISIBLE
        btnSearch.visibility = View.GONE
        searchBar.visibility = View.GONE
        spacer.visibility = View.VISIBLE   // 让全选和取消分列两侧
    }

    private fun hideSelectUI() {
        btnSelectAll.visibility = View.GONE
        btnCancelSelect.visibility = View.GONE
        btnDelete.visibility = View.GONE
        btnSearch.visibility = View.VISIBLE
        spacer.visibility = if (searchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // 强制退出搜索模式（不清除文本，直接隐藏界面）
    private fun cancelSearchDirectly() {
        searchBar.visibility = View.GONE
        btnSearch.visibility = View.VISIBLE
        spacer.visibility = View.VISIBLE
        // 注意：不调用 updateCurrentFiles，保留当前列表（但后面进入多选时会重新加载，所以没关系）
    }

    // ======================= 辅助方法 =======================
    private fun loadAllFiles() {
        val dir = File(getExternalFilesDir(null), "Recordings")
        allFiles = if (dir.exists()) {
            dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else emptyList()
    }

    private fun updateCurrentFiles(files: List<File>) {
        selectedFiles.removeAll { !files.contains(it) }
        currentFiles = files
        adapter = RecordingsAdapter(
            files,
            onFileClick = { file ->
                if (isSelectMode) {
                    toggleSelection(file)
                } else {
                    playVideo(file)
                }
            },
            onFileLongClick = { _, file ->
                if (!isSelectMode) {
                    enterSelectMode(file)
                }
                true
            }
        )
        adapter.isSelectMode = isSelectMode
        adapter.selectedFiles = selectedFiles
        recyclerView.adapter = adapter
        if (isSelectMode) updateDeleteButton()
    }

    private fun toggleSelection(file: File) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
            if (selectedFiles.isEmpty()) {
                exitSelectMode()
                return
            }
        } else {
            selectedFiles.add(file)
        }
        adapter.selectedFiles = selectedFiles
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun updateDeleteButton() {
        val validCount = selectedFiles.count { it in currentFiles }
        btnDelete.isEnabled = validCount > 0
        btnDelete.text = "删除选中 ($validCount)"
    }

    private fun deleteSelectedFiles() {
        val toDelete = selectedFiles.intersect(currentFiles).toSet()
        if (toDelete.isEmpty()) return
        for (file in toDelete) {
            file.delete()
        }
        allFiles = allFiles.filter { !toDelete.contains(it) }
        val keyword = etSearch.text.toString().trim().lowercase()
        val filtered = if (keyword.isEmpty()) allFiles
        else allFiles.filter { it.name.lowercase().contains(keyword) }
        exitSelectMode()
        selectedFiles.clear()
        updateCurrentFiles(filtered)
    }

    private fun playVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }
}

// 在 RecordingsActivity 内部保留，或独立文件均可
class RecordingsAdapter(
    private val files: List<File>,
    private val onFileClick: (File) -> Unit,
    private val onFileLongClick: (View, File) -> Boolean
) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

    // 由外部设置
    var selectedFiles: MutableSet<File> = mutableSetOf()
    var isSelectMode: Boolean = false

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(android.R.id.text1)
        val tvFileSize: TextView = view.findViewById(android.R.id.text2)
        val checkBox: CheckBox = view.findViewById(R.id.checkbox)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.tvFileName.text = file.name
        holder.tvFileSize.text = "${file.length() / 1024} KB"

        // 多选框显示状态
        holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = selectedFiles.contains(file)

        // 短按
        holder.itemView.setOnClickListener {
            onFileClick(file)
        }
        // 长按
        holder.itemView.setOnLongClickListener {
            onFileLongClick(it, file)
        }
    }

    override fun getItemCount() = files.size

    // 更新选中状态并刷新UI
    fun updateSelection(selectMode: Boolean, selected: MutableSet<File>) {
        this.isSelectMode = selectMode
        this.selectedFiles = selected
        notifyDataSetChanged()
    }
}