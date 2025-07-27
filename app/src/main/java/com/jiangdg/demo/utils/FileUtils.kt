package com.jiangdg.demo.utils

import android.content.Context
import java.io.File

object FileUtils {
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun getCacheDir(): File {
        return context?.cacheDir ?: File("/tmp")
    }

    fun getExternalCacheDir(): File? {
        return context?.externalCacheDir
    }

    fun createFileInCache(fileName: String): File {
        return File(getCacheDir(), fileName)
    }

    fun deleteFile(file: File): Boolean {
        return try {
            if (file.exists()) {
                file.delete()
            } else {
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getFileSize(file: File): Long {
        return if (file.exists()) file.length() else 0L
    }
} 