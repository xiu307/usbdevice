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
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jiangdg.demo.databinding.ActivityEnhancedMainBinding
import com.jiangdg.ausbc.encode.audio.AudioStrategyUAC
import com.jiangdg.ausbc.encode.audio.AudioStrategySystem
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.ausbc.encode.bean.RawData
import com.jiangdg.demo.utils.FileUtils
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.usb.USBMonitor
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

class EnhancedMainActivity : AppCompatActivity(), ICameraStateCallBack {
    private lateinit var binding: ActivityEnhancedMainBinding
    private var audioStrategy: IAudioStrategy? = null
    private var isRecording = false
    private var currentAudioFile: File? = null
    private var outputStream: java.io.FileOutputStream? = null
    private var mediaPlayer: MediaPlayer? = null
    private var cameraClient: MultiCameraClient? = null
    private var currentCamera: MultiCameraClient.ICamera? = null
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
        // initCameraClient() 移到onResume中，因为onPause会停止摄像头
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
        // 创建一个1x1像素的隐藏SurfaceView用于摄像头预览
        surfaceView = SurfaceView(this)
        surfaceView?.setZOrderMediaOverlay(true) // 确保在后台
        surfaceView?.visibility = View.INVISIBLE // 设置为不可见
        surfaceView?.layoutParams = ViewGroup.LayoutParams(1, 1) // 设置为1x1像素
        
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
            // 在进入调节界面前，先关闭摄像头和音频设备
            closeCameraAndAudio()
            startActivity(Intent(this, MainActivity::class.java))
        }

        // 开始/停止录音
        binding.btnRecordAudio.setOnClickListener {
            if (isRecording) {
                stopRecording()
                binding.btnRecordAudio.isSelected = false
                binding.btnRecordAudio.text = "开始连续录音"
            } else {
                startRecording()
                binding.btnRecordAudio.isSelected = true
                binding.btnRecordAudio.text = "停止录音"
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
        // 暂时不初始化，等USB设备连接后再初始化
        // audioStrategy = AudioStrategyUAC(ctrlBlock)
        // audioStrategy?.initAudioRecord()
    }

    private fun initCameraClient() {
        // 检查是否已经初始化，避免重复初始化
        if (cameraClient != null) {
            Log.d(TAG, "摄像头客户端已存在，跳过重复初始化")
            return
        }
        
        Log.d(TAG, "初始化摄像头客户端")
        // 使用USB摄像头，与DemoFragment类似
        cameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                Log.d(TAG, "USB设备已连接: ${device.deviceName}")
                // 自动请求权限
                requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                Log.d(TAG, "USB设备已断开: ${device?.deviceName}")
                currentCamera = null
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                ctrlBlock ?: return
                Log.d(TAG, "USB设备已连接并获取控制块: ${device.deviceName}")
                
                // 检查设备类型，只对音频设备初始化音频策略
                val isAudioDevice = isAudioDevice(device)
                Log.d(TAG, "设备 ${device.deviceName} 是否为音频设备: $isAudioDevice")
                
                if (isAudioDevice) {
                    // 初始化USB音频策略
                    try {
                        audioStrategy = AudioStrategyUAC(ctrlBlock)
                        audioStrategy?.initAudioRecord()
                        Log.d(TAG, "USB音频策略初始化成功")
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "USB音频设备已连接", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "USB音频策略初始化失败: ${e.message}")
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "USB音频设备连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    // 使用系统音频策略（与MainActivity相同的模式）
                    try {
                        audioStrategy = AudioStrategySystem()
                        audioStrategy?.initAudioRecord()
                        Log.d(TAG, "系统音频策略初始化成功")
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "系统音频设备已连接", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "系统音频策略初始化失败: ${e.message}")
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "系统音频设备连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                // 创建CameraUVC实例
                currentCamera = CameraUVC(this@EnhancedMainActivity, device)
                currentCamera?.setUsbControlBlock(ctrlBlock)
                
                // 打开摄像头
                openCamera()
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                Log.d(TAG, "USB设备连接断开: ${device?.deviceName}")
                
                // 清理USB音频策略
                try {
                    if (isRecording) {
                        stopRecording()
                    }
                    audioStrategy?.releaseAudioRecord()
                    audioStrategy = null
                    Log.d(TAG, "USB音频策略已清理")
                    runOnUiThread {
                        Toast.makeText(this@EnhancedMainActivity, "USB音频设备已断开", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "清理USB音频策略失败: ${e.message}")
                }
                
                closeCamera()
            }

            override fun onCancelDev(device: UsbDevice?) {
                Log.d(TAG, "USB设备权限被取消: ${device?.deviceName}")
            }
        })
        cameraClient?.register()
    }
    
    private fun requestPermission(device: UsbDevice) {
        cameraClient?.requestPermission(device)
    }
    
    private fun isAudioDevice(device: UsbDevice): Boolean {
        // 检查设备是否为音频设备
        // USB音频设备通常具有以下特征：
        // 1. 设备类为1 (Audio)
        // 2. 或者设备名称包含"Audio"、"audio"等关键词
        // 3. 或者制造商名称包含音频相关关键词
        
        val deviceClass = device.deviceClass
        val deviceName = device.deviceName ?: ""
        val manufacturerName = device.manufacturerName ?: ""
        val productName = device.productName ?: ""
        
        Log.d(TAG, "检查设备类型: class=$deviceClass, name=$deviceName, manufacturer=$manufacturerName, product=$productName")
        
        // 检查设备类是否为音频设备 (1 = Audio)
        if (deviceClass == 1) {
            Log.d(TAG, "设备类为音频设备")
            return true
        }
        
        // 检查设备名称是否包含音频关键词
        val audioKeywords = listOf("audio", "Audio", "AUDIO", "headset", "Headset", "HEADSET", "microphone", "Microphone", "MICROPHONE")
        val allNames = "$deviceName $manufacturerName $productName".lowercase()
        
        for (keyword in audioKeywords) {
            if (allNames.contains(keyword.lowercase())) {
                Log.d(TAG, "设备名称包含音频关键词: $keyword")
                return true
            }
        }
        
        // 检查特定的音频设备厂商
        val audioManufacturers = listOf("TTGK", "ttgk", "Audio-Technica", "Sennheiser", "Shure", "Blue", "Rode")
        for (manufacturer in audioManufacturers) {
            if (manufacturerName.contains(manufacturer, ignoreCase = true)) {
                Log.d(TAG, "设备制造商为音频设备厂商: $manufacturer")
                return true
            }
        }
        
        Log.d(TAG, "设备不是音频设备")
        return false
    }
    
    private fun openCamera() {
        // 确保SurfaceView已添加到布局中
        if (surfaceView?.parent == null) {
            binding.root.addView(surfaceView)
        }
        
        // 创建摄像头请求
        val cameraRequest = CameraRequest.Builder()
            .setPreviewWidth(1280)
            .setPreviewHeight(720)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setDefaultRotateType(com.jiangdg.ausbc.render.env.RotateType.ANGLE_0)
            .setAudioSource(CameraRequest.AudioSource.SOURCE_DEV_MIC)  // 使用USB设备内置麦克风
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAspectRatioShow(true)
            .setCaptureRawImage(false)
            .setRawPreviewData(false)
            .create()
        
        // 打开摄像头
        currentCamera?.openCamera(surfaceView, cameraRequest)
        currentCamera?.setCameraStateCallBack(this)
    }
    
    private fun closeCamera() {
        currentCamera?.closeCamera()
    }
    
    private fun closeCameraAndAudio() {
        Log.d(TAG, "关闭摄像头和音频设备")
        
        // 停止录音
        if (isRecording) {
            stopRecording()
        }
        
        // 关闭摄像头
        closeCamera()
        
        // 清理音频策略
        try {
            audioStrategy?.releaseAudioRecord()
            audioStrategy = null
            Log.d(TAG, "音频策略已清理")
        } catch (e: Exception) {
            Log.e(TAG, "清理音频策略失败", e)
        }
        
        // 清理定时器
        handler.removeCallbacksAndMessages(null)
        
        // 更新UI状态
        binding.btnRecordAudio.text = "开始录音"
        binding.tvRecordingStatus.visibility = View.GONE
        
        Toast.makeText(this, "已关闭摄像头和音频设备", Toast.LENGTH_SHORT).show()
    }

    private fun startRecording() {
        Log.d(TAG, "开始录音")
        
        // 检查是否有音频设备
        if (audioStrategy == null) {
            Toast.makeText(this, "没有检测到音频设备，无法录音", Toast.LENGTH_LONG).show()
            Log.w(TAG, "没有音频设备，无法开始录音")
            return
        }
        
        try {
            // 创建音频文件
            currentAudioFile = createAudioFile()
            if (currentAudioFile == null) {
                Toast.makeText(this, "创建音频文件失败", Toast.LENGTH_SHORT).show()
                return
            }
            
            outputStream = java.io.FileOutputStream(currentAudioFile)
            
            // 启动录音（使用与MainActivity相同的模式）
            audioStrategy?.startRecording()
            isRecording = true
            binding.btnRecordAudio.text = "停止录音"
            binding.btnRecordAudio.isSelected = true
            binding.tvRecordingStatus.text = "录音中..."
            binding.tvRecordingStatus.visibility = View.VISIBLE
            
            // 延迟2秒后开始定时上传，确保录音有足够时间开始
            handler.postDelayed(recordingRunnable, 2000)
            
            // 启动录音线程（使用与MainActivity相同的PCM读取方式）
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
            binding.btnRecordAudio.text = "开始连续录音"
            binding.btnRecordAudio.isSelected = false
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
                
                // 如果文件太小（小于10KB），也跳过上传
                if (fileSize < 10240) {
                    Log.w(TAG, "音频文件太小（${fileSize} bytes），跳过上传")
                    return@launch
                }
                
                // 创建新的音频文件用于继续录音，而不停止当前录音
                val newAudioFile = createAudioFile()
                if (newAudioFile != null) {
                    // 关闭当前输出流
                    outputStream?.close()
                    outputStream = null
                    
                    // 创建新的输出流
                    outputStream = java.io.FileOutputStream(newAudioFile)
                    
                    // 更新当前音频文件引用
                    currentAudioFile = newAudioFile
                    
                    Log.d(TAG, "创建新的音频文件继续录音: ${newAudioFile.absolutePath}")
                }
                
                // 尝试上传文件（不停止录音）
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
                
            } catch (e: Exception) {
                Log.e(TAG, "上传音频数据失败", e)
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
        // 使用USB摄像头进行拍照，与DemoFragment类似
        if (currentCamera?.isCameraOpened() == true) {
            // 摄像头已打开，直接拍照
            performPhotoCapture()
        } else {
            // 摄像头未打开，提示用户
            Log.d(TAG, "USB摄像头未打开")
            runOnUiThread {
                Toast.makeText(this@EnhancedMainActivity, "USB摄像头未打开，请先连接USB摄像头", Toast.LENGTH_SHORT).show()
            }
        }
    }
    

    
    private fun performPhotoCapture() {
        currentCamera?.captureImage(object : ICaptureCallBack {
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
                            // Toast.makeText(this@EnhancedMainActivity, "真实照片已保存到: $path", Toast.LENGTH_LONG).show()
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
        val recordingStatus = if (isRecording) "正在录音" else "未录音"
        val audioStrategyStatus = if (audioStrategy?.isRecording() == true) "音频策略正常" else "音频策略异常"
        val status = "录音状态: $recordingStatus, $audioStrategyStatus"
        Toast.makeText(this, status, Toast.LENGTH_LONG).show()
        Log.d(TAG, status)
    }

    override fun onResume() {
        super.onResume()
        // 当Activity恢复时，重新初始化摄像头客户端
        Log.d(TAG, "onResume: 重新初始化摄像头客户端")
        initCameraClient()
        
        // 如果cameraClient已存在，重新注册以检测设备
        if (cameraClient != null) {
            Log.d(TAG, "onResume: 重新注册摄像头客户端以检测设备")
            cameraClient?.register()
        }
    }

    override fun onPause() {
        super.onPause()
        // 当Activity暂停时，关闭摄像头和音频设备
        closeCameraAndAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        audioStrategy?.releaseAudioRecord()
        audioStrategy = null
        releaseMediaPlayer()
        closeCamera()
        cameraClient?.unRegister()
        cameraClient?.destroy()
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
            // 如果正在播放，先停止
            if (mediaPlayer?.isPlaying == true) {
                Log.d(TAG, "停止当前播放的音频")
                releaseMediaPlayer()
            }
            
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
            
            while (isRecording) {
                try {
                    val rawData = audioStrategy?.read()
                    if (rawData != null && rawData.size > 0) {
                        outputStream?.write(rawData.data, 0, rawData.size)
                        outputStream?.flush()
                        totalBytesWritten += rawData.size
                        
                        if (totalBytesWritten % 50000 == 0L) { // 每50KB记录一次，减少日志频率
                            Log.d(TAG, "录音进度: $totalBytesWritten bytes")
                        }
                    } else {
                        // 如果没有数据，短暂休眠避免CPU占用过高
                        Thread.sleep(5) // 减少休眠时间，提高响应速度
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "录音线程错误", e)
                    // 不要立即退出，继续尝试录音
                    Thread.sleep(50) // 减少错误恢复时间
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
    
    // ICameraStateCallBack 实现
    override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                Log.d(TAG, "USB摄像头已打开")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "USB摄像头已打开", Toast.LENGTH_SHORT).show()
                }
            }
            ICameraStateCallBack.State.CLOSED -> {
                Log.d(TAG, "USB摄像头已关闭")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "USB摄像头已关闭", Toast.LENGTH_SHORT).show()
                }
            }
            ICameraStateCallBack.State.ERROR -> {
                Log.e(TAG, "USB摄像头错误: $msg")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "USB摄像头错误: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
} 