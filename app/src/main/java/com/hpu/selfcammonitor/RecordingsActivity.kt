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
import androidx.activity.OnBackPressedCallback
import kotlin.compareTo


class RecordingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FolderAdapter
    private lateinit var tvFileCount: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnCancelSelect: Button
    private lateinit var btnDelete: Button
    private lateinit var btnExport: Button
    private lateinit var buttonCard: LinearLayout

    private var folderList: List<File> = emptyList()
    private var isSelectMode = false
    private val selectedFolders = mutableSetOf<File>()

    private lateinit var tvSelectedCount: TextView  // 新增
    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportSelectedFolders(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        tvFileCount = findViewById(R.id.tvFileCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        btnDelete = findViewById(R.id.btnDelete)
        btnExport = findViewById(R.id.btnExport)
        buttonCard = findViewById(R.id.buttonCard)

        tvSelectedCount = findViewById(R.id.tvSelectedCount)  // 新增

        loadFolderList()
        setupAdapter()

        btnSelectAll.setOnClickListener {
            selectedFolders.clear()
            selectedFolders.addAll(folderList)
            adapter.selectedFolders = selectedFolders
            adapter.notifyDataSetChanged()
            updateDeleteButton()
        }

        btnCancelSelect.setOnClickListener { exitSelectMode() }
        btnDelete.setOnClickListener { deleteSelectedFolders() }
        btnExport.setOnClickListener { if (selectedFolders.isNotEmpty()) showExportFolderSelector() }

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

    private fun loadFolderList() {
        val dir = File(getExternalFilesDir(null), "Recordings")
        folderList = if (dir.exists()) {
            dir.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                ?.sortedByDescending { it.name } ?: emptyList()
        } else emptyList()
    }

    private fun setupAdapter() {
        adapter = FolderAdapter(folderList,
            onFolderClick = { folder ->
                if (!isSelectMode) {
                    // 进入该文件夹的视频列表页面
                    val intent = Intent(this, VideoListActivity::class.java)
                    intent.putExtra("folder_path", folder.absolutePath)
                    startActivity(intent)
                } else {
                    toggleFolderSelection(folder)
                }
            },
            onFolderLongClick = { folder ->
                if (!isSelectMode) {
                    enterSelectMode(folder)
                    true
                } else false
            }
        )
        recyclerView.adapter = adapter
        updateFileCountDisplay()
    }

    private fun enterSelectMode(firstFolder: File) {
        selectedFolders.clear()
        selectedFolders.add(firstFolder)
        isSelectMode = true
        adapter.isSelectMode = true
        adapter.selectedFolders = selectedFolders
        adapter.notifyDataSetChanged()
        showSelectUI()
        updateSelectedCount()
        updateDeleteButton()
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedFolders.clear()
        adapter.isSelectMode = false
        adapter.selectedFolders = selectedFolders
        adapter.notifyDataSetChanged()
        hideSelectUI()
    }

    private fun toggleFolderSelection(folder: File) {
        if (selectedFolders.contains(folder)) {
            selectedFolders.remove(folder)
        } else {
            selectedFolders.add(folder)
        }
        // 同步给 adapter
        adapter.selectedFolders = selectedFolders
        adapter.notifyDataSetChanged()
        updateSelectedCount()   // 关键：更新顶部计数
        updateDeleteButton()
    }

    private fun updateDeleteButton() {
        btnDelete.isEnabled = selectedFolders.isNotEmpty()
        updateButtonCardVisibility()
    }

    private fun updateButtonCardVisibility() {
        buttonCard.visibility = if (isSelectMode && selectedFolders.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSelectUI() {
        // 隐藏普通模式控件
        tvFileCount.visibility = View.GONE
        // 显示多选模式控件
        btnSelectAll.visibility = View.VISIBLE
        tvSelectedCount.visibility = View.VISIBLE
        btnCancelSelect.visibility = View.VISIBLE
        updateSelectedCount()
        updateButtonCardVisibility()
    }

    private fun hideSelectUI() {
        // 显示普通模式控件
        tvFileCount.visibility = View.VISIBLE
        // 隐藏多选模式控件
        btnSelectAll.visibility = View.GONE
        tvSelectedCount.visibility = View.GONE
        btnCancelSelect.visibility = View.GONE
        buttonCard.visibility = View.GONE
        updateFileCountDisplay()
    }

    private fun updateFileCountDisplay() {
        tvFileCount.text = "共 ${folderList.size} 个文件夹"
    }

    private fun updateSelectedCount() {
        val count = selectedFolders.size
        tvSelectedCount.text = "已选 $count 项"
        // 调试日志，可删除
        android.util.Log.d("Recordings", "选中数量: $count")
    }

    private fun deleteSelectedFolders() {
        val toDelete = selectedFolders.toSet()
        if (toDelete.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("删除 ${toDelete.size} 个文件夹及其中的所有视频？")
            .setPositiveButton("删除") { _, _ ->
                for (folder in toDelete) {
                    folder.deleteRecursively()
                }
                loadFolderList()
                exitSelectMode()
                setupAdapter()
                updateFileCountDisplay()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showExportFolderSelector() {
        AlertDialog.Builder(this)
            .setTitle("选择导出位置")
            .setMessage("将选中的 ${selectedFolders.size} 个文件夹导出到外部存储")
            .setPositiveButton("选择文件夹") { _, _ ->
                pickDocumentLauncher.launch(null)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun exportSelectedFolders(treeUri: Uri) {
        // 实现导出文件夹的代码（递归复制文件到目标目录）
        // 省略具体实现，可参考之前的 exportSelectedFiles 进行改造
        Toast.makeText(this, "导出功能需递归实现", Toast.LENGTH_SHORT).show()
    }
}

// 文件夹适配器 FolderAdapter
class FolderAdapter(
    private val folders: List<File>,
    private val onFolderClick: (File) -> Unit,
    private val onFolderLongClick: (File) -> Boolean
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    var isSelectMode = false
    var selectedFolders = mutableSetOf<File>()

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

        holder.checkBox.visibility = if (isSelectMode) View.VISIBLE else View.GONE
        holder.checkBox.isChecked = selectedFolders.contains(folder)

        holder.itemView.setOnClickListener { onFolderClick(folder) }
        holder.itemView.setOnLongClickListener { onFolderLongClick(folder) }
    }

    override fun getItemCount() = folders.size
}