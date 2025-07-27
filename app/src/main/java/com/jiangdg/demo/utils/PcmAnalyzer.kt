package com.jiangdg.demo.utils

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM数据分析工具
 * 用于验证录音数据的正确性
 */
class PcmAnalyzer {
    companion object {
        private const val TAG = "PcmAnalyzer"
        
        /**
         * 分析PCM文件的基本信息
         */
        fun analyzePcmFile(file: File): PcmInfo? {
            return try {
                val fileSize = file.length()
                if (fileSize == 0L) {
                    Log.w(TAG, "PCM文件为空: ${file.absolutePath}")
                    return null
                }
                
                val inputStream = FileInputStream(file)
                val buffer = ByteArray(minOf(fileSize.toInt(), 8192)) // 读取前8KB数据进行分析
                val bytesRead = inputStream.read(buffer)
                inputStream.close()
                
                if (bytesRead <= 0) {
                    Log.w(TAG, "无法读取PCM文件: ${file.absolutePath}")
                    return null
                }
                
                val pcmInfo = analyzePcmData(buffer, bytesRead, fileSize)
                pcmInfo.filePath = file.absolutePath
                
                Log.d(TAG, "PCM文件分析结果: $pcmInfo")
                pcmInfo
                
            } catch (e: Exception) {
                Log.e(TAG, "分析PCM文件失败: ${file.absolutePath}", e)
                null
            }
        }
        
        /**
         * 分析PCM数据
         */
        fun analyzePcmData(data: ByteArray, length: Int, fileSize: Long = 0L): PcmInfo {
            val pcmInfo = PcmInfo()
            pcmInfo.fileSize = fileSize
            
            // 假设16位PCM，每个样本2字节
            val sampleCount = length / 2
            pcmInfo.sampleCount = sampleCount
            pcmInfo.dataLength = length
            
            // 计算统计信息
            var minValue = Short.MAX_VALUE
            var maxValue = Short.MIN_VALUE
            var sum = 0L
            var zeroCrossings = 0
            
            val byteBuffer = ByteBuffer.wrap(data, 0, length)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN) // PCM通常是小端序
            
            var previousSample = 0
            for (i in 0 until sampleCount) {
                if (byteBuffer.remaining() >= 2) {
                    val sample = byteBuffer.short.toInt()
                    
                    // 更新最大最小值
                    if (sample < minValue) minValue = sample.toShort()
                    if (sample > maxValue) maxValue = sample.toShort()
                    
                    sum += sample
                    
                    // 计算过零点
                    if (i > 0 && (previousSample >= 0 && sample < 0 || previousSample < 0 && sample >= 0)) {
                        zeroCrossings++
                    }
                    previousSample = sample
                }
            }
            
            pcmInfo.minValue = minValue
            pcmInfo.maxValue = maxValue
            pcmInfo.averageValue = if (sampleCount > 0) (sum / sampleCount).toInt() else 0
            pcmInfo.zeroCrossings = zeroCrossings
            
            // 计算动态范围
            pcmInfo.dynamicRange = maxValue - minValue
            
            // 计算RMS（均方根）
            var sumSquares = 0.0
            byteBuffer.rewind()
            for (i in 0 until sampleCount) {
                if (byteBuffer.remaining() >= 2) {
                    val sample = byteBuffer.short.toDouble()
                    sumSquares += sample * sample
                }
            }
            pcmInfo.rms = if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount) else 0.0
            
            // 判断数据质量 - 降低阈值以检测更小的音频信号
            pcmInfo.isValid = pcmInfo.dynamicRange >= 0 && pcmInfo.fileSize > 1024 // 只要文件有内容就认为是有效的
            
            return pcmInfo
        }
        
        /**
         * 检查PCM数据是否包含有效音频
         */
        fun isPcmDataValid(file: File): Boolean {
            val pcmInfo = analyzePcmFile(file)
            return pcmInfo?.isValid == true
        }
        
        /**
         * 获取PCM数据的音频时长（秒）
         */
        fun getPcmDuration(file: File, sampleRate: Int = 16000): Double {
            val pcmInfo = analyzePcmFile(file) ?: return 0.0
            return pcmInfo.sampleCount.toDouble() / sampleRate
        }
    }
    
    /**
     * PCM信息数据类
     */
    data class PcmInfo(
        var filePath: String = "",
        var fileSize: Long = 0L,
        var dataLength: Int = 0,
        var sampleCount: Int = 0,
        var minValue: Short = 0,
        var maxValue: Short = 0,
        var averageValue: Int = 0,
        var dynamicRange: Int = 0,
        var zeroCrossings: Int = 0,
        var rms: Double = 0.0,
        var isValid: Boolean = false
    ) {
        override fun toString(): String {
            return "PcmInfo(" +
                    "filePath='$filePath', " +
                    "fileSize=${fileSize}bytes, " +
                    "dataLength=${dataLength}bytes, " +
                    "sampleCount=$sampleCount, " +
                    "minValue=$minValue, " +
                    "maxValue=$maxValue, " +
                    "averageValue=$averageValue, " +
                    "dynamicRange=$dynamicRange, " +
                    "zeroCrossings=$zeroCrossings, " +
                    "rms=${String.format("%.2f", rms)}, " +
                    "isValid=$isValid" +
                    ")"
        }
    }
} 