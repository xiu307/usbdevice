package com.jiangdg.demo.utils

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private var currentAudioFile: File? = null
    private var outputStream: FileOutputStream? = null
    
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    fun startRecording(): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "Already recording")
            return false
        }

        try {
            Log.d(TAG, "Starting recording...")
            Log.d(TAG, "BUFFER_SIZE: $BUFFER_SIZE")
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed, state: ${audioRecord?.state}")
                return false
            }

            // 创建音频文件
            currentAudioFile = createAudioFile()
            Log.d(TAG, "Created audio file: ${currentAudioFile?.absolutePath}")
            
            if (currentAudioFile == null) {
                Log.e(TAG, "Failed to create audio file")
                return false
            }
            
            outputStream = FileOutputStream(currentAudioFile)
            Log.d(TAG, "Created output stream")

            audioRecord?.startRecording()
            isRecording.set(true)

            // 开始录音线程
            recordingThread = Thread(recordingRunnable)
            recordingThread?.start()

            Log.d(TAG, "Recording started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            return false
        }
    }

    fun stopRecording() {
        if (!isRecording.get()) {
            return
        }

        isRecording.set(false)

        try {
            audioRecord?.apply {
                stop()
                release()
            }
            audioRecord = null

            recordingThread?.interrupt()
            recordingThread = null

            outputStream?.close()
            outputStream = null

            Log.d(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    fun getCurrentAudioFile(): File? {
        if (currentAudioFile != null && currentAudioFile!!.exists()) {
            val fileSize = currentAudioFile!!.length()
            Log.d(TAG, "Current audio file: ${currentAudioFile!!.absolutePath}, size: $fileSize bytes")
            return currentAudioFile
        } else {
            Log.w(TAG, "Current audio file is null or doesn't exist")
            return null
        }
    }

    fun release() {
        stopRecording()
        currentAudioFile = null
    }

    private val recordingRunnable = Runnable {
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytesWritten = 0L
        var iterationCount = 0
        
        Log.d(TAG, "Recording thread started")
        
        while (isRecording.get()) {
            val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            
            if (readSize > 0) {
                try {
                    outputStream?.write(buffer, 0, readSize)
                    outputStream?.flush()
                    totalBytesWritten += readSize
                    iterationCount++
                    
                    if (iterationCount % 100 == 0) { // 每100次迭代记录一次
                        Log.d(TAG, "Recording progress: $totalBytesWritten bytes written, $iterationCount iterations")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Error writing audio data", e)
                    break
                }
            } else if (readSize == AudioRecord.ERROR_INVALID_OPERATION) {
                Log.e(TAG, "AudioRecord.ERROR_INVALID_OPERATION")
                break
            } else if (readSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "AudioRecord.ERROR_BAD_VALUE")
                break
            }
        }
        
        Log.d(TAG, "Recording thread ended. Total bytes written: $totalBytesWritten")
    }

    private fun createAudioFile(): File {
        val timestamp = System.currentTimeMillis()
        val fileName = "audio_$timestamp.pcm"
        return File(FileUtils.getCacheDir(), fileName)
    }
} 