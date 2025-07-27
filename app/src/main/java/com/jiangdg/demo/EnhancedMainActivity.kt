package com.jiangdg.demo

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jiangdg.demo.databinding.ActivityEnhancedMainBinding
import com.jiangdg.ausbc.encode.audio.AudioStrategySystem
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.ausbc.encode.bean.RawData
import com.jiangdg.demo.utils.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class EnhancedMainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEnhancedMainBinding
    private var audioStrategy: IAudioStrategy? = null
    private var isRecording = false
    private var currentAudioFile: File? = null
    private var outputStream: java.io.FileOutputStream? = null
    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val recordingRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                Log.d(TAG, "执行定时上传任务")
                uploadAudioData()
                handler.postDelayed(this, 5000) // 每5秒上传一次
            }
        }
    }

    companion object {
        private const val TAG = "EnhancedMainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val SERVER_URL = "http://114.55.106.4:80"
        
        // 生成唯一的glassesId
        fun generateGlassesId(context: Context): String {
            val timestamp = System.currentTimeMillis()
            val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
            val randomNum = (Math.random() * 1000000).toInt()
            
            val combinedString = "${timestamp}_${deviceId}_${randomNum}"
            return generateMD5(combinedString)
        }
        
        private fun generateMD5(input: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val digest = md.digest(input.toByteArray())
                digest.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                // 如果MD5生成失败，返回原始字符串的哈希值
                input.hashCode().toString()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnhancedMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 初始化FileUtils
        FileUtils.init(this)

        checkPermissions()
        initViews()
        initAudioRecorder()
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.CAMERA
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSION_REQUEST_CODE)
        }
    }

    private fun initViews() {
        // 进入原有调节界面
        binding.btnOriginalCamera.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 开始/停止录音
        binding.btnRecordAudio.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }

        // 拍照上传
        binding.btnTakePhoto.setOnClickListener {
            takePhotoAndUpload()
        }

        // 查看录音状态
        binding.btnAudioStatus.setOnClickListener {
            showAudioStatus()
        }
    }

    private fun initAudioRecorder() {
        audioStrategy = AudioStrategySystem()
        audioStrategy?.initAudioRecord()
    }

    private fun startRecording() {
        Log.d(TAG, "开始录音")
        try {
            // 创建音频文件
            currentAudioFile = createAudioFile()
            if (currentAudioFile == null) {
                Toast.makeText(this, "创建音频文件失败", Toast.LENGTH_SHORT).show()
                return
            }
            
            outputStream = java.io.FileOutputStream(currentAudioFile)
            
            // 启动录音
            audioStrategy?.startRecording()
            isRecording = true
            binding.btnRecordAudio.text = "停止录音"
            binding.tvRecordingStatus.text = "录音中..."
            binding.tvRecordingStatus.visibility = View.VISIBLE
            
            // 延迟2秒后开始定时上传，确保录音有足够时间开始
            handler.postDelayed(recordingRunnable, 2000)
            
            // 启动录音线程
            startRecordingThread()
            
            Toast.makeText(this, "开始录音", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "录音启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败", e)
            Toast.makeText(this, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        try {
            audioStrategy?.stopRecording()
            isRecording = false
            binding.btnRecordAudio.text = "开始录音"
            binding.tvRecordingStatus.text = "录音已停止"
            
            // 停止定时上传
            handler.removeCallbacks(recordingRunnable)
            
            // 关闭输出流
            outputStream?.close()
            outputStream = null
            
            Toast.makeText(this, "录音已停止", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "录音已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
        }
    }

    private fun uploadAudioData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val audioFile = currentAudioFile
                Log.d(TAG, "准备上传音频文件: ${audioFile?.absolutePath}, 存在: ${audioFile?.exists()}")
                
                if (audioFile == null) {
                    Log.w(TAG, "音频文件为null")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EnhancedMainActivity, "音频文件为null", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                if (!audioFile.exists()) {
                    Log.w(TAG, "音频文件不存在")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@EnhancedMainActivity, "音频文件不存在", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val fileSize = audioFile.length()
                Log.d(TAG, "开始上传音频文件，大小: $fileSize bytes")
                
                if (fileSize == 0L) {
                    Log.w(TAG, "音频文件大小为0，跳过上传")
                    return@launch
                }
                
                // 如果文件太小（小于1KB），也跳过上传
                if (fileSize < 1024) {
                    Log.w(TAG, "音频文件太小（${fileSize} bytes），跳过上传")
                    return@launch
                }
                
                // 暂停录音线程，避免文件被修改
                Log.d(TAG, "暂停录音线程进行上传")
                audioStrategy?.stopRecording()
                
                // 等待一小段时间确保文件写入完成
                delay(100)
                
                // 上传文件
                uploadAudioFile(audioFile)
                
                // 重新开始录音
                Log.d(TAG, "重新开始录音")
                audioStrategy?.startRecording()
                
            } catch (e: Exception) {
                Log.e(TAG, "上传音频数据失败", e)
                // 确保录音重新开始
                audioStrategy?.startRecording()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EnhancedMainActivity, "音频上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun uploadAudioFile(audioFile: File) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/pcm".toMediaType()))
                .addFormDataPart("glassesId", generateGlassesId(this@EnhancedMainActivity))
                .build()

            val request = Request.Builder()
                .url("$SERVER_URL/gw/glasses/audio")
                .post(requestBody)
                .build()

            // 使用同步调用
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            Log.d(TAG, "音频上传响应: $responseBody")
            
            if (response.isSuccessful) {
                Log.d(TAG, "音频上传成功")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "音频上传成功", Toast.LENGTH_SHORT).show()
                    
                    // 如果响应包含URL，尝试播放音频
                    if (!responseBody.isNullOrEmpty()) {
                        Log.d(TAG, "尝试播放返回的音频URL: $responseBody")
                        playAudioFromUrl(responseBody)
                    }
                }
            } else {
                Log.e(TAG, "音频上传失败: ${response.code}")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "音频上传失败: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频上传失败", e)
            runOnUiThread {
                Toast.makeText(this@EnhancedMainActivity, "音频上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun takePhotoAndUpload() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 这里需要从摄像头获取图片
                // 暂时使用模拟的图片文件
                val photoFile = createMockPhotoFile()
                if (photoFile != null) {
                    uploadPhotoFile(photoFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "拍照上传失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EnhancedMainActivity, "拍照上传失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createMockPhotoFile(): File? {
        try {
            // 创建一个简单的测试图片文件
            val photoFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
            
            // 创建一个简单的JPEG文件头
            val jpegHeader = byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), // JPEG SOI + APP0
                0x00.toByte(), 0x10.toByte(), // Length
                0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), // "JFIF"
                0x00.toByte(), // Null terminator
                0x01.toByte(), 0x01.toByte(), // Version 1.1
                0x00.toByte(), // Units: none
                0x00.toByte(), 0x01.toByte(), // Density: 1x1
                0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x00.toByte()  // No thumbnail
            )
            
            photoFile.writeBytes(jpegHeader)
            Log.d(TAG, "创建测试图片文件: ${photoFile.absolutePath}")
            return photoFile
        } catch (e: Exception) {
            Log.e(TAG, "创建测试图片文件失败", e)
            return null
        }
    }

    private fun uploadPhotoFile(photoFile: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

                    val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", photoFile.name, photoFile.asRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("glassesId", generateGlassesId(this@EnhancedMainActivity))
                .build()

        val request = Request.Builder()
            .url("$SERVER_URL/gw/glasses/picture")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "图片上传失败", e)
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "图片上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "图片上传响应: $responseBody")
                runOnUiThread {
                    if (response.isSuccessful) {
                        Toast.makeText(this@EnhancedMainActivity, "图片上传成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@EnhancedMainActivity, "图片上传失败: ${response.code}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun showAudioStatus() {
        val status = if (isRecording) "正在录音" else "未录音"
        Toast.makeText(this, "录音状态: $status", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        audioStrategy?.releaseAudioRecord()
        releaseMediaPlayer()
    }
    
    private fun playAudioFromUrl(url: String) {
        try {
            // 释放之前的MediaPlayer
            releaseMediaPlayer()
            
            // 创建新的MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener {
                    Log.d(TAG, "音频准备完成，开始播放")
                    start()
                    Toast.makeText(this@EnhancedMainActivity, "开始播放音频", Toast.LENGTH_SHORT).show()
                }
                setOnCompletionListener {
                    Log.d(TAG, "音频播放完成")
                    Toast.makeText(this@EnhancedMainActivity, "音频播放完成", Toast.LENGTH_SHORT).show()
                    releaseMediaPlayer()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "音频播放错误: what=$what, extra=$extra")
                    Toast.makeText(this@EnhancedMainActivity, "音频播放失败", Toast.LENGTH_SHORT).show()
                    releaseMediaPlayer()
                    true
                }
                prepareAsync()
            }
            
            Log.d(TAG, "开始准备播放音频: $url")
        } catch (e: Exception) {
            Log.e(TAG, "播放音频失败", e)
            Toast.makeText(this, "播放音频失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "释放MediaPlayer失败", e)
        }
    }
    
    private fun startRecordingThread() {
        Thread {
            Log.d(TAG, "录音线程启动")
            var totalBytesWritten = 0L
            
            while (isRecording && audioStrategy?.isRecording() == true) {
                try {
                    val rawData = audioStrategy?.read()
                    if (rawData != null && rawData.size > 0) {
                        outputStream?.write(rawData.data, 0, rawData.size)
                        outputStream?.flush()
                        totalBytesWritten += rawData.size
                        
                        if (totalBytesWritten % 10000 == 0L) { // 每10KB记录一次
                            Log.d(TAG, "录音进度: $totalBytesWritten bytes")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "录音线程错误", e)
                    break
                }
            }
            
            Log.d(TAG, "录音线程结束，总共写入: $totalBytesWritten bytes")
        }.start()
    }
    
    private fun createAudioFile(): File? {
        return try {
            val timestamp = System.currentTimeMillis()
            val fileName = "audio_$timestamp.pcm"
            val file = File(FileUtils.getCacheDir(), fileName)
            Log.d(TAG, "创建音频文件: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "创建音频文件失败", e)
            null
        }
    }
} 