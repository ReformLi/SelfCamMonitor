package com.hpu.selfcammonitor

import android.app.ProgressDialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import android.view.MotionEvent
import kotlin.compareTo


class RecordingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RecordingsAdapter

    // 工具栏组件
    private lateinit var btnSelectAll: Button
    private lateinit var tvFileCount: TextView
    private lateinit var searchBar: LinearLayout          // 搜索栏整体
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageButton
    private lateinit var btnSearchConfirm: Button
    private lateinit var spacer: View                     // 占位
    private lateinit var btnSearch: Button
    private lateinit var btnCancelSelect: Button
    private lateinit var btnDelete: Button
    private lateinit var btnExport: Button
    private lateinit var buttonCard: LinearLayout

    // 数据
    private var allFiles: List<File> = emptyList()
    private var currentFiles: List<File> = emptyList()
    private var isSelectMode = false
    private val selectedFiles = mutableSetOf<File>()

    // SAF文件选择器
    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUri ->
            // 获取写入权限
            contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            // 开始导出
            exportSelectedFiles(treeUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        // 适配全面屏状态栏
        setupStatusBarPadding()

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        btnSelectAll = findViewById(R.id.btnSelectAll)
        tvFileCount = findViewById(R.id.tvFileCount)
        searchBar = findViewById(R.id.searchBar)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnSearchConfirm = findViewById(R.id.btnSearchConfirm)
        spacer = findViewById(R.id.spacer)
        btnSearch = findViewById(R.id.btnSearch)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        btnDelete = findViewById(R.id.btnDelete)
        btnExport = findViewById(R.id.btnExport)
        buttonCard = findViewById(R.id.buttonCard)

        loadAllFiles()
        updateCurrentFiles(allFiles)
        updateFileCountDisplay()

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
            if (etSearch.text.isEmpty()) {
                // 如果搜索框已经为空，点击X按钮则取消搜索
                cancelSearch()
            } else {
                // 如果搜索框有内容，则清空内容
                etSearch.text.clear()
            }
        }

        // 长按提示
        btnClearSearch.setOnLongClickListener {
            val message = if (etSearch.text.isEmpty()) {
                "取消搜索"
            } else {
                "清空搜索内容"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            true
        }

        // 搜索框文本变化监听
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 根据搜索框内容改变清除按钮的可用状态和颜色
                if (s.isNullOrEmpty()) {
                    btnClearSearch.isEnabled = true
                    btnClearSearch.alpha = 0.5f  // 半透明表示可以取消搜索
                } else {
                    btnClearSearch.isEnabled = true
                    btnClearSearch.alpha = 1.0f  // 完全不透明表示可以清空内容
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

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

        btnExport.setOnClickListener {
            if (selectedFiles.isNotEmpty()) {
                showExportFolderSelector()
            }
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
        // X按钮始终显示，但状态会根据搜索框内容自动调整
        btnClearSearch.alpha = 0.5f  // 初始状态为半透明
        // 搜索模式下显示文件数量（因为不是选择模式）
        updateFileCountDisplay()
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
        // 更新文件数量显示（显示过滤后的数量）
        updateFileCountDisplay()
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
        updateDeleteButton() // 更新删除按钮状态
        updateButtonCardVisibility()
        hideSelectUI()
    }

    private fun showSelectUI() {
        btnSelectAll.visibility = View.VISIBLE
        btnCancelSelect.visibility = View.VISIBLE
        updateButtonCardVisibility() // 显示删除和导出按钮卡片
        btnSearch.visibility = View.GONE
        searchBar.visibility = View.GONE
        spacer.visibility = View.VISIBLE   // 让全选和取消分列两侧
        updateDeleteButton() // 确保删除按钮状态正确更新
        updateFileCountDisplay() // 更新文件数量显示（隐藏）
    }

    private fun hideSelectUI() {
        btnSelectAll.visibility = View.GONE
        btnCancelSelect.visibility = View.GONE
        updateButtonCardVisibility() // 隐藏删除和导出按钮卡片
        btnSearch.visibility = View.VISIBLE
        spacer.visibility = if (searchBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateFileCountDisplay() // 更新文件数量显示（显示）
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
        // 只移除那些已经被删除的文件，保留其他选中的文件
        selectedFiles.removeAll { selectedFile ->
            selectedFile.exists().not() || !allFiles.contains(selectedFile)
        }
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
        // 无论是否在selectMode，只要selectedFiles发生变化就更新删除按钮
        updateDeleteButton()
        updateFileCountDisplay()
    }

    private fun toggleSelection(file: File) {
        val wasSelected = selectedFiles.contains(file)
        if (wasSelected) {
            selectedFiles.remove(file)
        } else {
            selectedFiles.add(file)
        }
        adapter.selectedFiles = selectedFiles
        adapter.notifyDataSetChanged()
        updateDeleteButton()
    }

    private fun updateDeleteButton() {
        val validCount = selectedFiles.size
        btnDelete.isEnabled = validCount > 0
        btnDelete.text = "删除选中"
        updateButtonCardVisibility()
    }

    private fun updateButtonCardVisibility() {
        if (isSelectMode && selectedFiles.isNotEmpty()) {
            buttonCard.visibility = View.VISIBLE
        } else {
            buttonCard.visibility = View.GONE
        }
    }

    // 显示导出文件夹选择对话框
    private fun showExportFolderSelector() {
        val alertDialog = AlertDialog.Builder(this)
            .setTitle("选择导出文件夹")
            .setMessage("选择一个文件夹来导出选中的视频文件，该文件夹将成为一个媒体库目录")
            .setPositiveButton("选择文件夹") { _, _ ->
                pickDocumentLauncher.launch(null)
            }
            .setNegativeButton("取消", null)
            .create()
        alertDialog.show()
    }

    // 导出选中的视频文件
    private fun exportSelectedFiles(treeUri: Uri) {
        if (selectedFiles.isEmpty()) return

        // 显示进度对话框
        val progress = ProgressDialog(this)
        progress.setMessage("正在导出 ${selectedFiles.size} 个文件...")
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        progress.setCancelable(false)
        progress.show()

        // 创建DocumentFile来处理目标目录
        val rootDocument = DocumentFile.fromTreeUri(this, treeUri)

        // 导出文件
        var successCount = 0
        val failedFiles = mutableListOf<String>()

        selectedFiles.forEach { file ->
            try {
                // 复制文件到目标目录
                if (copyFileToDocument(file,treeUri, rootDocument)) {
                    successCount++
                }
            } catch (e: Exception) {
                failedFiles.add(file.name ?: "未知文件名")
                android.util.Log.e("Export", "Failed to export ${file.name}: ${e.message}")
            }
        }

        // 关闭进度对话框
        progress.dismiss()

        // 显示结果提示
        val resultMessage = if (failedFiles.isEmpty()) {
            "成功导出 $successCount 个文件到系统文件夹"
        } else {
            "成功导出 $successCount 个文件，但以下文件导出失败：\n${failedFiles.joinToString("\n")}"
        }

        AlertDialog.Builder(this)
            .setTitle("导出完成")
            .setMessage(resultMessage)
            .setPositiveButton("确定") { _, _ ->
                // 清空选择并返回录像管理页面
                exitSelectMode()
                Toast.makeText(this, "导出成功！", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // 复制文件到DocumentFile目录
    private fun copyFileToDocument(sourceFile: File,treeUri: Uri, rootDocument: DocumentFile?): Boolean {
        return try {
            // 创建目标文件
            val destFile = rootDocument?.createFile("video/mp4", sourceFile.name) ?: return false

            // 打开源文件和目标文件
            val sourceInputStream = FileInputStream(sourceFile)
            val destOutputStream = contentResolver.openOutputStream(destFile.uri) ?: return false

            // 复制内容
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (sourceInputStream.read(buffer).also { bytesRead = it } != -1) {
                destOutputStream.write(buffer, 0, bytesRead)
            }

            // 关闭流
            sourceInputStream.close()
            destOutputStream.close()

            // 对于API 29+，刷新媒体库
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    // 根据URI判断相对路径
                    val path = if (treeUri.toString().contains("Android/data") ||
                                  treeUri.toString().contains("Documents") ||
                                  treeUri.toString().contains("Download")) {
                        "Download/"
                    } else if (treeUri.toString().contains("Pictures")) {
                        "Pictures/"
                    } else if (treeUri.toString().contains("DCIM")) {
                        "DCIM/"
                    } else {
                        ""
                    }
                    put(MediaStore.Video.Media.RELATIVE_PATH, path)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                contentResolver.update(destFile.uri, values, null, null)
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(destFile.uri, values, null, null)
            }

            true
        } catch (e: Exception) {
            android.util.Log.e("Export", "Error copying file: ${e.message}")
            false
        }
    }

    private fun updateFileCountDisplay() {
        val count = currentFiles.size
        tvFileCount.text = "共 $count 个文件"

        // 根据选择模式控制文件数量显示和全选按钮的显示
        if (isSelectMode) {
            tvFileCount.visibility = View.GONE  // 选择模式下隐藏文件数量
            btnSelectAll.visibility = View.VISIBLE  // 显示全选按钮
        } else {
            tvFileCount.visibility = View.VISIBLE  // 普通模式下显示文件数量
            btnSelectAll.visibility = View.GONE  // 隐藏全选按钮
        }
    }

    private fun deleteSelectedFiles() {
        val toDelete = selectedFiles.toSet()
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

    private fun setupStatusBarPadding() {
        // 获取状态栏高度并设置padding
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            val statusBarHeight = resources.getDimensionPixelSize(resourceId)
            findViewById<View>(android.R.id.content).setPadding(0, statusBarHeight, 0, 0)
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
            android.util.Log.d("RecordingsActivity", "Item clicked: ${file.name}, isSelected=${selectedFiles.contains(file)}")
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