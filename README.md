# Android USB Camera

A USB camera application for Android devices.

## Features

- USB camera support
- Camera parameter adjustment
- Real-time video streaming
- Audio recording and streaming
- Photo capture and upload
- **Media button support**: Press the media play/pause button on your device to trigger photo capture and upload

## Enhanced Features

### Audio Recording & Streaming
- Continuous PCM audio recording (16kHz, mono, 16-bit, little-endian)
- Automatic audio upload to server every 5 seconds
- Audio playback of server response URL
- Uses original project's AudioStrategySystem for recording

### Photo Upload
- Manual photo capture and upload via UI button
- **Media button trigger**: Press device's media play/pause button to take photo and upload
- Automatic glassesId generation using timestamp, device ID, and random number
- Server response handling

### Technical Details
- Audio format: PCM 16-bit, 16kHz, mono, little-endian
- Network timeouts: 60 seconds for upload operations
- Unique device identification via MD5 hash of timestamp + Android ID + random number
- Concurrent audio recording with periodic uploads
- MediaPlayer integration for audio playback

## Installation

1. Connect your Android device via USB
2. Enable USB debugging
3. Run the application

## Usage

1. Launch the enhanced interface
2. Use "开始录音" to start continuous audio recording and streaming
3. Use "拍照上传" button or **press media play/pause button** to capture and upload photos
4. Use "进入原有调节界面" to access original camera adjustment features

## Server APIs

- Audio upload: `POST http://114.55.106.4:80/gw/glasses/audio`
- Photo upload: `POST http://114.55.106.4:80/gw/glasses/picture`

Both APIs use multipart/form-data with `file` and `glassesId` parameters.
