package com.hpu.selfcammonitor.ui.recordings // 请根据你的实际包名修改

import android.app.ProgressDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hpu.selfcammonitor.R
import com.hpu.selfcammonitor.ui.video.VideoListActivity
import java.io.File
import java.io.FileInputStream

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
    private lateinit var btnBack: ImageButton
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
        // 绑定返回按钮
        btnBack = findViewById(R.id.btnBack)
        btnBack.setOnClickListener {
            if (isSelectMode) {
                exitSelectMode()
            } else {
                finish()
            }
        }

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
        btnBack.visibility = View.GONE
        tvFileCount.visibility = View.GONE
        btnSelectAll.visibility = View.VISIBLE
        tvSelectedCount.visibility = View.VISIBLE
        btnCancelSelect.visibility = View.VISIBLE
        updateSelectedCount()
        updateButtonCardVisibility()
    }

    private fun hideSelectUI() {
        btnBack.visibility = View.VISIBLE
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

    /**
     * 导出选中的文件夹（递归复制文件夹内所有 MP4 文件到用户选择的目标目录）
     */
    private fun exportSelectedFolders(treeUri: Uri) {
        if (selectedFolders.isEmpty()) return

        // 统计所有需要导出的文件（仅 MP4）
        val allFiles = selectedFolders.flatMap { folder ->
            folder.walkTopDown()
                .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                .toList()
        }
        if (allFiles.isEmpty()) {
            Toast.makeText(this, "所选文件夹中没有视频文件", Toast.LENGTH_SHORT).show()
            return
        }

        val progress = ProgressDialog(this)
        progress.setMessage("正在导出 ${allFiles.size} 个视频...")
        progress.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        progress.setCancelable(false)
        progress.show()

        val rootDocument = DocumentFile.fromTreeUri(this, treeUri)
        if (rootDocument == null) {
            progress.dismiss()
            Toast.makeText(this, "无法访问目标文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        var totalSuccess = 0
        val failedFiles = mutableListOf<String>()

        for (sourceFolder in selectedFolders) {
            // 在目标目录中创建同名子文件夹
            val targetFolder = rootDocument.findFile(sourceFolder.name) ?: rootDocument.createDirectory(sourceFolder.name)
            if (targetFolder == null) {
                // 创建失败，记录该文件夹下所有文件失败
                sourceFolder.walkTopDown()
                    .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                    .forEach { failedFiles.add("${sourceFolder.name}/${it.name}") }
                continue
            }
            // 递归复制文件夹内的视频文件，累加成功数量
            totalSuccess += copyFolderRecursively(sourceFolder, targetFolder, failedFiles)
        }

        progress.dismiss()
        val message = if (failedFiles.isEmpty()) {
            "成功导出 $totalSuccess 个文件"
        } else {
            "成功导出 $totalSuccess 个文件，失败：${failedFiles.joinToString(limit = 5)}"
        }
        AlertDialog.Builder(this)
            .setTitle("导出完成")
            .setMessage(message)
            .setPositiveButton("确定") { _, _ -> exitSelectMode() }
            .show()
    }

    /**
     * 递归复制文件夹内的 MP4 文件到目标 DocumentFile 目录，返回成功复制的文件数量
     */
    private fun copyFolderRecursively(
        sourceFolder: File,
        targetFolder: DocumentFile,
        failedFiles: MutableList<String>
    ): Int {
        var successCount = 0
        sourceFolder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                // 在目标下创建子目录
                val subTarget = targetFolder.findFile(file.name) ?: targetFolder.createDirectory(file.name)
                if (subTarget != null) {
                    successCount += copyFolderRecursively(file, subTarget, failedFiles)
                } else {
                    // 子目录创建失败，记录该子目录下所有文件失败
                    file.walkTopDown()
                        .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                        .forEach { failedFiles.add("${sourceFolder.name}/${it.name}") }
                }
            } else if (file.isFile && file.extension.equals("mp4", ignoreCase = true)) {
                if (copyFileToDocument(file, targetFolder)) {
                    successCount++
                } else {
                    failedFiles.add("${sourceFolder.name}/${file.name}")
                }
            }
        }
        return successCount
    }

    /**
     * 复制单个视频文件到指定的 DocumentFile 目录
     */
    private fun copyFileToDocument(sourceFile: File, targetDir: DocumentFile): Boolean {
        return try {
            val destFile = targetDir.createFile("video/mp4", sourceFile.name) ?: return false
            val sourceStream = FileInputStream(sourceFile)
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
            android.util.Log.e("Export", "复制失败: ${sourceFile.absolutePath}", e)
            false
        }
    }
}