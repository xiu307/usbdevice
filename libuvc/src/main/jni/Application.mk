#/*
# * UVCCamera
# * library and sample to access to UVC web camera on non-rooted Android device
# * 
# * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
# * 
# * File name: Application.mk
# * 
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# *  You may obtain a copy of the License at
# * 
# *     http://www.apache.org/licenses/LICENSE-2.0
# * 
# *  Unless required by applicable law or agreed to in writing, software
# *  distributed under the License is distributed on an "AS IS" BASIS,
# *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# *  See the License for the specific language governing permissions and
# *  limitations under the License.
# * 
# * All files in the folder are under this Apache License, Version 2.0.
# * Files in the jni/libjpeg, jni/libusb, jin/libuvc, jni/rapidjson folder may have a different license, see the respective files.
#*/

# This is just for mips, if you really needs MSA, un-comment and build with GCC.
# Note: Supporting GCC on NDK is already deprecated and GCC will be removed from NDK soon.
#NDK_TOOLCHAIN_VERSION := 4.9

APP_PLATFORM := android-14
APP_ABI :=arm64-v8a
# /*if you want x86 or x86_64, please open it*/
#APP_ABI :=arm64-v8a armeabi-v7a x86 x86_64
#APP_OPTIM := debug
APP_OPTIM := release
