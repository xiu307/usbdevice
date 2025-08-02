package com.jiangdg.demo

// Android Framework

// AndroidX

// Kotlin Coroutines

// OkHttp

// Java

// Project specific
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.encode.audio.AudioStrategySystem
import com.jiangdg.ausbc.encode.audio.AudioStrategyUAC
import com.jiangdg.ausbc.encode.audio.IAudioStrategy
import com.jiangdg.demo.databinding.ActivityEnhancedMainBinding
import com.jiangdg.demo.utils.FileUtils
import com.jiangdg.demo.utils.PcmAnalyzer
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class EnhancedMainActivity : AppCompatActivity(), ICameraStateCallBack {
    private lateinit var binding: ActivityEnhancedMainBinding
    private var audioStrategy: IAudioStrategy? = null
    private var isRecording = false
    private var isPhoneMicRecording = false
    private var currentAudioFile: File? = null
    private var outputStream: java.io.FileOutputStream? = null
    private var mediaPlayer: MediaPlayer? = null
    
    // MediaSession相关
    private var mediaSession: MediaSession? = null
    private var playbackState: PlaybackState? = null
    
    // 手机麦克风录音相关
    private var phoneMicAudioRecord: android.media.AudioRecord? = null
    private var phoneMicRecordingThread: Thread? = null
    private var phoneMicOutputStream: java.io.FileOutputStream? = null
    private var phoneMicCurrentFile: File? = null
    private var phoneMicTotalBytesWritten = 0L
    private var phoneMicUploadRunnable: Runnable? = null
    private var cameraClient: MultiCameraClient? = null
    private var currentCamera: MultiCameraClient.ICamera? = null
    private var surfaceView: SurfaceView? = null
    private val handler = Handler(Looper.getMainLooper())
    
    // 音量键相关变量
    private var lastVolumeUpTime = 0L // 记录上次音量键按下时间
    
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
        private const val DEFAULT_SERVER_URL = "http://114.55.106.4:80"
    private var serverUrl: String = DEFAULT_SERVER_URL
    private var glassesId: String = ""
    
    // SharedPreferences相关
    private lateinit var sharedPreferences: SharedPreferences
    private val PREF_NAME = "EnhancedMainActivityPrefs"
    private val KEY_SERVER_URL = "server_url"
        
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
        
        // 初始化SharedPreferences
        sharedPreferences = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        
        // 加载保存的服务器URL
        serverUrl = sharedPreferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        
        // 设置服务器URL输入框
        setupServerUrlInput()

        // 生成设备ID
        glassesId = generateGlassesId(this)
        Log.d(TAG, "生成的设备ID: $glassesId")

        // 清理旧的PCM文件
        cleanupOldPcmFiles()

        checkPermissions()
        initViews()
        initMediaSession()
        initAudioRecorder()
        // initCameraClient() 移到onResume中，因为onPause会停止摄像头
    }

    private fun initMediaSession() {
        try {
            // 创建MediaSession
            mediaSession = MediaSession(this, "EnhancedMainActivity")
            
            // 设置MediaSession回调
            mediaSession?.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    Log.d(TAG, "MediaSession: 播放按钮被按下")
                    handleMediaSessionPlay()
                }
                
                override fun onPause() {
                    Log.d(TAG, "MediaSession: 暂停按钮被按下")
                    handleMediaSessionPause()
                }
                
                override fun onStop() {
                    Log.d(TAG, "MediaSession: 停止按钮被按下")
                    handleMediaSessionStop()
                }
                
                override fun onSkipToNext() {
                    Log.d(TAG, "MediaSession: 下一曲按钮被按下")
                    handleMediaSessionNext()
                }
                
                override fun onSkipToPrevious() {
                    Log.d(TAG, "MediaSession: 上一曲按钮被按下")
                    handleMediaSessionPrevious()
                }
                
                override fun onSeekTo(pos: Long) {
                    Log.d(TAG, "MediaSession: 跳转到位置 $pos")
                    handleMediaSessionSeek(pos)
                }
            })
            
            // 设置MediaSession为活跃状态
            mediaSession?.isActive = true
            
            // 初始化播放状态
            updatePlaybackState(PlaybackState.STATE_NONE)
            
            Log.d(TAG, "MediaSession初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "MediaSession初始化失败", e)
        }
    }
    
    private fun updatePlaybackState(state: Int) {
        try {
            val stateBuilder = PlaybackState.Builder()
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(
                    PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO
                )
            
            playbackState = stateBuilder.build()
            mediaSession?.setPlaybackState(playbackState)
            
            Log.d(TAG, "播放状态已更新: $state")
        } catch (e: Exception) {
            Log.e(TAG, "更新播放状态失败", e)
        }
    }
    
    private fun handleMediaSessionPlay() {
        Log.d(TAG, "处理MediaSession播放事件")
        runOnUiThread {
            // 模拟按钮按下效果
            binding.btnRecordAudio.isPressed = true
            
            // 延迟恢复按钮状态
            handler.postDelayed({
                binding.btnRecordAudio.isPressed = false
            }, 200)
            
            // 开始录音
            if (!isRecording) {
                startRecording()
                Toast.makeText(this@EnhancedMainActivity, "媒体键开始录音", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun handleMediaSessionPause() {
        Log.d(TAG, "处理MediaSession暂停事件")
        runOnUiThread {
            // 模拟按钮按下效果
            binding.btnRecordAudio.isPressed = true
            
            // 延迟恢复按钮状态
            handler.postDelayed({
                binding.btnRecordAudio.isPressed = false
            }, 200)
            
            // 停止录音
            if (isRecording) {
                stopRecording()
                Toast.makeText(this@EnhancedMainActivity, "媒体键停止录音", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun handleMediaSessionStop() {
        Log.d(TAG, "处理MediaSession停止事件")
        runOnUiThread {
            // 停止所有录音
            if (isRecording) {
                stopRecording()
            }
            if (isPhoneMicRecording) {
                stopPhoneMicRecording()
            }
            Toast.makeText(this@EnhancedMainActivity, "媒体键停止所有录音", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleMediaSessionNext() {
        Log.d(TAG, "处理MediaSession下一曲事件")
        runOnUiThread {
            // 模拟按钮按下效果
            binding.btnTakePhoto.isPressed = true
            
            // 延迟恢复按钮状态
            handler.postDelayed({
                binding.btnTakePhoto.isPressed = false
            }, 200)
            
            // 拍照上传
            takePhotoAndUpload()
            Toast.makeText(this@EnhancedMainActivity, "媒体键拍照上传", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun handleMediaSessionPrevious() {
        Log.d(TAG, "处理MediaSession上一曲事件")
        runOnUiThread {
            // 模拟按钮按下效果
            binding.btnPhoneMicRecord.isPressed = true
            
            // 延迟恢复按钮状态
            handler.postDelayed({
                binding.btnPhoneMicRecord.isPressed = false
            }, 200)
            
            // 切换手机麦克风录音状态
            if (isPhoneMicRecording) {
                stopPhoneMicRecording()
                Toast.makeText(this@EnhancedMainActivity, "媒体键停止手机录音", Toast.LENGTH_SHORT).show()
            } else {
                startPhoneMicRecording()
                Toast.makeText(this@EnhancedMainActivity, "媒体键开始手机录音", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun handleMediaSessionSeek(position: Long) {
        Log.d(TAG, "处理MediaSession跳转事件: $position")
        // 这里可以根据需要实现跳转逻辑
        // 比如跳转到特定的录音时间点
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

    private fun setupServerUrlInput() {
        // 设置输入框的初始值
        binding.etServerUrl.setText(serverUrl)
        
        // 监听输入框变化，实时保存
        binding.etServerUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: android.text.Editable?) {
                val newUrl = s?.toString()?.trim()
                if (!newUrl.isNullOrEmpty()) {
                    serverUrl = newUrl
                    // 保存到SharedPreferences
                    sharedPreferences.edit().putString(KEY_SERVER_URL, serverUrl).apply()
                    Log.d(TAG, "服务器URL已更新: $serverUrl")
                }
            }
        })
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

        // 开始/停止录音 - 优化后的按钮处理
        binding.btnRecordAudio.setOnClickListener {
            if (isRecording) {
                stopRecording()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
            } else {
                startRecording()
                updatePlaybackState(PlaybackState.STATE_PLAYING)
            }
        }

        // 拍照上传 - 优化后的按钮处理
        binding.btnTakePhoto.setOnClickListener {
            takePhotoAndUpload()
            // 拍照时短暂显示播放状态
            updatePlaybackState(PlaybackState.STATE_PLAYING)
            handler.postDelayed({
                updatePlaybackState(if (isRecording) PlaybackState.STATE_PLAYING else PlaybackState.STATE_STOPPED)
            }, 500)
        }

        // 查看录音状态
        binding.btnAudioStatus.setOnClickListener {
            showAudioStatus()
        }

        // 手机麦克风录音 - 优化后的按钮处理
        binding.btnPhoneMicRecord.setOnClickListener {
            if (isPhoneMicRecording) {
                stopPhoneMicRecording()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
            } else {
                startPhoneMicRecording()
                updatePlaybackState(PlaybackState.STATE_PLAYING)
            }
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
            resetRecordingButtonState()
            updatePlaybackState(PlaybackState.STATE_STOPPED)
            return
        }
        
        try {
            // 创建音频文件
            currentAudioFile = createAudioFile()
            if (currentAudioFile == null) {
                Toast.makeText(this, "创建音频文件失败", Toast.LENGTH_SHORT).show()
                resetRecordingButtonState()
                updatePlaybackState(PlaybackState.STATE_STOPPED)
                return
            }
            
            outputStream = java.io.FileOutputStream(currentAudioFile)
            
            // 启动录音（使用与MainActivity相同的模式）
            audioStrategy?.startRecording()
            
            // 只有在录音启动成功后才设置按钮状态
            isRecording = true
            binding.btnRecordAudio.text = "停止录音"
            binding.btnRecordAudio.isSelected = true
            binding.tvRecordingStatus.text = "录音中..."
            binding.tvRecordingStatus.visibility = View.VISIBLE
            
            // 更新MediaSession状态
            updatePlaybackState(PlaybackState.STATE_PLAYING)
            
            // 延迟2秒后开始定时上传，确保录音有足够时间开始
            handler.postDelayed(recordingRunnable, 2000)
            
            // 启动录音线程（使用与MainActivity相同的PCM读取方式）
            startRecordingThread()
            
            Toast.makeText(this, "开始录音", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "录音启动成功")
        } catch (e: Exception) {
            Log.e(TAG, "录音启动失败", e)
            Toast.makeText(this, "录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            // 确保在异常情况下恢复按钮状态
            resetRecordingButtonState()
            updatePlaybackState(PlaybackState.STATE_STOPPED)
            // 清理可能已经创建的资源
            cleanupRecordingResources()
        }
    }

    private fun stopRecording() {
        try {
            audioStrategy?.stopRecording()
            cleanupRecordingResources()
            resetRecordingButtonState()
            
            // 更新MediaSession状态
            updatePlaybackState(PlaybackState.STATE_STOPPED)
            
            Toast.makeText(this, "录音已停止", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "录音已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败", e)
            cleanupRecordingResources()
            resetRecordingButtonState()
            updatePlaybackState(PlaybackState.STATE_STOPPED)
        }
    }
    
    private fun resetRecordingButtonState() {
        Log.d(TAG, "重置录音按钮状态")
        binding.btnRecordAudio.text = "开始连续录音"
        binding.btnRecordAudio.isSelected = false
        binding.tvRecordingStatus.text = "录音已停止"
        binding.tvRecordingStatus.visibility = View.VISIBLE
        Log.d(TAG, "录音按钮状态已重置: text=${binding.btnRecordAudio.text}, isSelected=${binding.btnRecordAudio.isSelected}")
    }
    
    private fun cleanupRecordingResources() {
        try {
            // 停止定时上传
            handler.removeCallbacks(recordingRunnable)
            
            // 关闭输出流
            outputStream?.close()
            outputStream = null
            
            // 重置录音状态
            isRecording = false
            
            Log.d(TAG, "录音资源清理完成")
        } catch (e: Exception) {
            Log.e(TAG, "清理录音资源失败", e)
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

    private fun uploadPhoneMicAudioFile(audioFile: File): String? {
        return try {
            Log.d(TAG, "开始上传手机麦克风录音文件: ${audioFile.absolutePath}")
            
            val fileSize = audioFile.length()
            if (fileSize == 0L) {
                Log.w(TAG, "手机麦克风录音文件为空，跳过上传")
                return null
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("glassesId", glassesId)
                .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/pcm".toMediaType()))
                .build()

            val request = Request.Builder()
                .url("$serverUrl/gw/glasses/audio")
                .post(requestBody)
                .build()

            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                Log.d(TAG, "手机麦克风录音上传成功")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "手机麦克风录音上传成功", Toast.LENGTH_SHORT).show()
                    // 如果响应包含URL，尝试播放音频
                    if (!responseBody.isNullOrEmpty()) {
                        Log.d(TAG, "尝试播放返回的音频URL: $responseBody")
                        playAudioFromUrl(responseBody)
                    }
                }
                return responseBody
            } else {
                Log.e(TAG, "手机麦克风录音上传失败: ${response.code}, 响应内容: $responseBody")
                runOnUiThread {
                    Toast.makeText(this@EnhancedMainActivity, "手机麦克风录音上传失败: ${response.code}", Toast.LENGTH_SHORT).show()
                }
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "手机麦克风录音上传异常", e)
            runOnUiThread {
                Toast.makeText(this@EnhancedMainActivity, "手机麦克风录音上传异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            return null
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
                .url("$serverUrl/gw/glasses/audio")
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
                    
                    // 上传成功后删除本地文件
                    try {
                        if (audioFile.exists()) {
                            val deleted = audioFile.delete()
                            Log.d(TAG, "本地音频文件删除${if (deleted) "成功" else "失败"}: ${audioFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "删除本地音频文件失败", e)
                    }
                    
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
        Log.d(TAG, "开始拍照=====")
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
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@EnhancedMainActivity, "拍照路径为空", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }, null)
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
                            .url("$serverUrl/gw/glasses/picture")
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
                        // 上传成功后删除本地文件
                        try {
                            if (photoFile.exists()) {
                                val deleted = photoFile.delete()
                                Log.d(TAG, "本地照片文件删除${if (deleted) "成功" else "失败"}: ${photoFile.absolutePath}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "删除本地照片文件失败", e)
                        }
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
        val phoneMicStatus = if (isPhoneMicRecording) "手机麦克风录音中" else "手机麦克风未录音"
        val status = "录音状态: $recordingStatus, $audioStrategyStatus, $phoneMicStatus"
        Toast.makeText(this, status, Toast.LENGTH_LONG).show()
        Log.d(TAG, status)
    }

    private fun startPhoneMicRecording() {
        if (isPhoneMicRecording) {
            Toast.makeText(this, "手机麦克风录音已在进行中", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 创建音频文件
            val audioFile = createAudioFile()
            if (audioFile == null) {
                Toast.makeText(this, "创建音频文件失败", Toast.LENGTH_SHORT).show()
                return
            }

            phoneMicCurrentFile = audioFile
            phoneMicOutputStream = java.io.FileOutputStream(audioFile)

            // 配置AudioRecord参数 - 16kHz单声道
            val sampleRate = 16000
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            if (bufferSize == android.media.AudioRecord.ERROR_BAD_VALUE || bufferSize == android.media.AudioRecord.ERROR) {
                Toast.makeText(this, "不支持的音频配置", Toast.LENGTH_SHORT).show()
                return
            }

            // 创建AudioRecord
            phoneMicAudioRecord = android.media.AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (phoneMicAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "AudioRecord初始化失败", Toast.LENGTH_SHORT).show()
                return
            }

            // 开始录音
            phoneMicAudioRecord?.startRecording()
            isPhoneMicRecording = true
            phoneMicTotalBytesWritten = 0L

            // 更新UI
            binding.btnPhoneMicRecord.text = "停止手机录音"
            binding.btnPhoneMicRecord.isSelected = true

            // 更新MediaSession状态
            updatePlaybackState(PlaybackState.STATE_PLAYING)

            // 启动录音线程
            startPhoneMicRecordingThread()

            // 启动定时上传任务
            startPhoneMicUploadTask()

            Toast.makeText(this, "手机麦克风录音已开始", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "手机麦克风录音开始: ${audioFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "手机麦克风录音启动失败", e)
            Toast.makeText(this, "手机麦克风录音启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
            cleanupPhoneMicResources()
            updatePlaybackState(PlaybackState.STATE_STOPPED)
        }
    }

    private fun stopPhoneMicRecording() {
        if (!isPhoneMicRecording) {
            Toast.makeText(this, "手机麦克风录音未在进行中", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // 停止录音
            phoneMicAudioRecord?.stop()
            phoneMicAudioRecord?.release()
            phoneMicAudioRecord = null

            // 停止录音线程
            phoneMicRecordingThread?.interrupt()
            phoneMicRecordingThread = null

            // 关闭输出流
            phoneMicOutputStream?.close()
            phoneMicOutputStream = null

            // 重置状态
            isPhoneMicRecording = false

            // 更新UI
            binding.btnPhoneMicRecord.text = "手机麦克风录音"
            binding.btnPhoneMicRecord.isSelected = false

            // 更新MediaSession状态
            updatePlaybackState(PlaybackState.STATE_STOPPED)

            Toast.makeText(this, "手机麦克风录音已停止", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "手机麦克风录音已停止")

        } catch (e: Exception) {
            Log.e(TAG, "停止手机麦克风录音失败", e)
            Toast.makeText(this, "停止手机麦克风录音失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            cleanupPhoneMicResources()
            updatePlaybackState(PlaybackState.STATE_STOPPED)
        }
    }

    private fun startPhoneMicRecordingThread() {
        phoneMicRecordingThread = Thread {
            val buffer = ByteArray(4096)
            val shortBuffer = ShortArray(buffer.size / 2)

            while (isPhoneMicRecording && !Thread.currentThread().isInterrupted) {
                try {
                    val bytesRead = phoneMicAudioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
                    if (bytesRead > 0) {
                        // 将Short数组转换为小端序字节数组
                        val byteBuffer = ByteBuffer.allocate(bytesRead * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until bytesRead) {
                            byteBuffer.putShort(shortBuffer[i])
                        }
                        
                        phoneMicOutputStream?.write(byteBuffer.array(), 0, byteBuffer.array().size)
                        phoneMicOutputStream?.flush()
                        phoneMicTotalBytesWritten += byteBuffer.array().size

                        if (phoneMicTotalBytesWritten % 50000 == 0L) {
                            Log.d(TAG, "手机麦克风录音进度: $phoneMicTotalBytesWritten bytes")
                        }
                    } else {
                        Thread.sleep(5)
                    }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        Log.e(TAG, "手机麦克风录音线程错误", e)
                        Thread.sleep(50)
                    }
                }
            }
            Log.d(TAG, "手机麦克风录音线程结束")
        }
        phoneMicRecordingThread?.start()
    }

    private fun startPhoneMicUploadTask() {
        phoneMicUploadRunnable = object : Runnable {
            override fun run() {
                if (isPhoneMicRecording) {
                    Log.d(TAG, "执行手机麦克风定时上传任务")
                    uploadPhoneMicAudioData()
                    handler.postDelayed(this, 1000) // 每1秒上传一次
                }
            }
        }
        handler.postDelayed(phoneMicUploadRunnable!!, 1000)
    }

    private fun uploadPhoneMicAudioData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val currentFile = phoneMicCurrentFile
                if (currentFile == null || !currentFile.exists()) {
                    Log.w(TAG, "手机麦克风录音文件不存在，跳过上传")
                    return@launch
                }

                val fileSize = currentFile.length()
                if (fileSize == 0L) {
                    Log.w(TAG, "手机麦克风录音文件为空，跳过上传")
                    return@launch
                }

                // 如果文件太小（小于10KB），也跳过上传
                if (fileSize < 10240) {
                    Log.w(TAG, "手机麦克风录音文件太小（${fileSize} bytes），跳过上传")
                    return@launch
                }

                Log.d(TAG, "开始上传手机麦克风录音数据: ${currentFile.absolutePath}, 大小: $fileSize bytes")

                // 验证PCM数据正确性
                val pcmInfo = PcmAnalyzer.analyzePcmFile(currentFile)
                if (pcmInfo != null) {
                    Log.d(TAG, "PCM数据分析结果: $pcmInfo")
                    
                    if (!pcmInfo.isValid) {
                        Log.w(TAG, "PCM数据无效，跳过上传")
                        return@launch
                    }
                    
                    val duration = PcmAnalyzer.getPcmDuration(currentFile, 16000)
                    Log.d(TAG, "PCM音频时长: ${String.format("%.2f", duration)}秒")
                } else {
                    Log.w(TAG, "无法分析PCM数据，跳过上传")
                    return@launch
                }

                // 创建临时文件副本用于上传，避免上传过程中文件被修改
                val tempFile = File(currentFile.parentFile, "temp_${currentFile.name}")
                currentFile.copyTo(tempFile, overwrite = true)

                // 上传临时文件
                val responseBody = uploadPhoneMicAudioFile(tempFile)
                if (responseBody != null) {
                    Log.d(TAG, "手机麦克风录音数据上传成功")
                    
                    // 上传成功后删除原文件和临时文件，创建新文件继续录音
                    try {
                        // 删除原始文件
                        if (currentFile.exists()) {
                            currentFile.delete()
                            Log.d(TAG, "删除已上传的手机麦克风录音文件: ${currentFile.absolutePath}")
                        }
                        
                        // 删除临时文件
                        if (tempFile.exists()) {
                            tempFile.delete()
                            Log.d(TAG, "删除临时文件: ${tempFile.absolutePath}")
                        }
                        
                        // 创建新的音频文件继续录音
                        val newAudioFile = createAudioFile()
                        if (newAudioFile != null) {
                            phoneMicOutputStream?.close()
                            phoneMicOutputStream = null
                            phoneMicOutputStream = java.io.FileOutputStream(newAudioFile)
                            phoneMicCurrentFile = newAudioFile
                            phoneMicTotalBytesWritten = 0L
                            Log.d(TAG, "创建新的手机麦克风录音文件继续录音: ${newAudioFile.absolutePath}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "创建新录音文件失败", e)
                    }
                } else {
                    Log.e(TAG, "手机麦克风录音数据上传失败")
                    // 上传失败时删除临时文件
                    if (tempFile.exists()) {
                        tempFile.delete()
                        Log.d(TAG, "删除上传失败的临时文件: ${tempFile.absolutePath}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "手机麦克风录音数据上传异常", e)
            }
        }
    }

    private fun cleanupOldPcmFiles() {
        try {
            val cacheDir = File(cacheDir, "audio")
            if (cacheDir.exists()) {
                val pcmFiles = cacheDir.listFiles { file ->
                    file.name.endsWith(".pcm") && (file.name.startsWith("audio_") || file.name.startsWith("temp_"))
                }
                
                pcmFiles?.forEach { file ->
                    try {
                        if (file.delete()) {
                            Log.d(TAG, "清理旧PCM文件: ${file.name}")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "删除旧PCM文件失败: ${file.name}", e)
                    }
                }
                
                Log.d(TAG, "清理了 ${pcmFiles?.size ?: 0} 个旧PCM文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清理旧PCM文件失败", e)
        }
    }

    private fun cleanupPhoneMicResources() {
        try {
            // 停止定时上传任务
            phoneMicUploadRunnable?.let { handler.removeCallbacks(it) }
            phoneMicUploadRunnable = null

            phoneMicAudioRecord?.stop()
            phoneMicAudioRecord?.release()
            phoneMicAudioRecord = null

            phoneMicRecordingThread?.interrupt()
            phoneMicRecordingThread = null

            phoneMicOutputStream?.close()
            phoneMicOutputStream = null

            isPhoneMicRecording = false
            phoneMicTotalBytesWritten = 0L
            binding.btnPhoneMicRecord.text = "手机麦克风录音"
            binding.btnPhoneMicRecord.isSelected = false

            // 更新MediaSession状态
            updatePlaybackState(PlaybackState.STATE_STOPPED)

            Log.d(TAG, "手机麦克风录音资源清理完成")
        } catch (e: Exception) {
            Log.e(TAG, "清理手机麦克风录音资源失败", e)
        }
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
        
        // 重新激活MediaSession
        mediaSession?.isActive = true
        Log.d(TAG, "MediaSession已重新激活")
    }

    override fun onPause() {
        super.onPause()
        // 当Activity暂停时，关闭摄像头和音频设备
        closeCameraAndAudio()
        
        // 暂停MediaSession
        mediaSession?.isActive = false
        Log.d(TAG, "MediaSession已暂停")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        stopPhoneMicRecording()
        audioStrategy?.releaseAudioRecord()
        audioStrategy = null
        releaseMediaPlayer()
        closeCamera()
        cameraClient?.unRegister()
        cameraClient?.destroy()
        
        // 释放MediaSession
        try {
            mediaSession?.release()
            mediaSession = null
            Log.d(TAG, "MediaSession已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放MediaSession失败", e)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                Log.d(TAG, "检测到媒体播放/暂停按钮")
                // 让MediaSession处理播放/暂停事件
                return false // 不消费事件，让MediaSession处理
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                Log.d(TAG, "检测到媒体播放按钮")
                handleMediaSessionPlay()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                Log.d(TAG, "检测到媒体暂停按钮")
                handleMediaSessionPause()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                Log.d(TAG, "检测到媒体停止按钮")
                handleMediaSessionStop()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                Log.d(TAG, "检测到媒体下一曲按钮")
                handleMediaSessionNext()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                Log.d(TAG, "检测到媒体上一曲按钮")
                handleMediaSessionPrevious()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                val currentTime = System.currentTimeMillis()
                
                // 防止重复触发，如果距离上次按下时间太短，忽略
                if (currentTime - lastVolumeUpTime < 100) {
                    Log.d(TAG, "音量加键按下间隔太短，忽略")
                    return true
                }
                lastVolumeUpTime = currentTime
                
                if (!isRecording) {
                    Log.d(TAG, "音量加键按下，开始录制10秒音频")
                    runOnUiThread {
                        // 模拟按钮按下效果
                        binding.btnRecordAudio.isPressed = true
                        
                        // 延迟恢复按钮状态
                        handler.postDelayed({
                            binding.btnRecordAudio.isPressed = false
                        }, 200) // 200毫秒后恢复
                        
                        // 开始录音
                        startRecording()
                        updatePlaybackState(PlaybackState.STATE_PLAYING)
                        Toast.makeText(this@EnhancedMainActivity, "音量加键开始录制10秒音频", Toast.LENGTH_SHORT).show()
                        
                        // 10秒后自动停止录音
                        handler.postDelayed({
                            if (isRecording) {
                                Log.d(TAG, "10秒录制时间到，自动停止录音")
                                stopRecording()
                                updatePlaybackState(PlaybackState.STATE_STOPPED)
                                Toast.makeText(this@EnhancedMainActivity, "10秒录制完成，已自动停止", Toast.LENGTH_SHORT).show()
                            }
                        }, 10000) // 10秒后自动停止
                    }
                }
                return true // 消费事件，防止系统音量调节
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
                                // 文件已在uploadAudioFile中删除，这里不需要额外处理
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
            var consecutiveErrors = 0
            val maxConsecutiveErrors = 10 // 连续错误次数阈值
            
            while (isRecording) {
                try {
                    val rawData = audioStrategy?.read()
                    if (rawData != null && rawData.size > 0) {
                        outputStream?.write(rawData.data, 0, rawData.size)
                        outputStream?.flush()
                        totalBytesWritten += rawData.size
                        consecutiveErrors = 0 // 重置错误计数
                        
                        if (totalBytesWritten % 50000 == 0L) { // 每50KB记录一次，减少日志频率
                            Log.d(TAG, "录音进度: $totalBytesWritten bytes")
                        }
                    } else {
                        // 如果没有数据，短暂休眠避免CPU占用过高
                        Thread.sleep(5) // 减少休眠时间，提高响应速度
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "录音线程错误", e)
                    consecutiveErrors++
                    
                    // 如果连续错误次数过多，停止录音
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        Log.e(TAG, "录音线程连续错误次数过多，停止录音")
                        runOnUiThread {
                            Toast.makeText(this@EnhancedMainActivity, "录音出现错误，已自动停止", Toast.LENGTH_SHORT).show()
                            stopRecording()
                        }
                        break
                    }
                    
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