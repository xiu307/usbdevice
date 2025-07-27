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
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.Camera1Strategy
import com.jiangdg.ausbc.camera.bean.CameraRequest
import android.hardware.usb.UsbDevice
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
    private var cameraStrategy: Camera1Strategy? = null
    private var surfaceView: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // 音频上传队列
    private val audioUploadQueue = mutableListOf<File>()
    private val maxQueueSize = 400
    
    private val recordingRunnable = object : Runnable {
        override fun run() {
            if (isRecording) {
                Log.d(TAG, "执行定时上传任务")
                uploadAudioData()
                handler.postDelayed(this, 1000) // 每1秒上传一次
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
        initCameraClient()
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
        // 创建SurfaceView用于摄像头预览
        surfaceView = SurfaceView(this)
        surfaceView?.setZOrderMediaOverlay(true) // 确保在后台
        // 让SurfaceView完全可见，以便摄像头能够正常预览
        surfaceView?.visibility = View.VISIBLE
        surfaceView?.alpha = 1.0f // 设置为完全不透明，确保预览正常
        
        // 设置SurfaceHolder回调
        surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.d(TAG, "Surface已创建")
            }
            
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.d(TAG, "Surface已改变: ${width}x${height}")
            }
            
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(TAG, "Surface已销毁")
            }
        })
        
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

    private fun initCameraClient() {
        // 使用Camera1Strategy，与DemoFragment类似
        cameraStrategy = Camera1Strategy(this)
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
                
                // 尝试上传文件
                val response = uploadAudioFile(audioFile)
                
                if (response != null) {
                    Log.d(TAG, "音频上传成功，处理队列")
                    // 上传成功后，处理队列中的文件
                    processUploadQueue()
                } else {
                    Log.d(TAG, "音频上传失败，添加到队列")
                    // 上传失败，添加到队列
                    addToUploadQueue(audioFile)
                }
                
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

    private fun uploadAudioFile(audioFile: File): String? {
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
                return responseBody
            } else {
                Log.e(TAG, "音频上传失败: ${response.code}")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "音频上传失败: ${response.code}", Toast.LENGTH_SHORT).show()
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频上传失败", e)
            runOnUiThread {
                Toast.makeText(this@EnhancedMainActivity, "音频上传失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return null
        }
    }

    private fun takePhotoAndUpload() {
        // 使用Camera1Strategy进行拍照，与DemoFragment类似
        if (cameraStrategy?.isCameraOpened() == true) {
            // 摄像头已打开，直接拍照
            performPhotoCapture()
        } else {
            // 摄像头未打开，尝试打开摄像头
            Log.d(TAG, "摄像头未打开，尝试打开摄像头")
            runOnUiThread {
                Toast.makeText(this@EnhancedMainActivity, "正在打开摄像头...", Toast.LENGTH_SHORT).show()
            }
            
            try {
                // 确保SurfaceView已添加到布局中
                if (surfaceView?.parent == null) {
                    binding.root.addView(surfaceView)
                }
                
                // 等待Surface准备就绪
                val surface = surfaceView?.holder?.surface
                val isValid = surface?.isValid
                Log.d(TAG, "检查Surface状态: surface=${surface != null}, isValid=$isValid")
                
                if (isValid != true) {
                    Log.d(TAG, "等待Surface准备就绪...")
                    // 确保SurfaceView已添加到布局中
                    if (surfaceView?.parent == null) {
                        Log.d(TAG, "SurfaceView未添加到布局，正在添加...")
                        binding.root.addView(surfaceView)
                    }
                    
                    surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            Log.d(TAG, "Surface已创建，开始启动摄像头")
                            startCameraPreview()
                        }
                        
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            Log.d(TAG, "Surface已改变: ${width}x${height}")
                        }
                        
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            Log.d(TAG, "Surface已销毁")
                        }
                    })
                    return
                }
                
                startCameraPreview()
                
            } catch (e: Exception) {
                Log.e(TAG, "打开摄像头失败", e)
                createAndUploadMockPhoto()
            }
        }
    }
    
    private fun startCameraPreview() {
        try {
            Log.d(TAG, "开始启动摄像头预览")
            
            // 检查Surface状态
            val surface = surfaceView?.holder?.surface
            val isValid = surface?.isValid
            Log.d(TAG, "启动预览前检查Surface: surface=${surface != null}, isValid=$isValid")
            
            if (isValid != true) {
                Log.e(TAG, "Surface无效，无法启动摄像头预览")
                createAndUploadMockPhoto()
                return
            }
            
            // 创建摄像头请求
            val cameraRequest = CameraRequest.Builder()
                .setFrontCamera(false)
                .setPreviewWidth(1280)  // 降低分辨率，提高兼容性
                .setPreviewHeight(720)
                .create()
            
            Log.d(TAG, "摄像头请求创建完成，开始启动预览")
            
            // 启动预览（使用SurfaceView的SurfaceHolder）
            Log.d(TAG, "开始启动摄像头预览，SurfaceView状态: visible=${surfaceView?.visibility}, alpha=${surfaceView?.alpha}")
            cameraStrategy?.startPreview(cameraRequest, surfaceView?.holder)
            
            // 等待摄像头打开
            handler.postDelayed({
                val isOpened = cameraStrategy?.isCameraOpened()
                Log.d(TAG, "检查摄像头状态: isOpened=$isOpened")
                
                if (isOpened == true) {
                    Log.d(TAG, "摄像头已打开，开始拍照")
                    performPhotoCapture()
                } else {
                    Log.e(TAG, "摄像头打开失败，使用模拟图片")
                    createAndUploadMockPhoto()
                }
            }, 2000) // 等待2秒让摄像头初始化
            
        } catch (e: Exception) {
            Log.e(TAG, "启动摄像头预览失败", e)
            createAndUploadMockPhoto()
        }
    }
    
    private fun performPhotoCapture() {
        cameraStrategy?.captureImage(object : ICaptureCallBack {
            override fun onBegin() {
                Log.d(TAG, "开始拍照")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "开始拍照", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onError(error: String?) {
                Log.e(TAG, "拍照失败: $error")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "拍照失败: $error", Toast.LENGTH_SHORT).show()
                }
                // 拍照失败时，使用模拟图片
                createAndUploadMockPhoto()
            }

            override fun onComplete(path: String?) {
                Log.d(TAG, "拍照完成: $path")
                if (path != null) {
                    val photoFile = File(path)
                    if (photoFile.exists()) {
                        // 显示照片保存路径
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "真实照片已保存到: $path", Toast.LENGTH_LONG).show()
                            Log.d(TAG, "真实照片文件大小: ${photoFile.length()} bytes")
                        }
                        // 上传照片
                        uploadPhotoFile(photoFile)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "照片文件不存在", Toast.LENGTH_SHORT).show()
                        }
                        createAndUploadMockPhoto()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@EnhancedMainActivity, "拍照路径为空", Toast.LENGTH_SHORT).show()
                    }
                    createAndUploadMockPhoto()
                }
            }
        }, null)
    }
    
    private fun createAndUploadMockPhoto() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val photoFile = createMockPhotoFile()
                if (photoFile != null) {
                    // 显示照片保存路径
                    runOnUiThread {
                        Toast.makeText(this@EnhancedMainActivity, "模拟照片已保存到: ${photoFile.absolutePath}", Toast.LENGTH_LONG).show()
                        Log.d(TAG, "模拟照片文件大小: ${photoFile.length()} bytes")
                    }
                    // 上传照片
                    uploadPhotoFile(photoFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "创建模拟照片失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EnhancedMainActivity, "创建模拟照片失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun createMockPhotoFile(): File? {
        try {
            // 创建一个更真实的测试图片文件
            val photoFile = File(cacheDir, "mock_photo_${System.currentTimeMillis()}.jpg")
            
            // 创建一个基本的JPEG文件，包含更多数据
            val jpegData = byteArrayOf(
                // JPEG SOI
                0xFF.toByte(), 0xD8.toByte(),
                // APP0 segment
                0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(),
                0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte(), // "JFIF"
                0x01.toByte(), 0x01.toByte(), // Version 1.1
                0x00.toByte(), // Units: none
                0x00.toByte(), 0x01.toByte(), // Density: 1x1
                0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x00.toByte(), // No thumbnail
                // DQT segment (Quantization table)
                0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(), 0x00.toByte(),
                // Luminance quantization table
                0x08.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte(), 0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte(),
                0x06.toByte(), 0x07.toByte(), 0x08.toByte(), 0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(),
                0x07.toByte(), 0x08.toByte(), 0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(),
                0x08.toByte(), 0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(),
                0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(),
                0x0A.toByte(), 0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x11.toByte(),
                0x0B.toByte(), 0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x11.toByte(), 0x12.toByte(),
                0x0C.toByte(), 0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(),
                // Chrominance quantization table
                0x0D.toByte(), 0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(), 0x14.toByte(),
                0x0E.toByte(), 0x0F.toByte(), 0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(), 0x14.toByte(), 0x15.toByte(),
                0x0F.toByte(), 0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(), 0x14.toByte(), 0x15.toByte(), 0x16.toByte(),
                0x10.toByte(), 0x11.toByte(), 0x12.toByte(), 0x13.toByte(), 0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(),
                0x11.toByte(), 0x12.toByte(), 0x13.toByte(), 0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(), 0x18.toByte(),
                0x12.toByte(), 0x13.toByte(), 0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(), 0x18.toByte(), 0x19.toByte(),
                0x13.toByte(), 0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(), 0x18.toByte(), 0x19.toByte(), 0x1A.toByte(),
                0x14.toByte(), 0x15.toByte(), 0x16.toByte(), 0x17.toByte(), 0x18.toByte(), 0x19.toByte(), 0x1A.toByte(), 0x1B.toByte(),
                // SOF0 segment (Start of Frame)
                0xFF.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x11.toByte(), 0x08.toByte(),
                0x00.toByte(), 0x10.toByte(), // Height: 16 pixels
                0x00.toByte(), 0x10.toByte(), // Width: 16 pixels
                0x03.toByte(), // Number of components
                0x01.toByte(), 0x11.toByte(), 0x00.toByte(), // Y component
                0x02.toByte(), 0x11.toByte(), 0x01.toByte(), // Cb component
                0x03.toByte(), 0x11.toByte(), 0x01.toByte(), // Cr component
                // DHT segment (Huffman table)
                0xFF.toByte(), 0xC4.toByte(), 0x00.toByte(), 0x1F.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x01.toByte(), 0x05.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x02.toByte(), 0x03.toByte(), 0x04.toByte(), 0x05.toByte(), 0x06.toByte(), 0x07.toByte(), 0x08.toByte(), 0x09.toByte(), 0x0A.toByte(), 0x0B.toByte(),
                // SOS segment (Start of Scan)
                0xFF.toByte(), 0xDA.toByte(), 0x00.toByte(), 0x0C.toByte(), 0x03.toByte(),
                0x01.toByte(), 0x00.toByte(), 0x02.toByte(), 0x11.toByte(), 0x03.toByte(), 0x11.toByte(), 0x00.toByte(), 0x3F.toByte(), 0x00.toByte(),
                // 一些基本的图像数据（16x16像素的简单图像）
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                // JPEG EOI
                0xFF.toByte(), 0xD9.toByte()
            )
            
            photoFile.writeBytes(jpegData)
            Log.d(TAG, "创建模拟图片文件: ${photoFile.absolutePath}, 大小: ${photoFile.length()} bytes")
            return photoFile
        } catch (e: Exception) {
            Log.e(TAG, "创建模拟图片文件失败", e)
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
        cameraStrategy?.stopPreview()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                Log.d(TAG, "检测到媒体播放/暂停按钮，触发拍照上传")
                takePhotoAndUpload()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun addToUploadQueue(audioFile: File) {
        synchronized(audioUploadQueue) {
            if (audioUploadQueue.size >= maxQueueSize) {
                Log.w(TAG, "上传队列已满（${maxQueueSize}条），丢弃最旧的文件")
                audioUploadQueue.removeAt(0) // 移除最旧的文件
            }
            audioUploadQueue.add(audioFile)
            Log.d(TAG, "文件已添加到上传队列，当前队列大小: ${audioUploadQueue.size}")
        }
    }
    
    private fun processUploadQueue() {
        lifecycleScope.launch(Dispatchers.IO) {
            synchronized(audioUploadQueue) {
                if (audioUploadQueue.isEmpty()) {
                    Log.d(TAG, "上传队列为空")
                    return@launch
                }
                
                Log.d(TAG, "开始处理上传队列，队列大小: ${audioUploadQueue.size}")
                
                val filesToProcess = audioUploadQueue.toList()
                audioUploadQueue.clear()
                
                for (file in filesToProcess) {
                    try {
                        if (file.exists()) {
                            Log.d(TAG, "处理队列中的文件: ${file.name}")
                            val response = uploadAudioFile(file)
                            if (response != null) {
                                Log.d(TAG, "队列文件上传成功: ${file.name}")
                            } else {
                                Log.e(TAG, "队列文件上传失败: ${file.name}")
                                // 重新添加到队列
                                addToUploadQueue(file)
                            }
                        } else {
                            Log.w(TAG, "队列中的文件不存在: ${file.name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理队列文件失败: ${file.name}", e)
                        // 重新添加到队列
                        addToUploadQueue(file)
                    }
                }
            }
        }
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