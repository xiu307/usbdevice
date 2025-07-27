# 手机麦克风录音PCM数据测试指南

## 功能概述

本项目实现了手机麦克风持续录音功能，具有以下特点：

- ✅ **持续录音**：使用`AudioRecord`持续录制PCM音频数据
- ✅ **每1秒上传**：通过定时器每1秒执行一次上传任务
- ✅ **上传成功后删除文件**：确保本地存储空间不被占用
- ✅ **PCM数据验证**：上传前验证PCM数据的正确性
- ✅ **创建新文件继续录音**：上传成功后创建新的音频文件继续录音

## 技术实现

### 1. 录音配置
```kotlin
// 音频参数配置
val sampleRate = 16000        // 采样率：16kHz
val channelConfig = AudioFormat.CHANNEL_IN_MONO  // 单声道
val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16位PCM
```

### 2. 录音流程
1. 创建音频文件
2. 启动`AudioRecord`录音
3. 启动录音线程持续写入PCM数据
4. 启动定时上传任务（每1秒）
5. 上传时创建临时文件副本
6. 验证PCM数据正确性
7. 上传成功后删除原文件，创建新文件继续录音

### 3. PCM数据验证
使用`PcmAnalyzer`类验证PCM数据：
- 检查文件大小
- 分析音频样本统计信息
- 计算动态范围、过零点数、RMS值
- 判断数据是否包含有效音频

## 测试方法

### 1. 安装APK
```bash
# 编译项目
export JAVA_HOME=/Users/administrator/Library/Java/JavaVirtualMachines/corretto-11.0.27/Contents/Home
./gradlew :app:assembleDebug

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. 运行测试
1. 启动应用
2. 点击"手机麦克风录音"按钮开始录音
3. 对着手机麦克风说话或播放音频
4. 观察日志输出，确认：
   - 录音线程正常运行
   - 每1秒执行上传任务
   - PCM数据验证通过
   - 文件上传成功并删除

### 3. 日志监控
使用以下命令监控日志：
```bash
adb logcat | grep -E "(EnhancedMainActivity|PcmAnalyzer)"
```

关键日志信息：
```
# 录音开始
手机麦克风录音开始: /path/to/audio_file.pcm

# 录音进度
手机麦克风录音进度: 50000 bytes

# PCM数据分析
PCM数据分析结果: PcmInfo(fileSize=32000bytes, sampleCount=16000, dynamicRange=1234, isValid=true)
PCM音频时长: 1.00秒

# 上传过程
开始上传手机麦克风录音数据: /path/to/audio_file.pcm, 大小: 32000 bytes
手机麦克风录音上传成功
删除已上传的手机麦克风录音文件: /path/to/audio_file.pcm
创建新的手机麦克风录音文件继续录音: /path/to/new_audio_file.pcm
```

### 4. PCM数据质量检查

#### 有效PCM数据的特征：
- **动态范围 > 0**：音频有变化
- **过零点数 > 0**：音频有波动
- **RMS值合理**：音频强度适中
- **文件大小 > 10KB**：避免上传过小文件

#### 无效PCM数据的特征：
- 动态范围为0（静音）
- 过零点数为0（直流信号）
- 文件过小（< 10KB）

### 5. 服务器端验证
确保服务器能正确接收和处理PCM数据：
- 检查HTTP响应状态码
- 验证接收到的文件大小
- 测试PCM数据播放功能

## 故障排除

### 1. 录音权限问题
确保应用已获得录音权限：
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

### 2. 文件权限问题
确保应用有写入外部存储的权限：
```xml
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

### 3. 网络连接问题
检查服务器URL配置和网络连接状态

### 4. PCM数据异常
如果PCM数据验证失败，检查：
- 麦克风硬件是否正常
- 录音参数配置是否正确
- 音频源是否有声音

## 性能优化

### 1. 内存管理
- 使用临时文件避免内存占用过大
- 及时删除已上传的文件
- 控制录音缓冲区大小

### 2. 网络优化
- 设置合理的超时时间
- 使用压缩传输（如需要）
- 实现断点续传（如需要）

### 3. 电池优化
- 避免频繁的文件I/O操作
- 合理设置上传间隔
- 在后台时暂停录音（如需要）

## 扩展功能

### 1. 音频格式转换
可以添加MP3、AAC等格式的编码功能

### 2. 音频预处理
可以添加降噪、增益控制等音频处理功能

### 3. 实时音频流
可以实现WebRTC等实时音频传输功能

## 总结

本实现提供了完整的手机麦克风录音解决方案，包括：
- 持续录音功能
- 定时上传机制
- PCM数据验证
- 文件管理优化
- 错误处理机制

通过PCM数据分析，可以确保上传的音频数据质量，避免上传无效的录音文件。 