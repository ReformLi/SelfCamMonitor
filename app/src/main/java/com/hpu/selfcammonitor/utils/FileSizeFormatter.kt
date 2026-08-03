package com.hpu.selfcammonitor.utils

/**
 * 文件大小自适应单位格式化（B / KB / MB / GB）。
 * 统一用于主界面存储占用、日期文件夹大小、视频文件大小等显示场景。
 */
object FileSizeFormatter {

    fun format(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 ->
                "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 ->
                "%.1f MB".format(bytes / (1024.0 * 1024))
            bytes >= 1024L ->
                "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
