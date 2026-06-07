package com.hpu.selfcammonitor.ui.recordings // 请根据你的实际包名修改

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.ui.video.VideoListActivity
import java.io.File

class RecordingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FolderAdapter
    private lateinit var tvFileCount: TextView
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnCancelSelect: Button
    private lateinit var btnDelete: Button
    private lateinit var btnExport: Button
    private lateinit var buttonCard: LinearLayout

    private var folderList: List<File> = emptyList()
    private var isSelectMode = false
    private val selectedFolders = mutableSetOf<File>()

    private val pickDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { exportSelectedFolders(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        tvFileCount = findViewById(R.id.tvFileCount)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnCancelSelect = findViewById(R.id.btnCancelSelect)
        btnDelete = findViewById(R.id.btnDelete)
        btnExport = findViewById(R.id.btnExport)
        buttonCard = findViewById(R.id.buttonCard)

        loadFolderList()
        setupAdapter()

        btnSelectAll.setOnClickListener {
            if (selectedFolders.size == folderList.size) {
                selectedFolders.clear()
            } else {
                selectedFolders.clear()
                selectedFolders.addAll(folderList)
            }
            adapter.selectedFolders = selectedFolders
            adapter.notifyDataSetChanged()
            adapter.onSelectionChanged?.invoke(selectedFolders.size)
            updateSelectedCount()
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
                // 普通模式：进入视频列表
                val intent = Intent(this, VideoListActivity::class.java)
                intent.putExtra("folder_path", folder.absolutePath)
                startActivity(intent)
            },
            onFolderLongClick = { folder ->
                if (!isSelectMode) {
                    enterSelectMode(folder)
                    true
                } else false
            }
        )
        // 设置选中状态变化回调，更新顶部计数
        adapter.onSelectionChanged = { count ->
            runOnUiThread {
                updateSelectedCount()
                updateDeleteButton()
            }
        }
        recyclerView.adapter = adapter
        updateFileCountDisplay()
    }

    private fun enterSelectMode(firstFolder: File) {
        isSelectMode = true
        selectedFolders.clear()
        selectedFolders.add(firstFolder)
        adapter.isSelectMode = true
        adapter.selectedFolders = selectedFolders
        adapter.onSelectionChanged?.invoke(selectedFolders.size)
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

    private fun updateDeleteButton() {
        btnDelete.isEnabled = selectedFolders.isNotEmpty()
        updateButtonCardVisibility()
    }

    private fun updateButtonCardVisibility() {
        buttonCard.visibility = if (isSelectMode && selectedFolders.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSelectUI() {
        tvFileCount.visibility = View.GONE
        btnSelectAll.visibility = View.VISIBLE
        tvSelectedCount.visibility = View.VISIBLE
        btnCancelSelect.visibility = View.VISIBLE
        updateSelectedCount()
        updateButtonCardVisibility()
    }

    private fun hideSelectUI() {
        tvFileCount.visibility = View.VISIBLE
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
        tvSelectedCount.text = "已选 ${selectedFolders.size} 项"
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
        Toast.makeText(this, "导出功能需递归实现", Toast.LENGTH_SHORT).show()
    }
}