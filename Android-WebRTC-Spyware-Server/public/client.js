const socket = io('http://192.168.29.10:3000');
const videoEl = document.getElementById('remoteVideo');
const screenEl = document.getElementById('screenVideo');
const statusDiv = document.getElementById('status');
let peer;
let myId;
let androidClientId;
let videoTrackCount = 0;

const config = {
  iceServers: [
    { urls: 'stun:stun.l.google.com:19302' },
    { urls: 'turn:numb.viagenie.ca', username: 'your@email.com', credential: 'yourpassword' }
  ]
};

function updateStatus(message) {
  console.log(message);
  statusDiv.textContent = message;
}

socket.on('connect', () => {
  updateStatus('Connected to signaling server');
});

socket.on('id', id => {
  myId = id;
  console.log('My socket ID:', myId);
  socket.emit('identify', 'web');
  socket.emit('web-client-ready', myId);
  updateStatus('Announced readiness to receive stream');
});

socket.on('android-client-ready', id => {
  androidClientId = id;
  console.log('Android client ready:', id);
  updateStatus('Android client connected');
});

socket.on('signal', async (data) => {
  console.log('Received signal:', data);
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
        mid: event.transceiver.mid,
        label: event.track.label,
        streamId: event.streams[0]?.id
      });
      if (event.track.kind === 'video') {
        videoTrackCount++;
        if ((event.transceiver.mid === 'video' || event.track.label === 'video') || videoTrackCount === 1) {
          if (!videoEl.srcObject) {
            videoEl.srcObject = event.streams[0];
            videoEl.onloadedmetadata = () => {
              console.log('Camera video metadata loaded, playing stream');
              videoEl.play().catch(e => console.error('Camera play error:', e));
              updateStatus('Playing Android camera, screen, and audio');
              document.body.style.backgroundColor = '#e8f5e8';
            };
          }
        } else if ((event.transceiver.mid === 'screen' || event.track.label === 'screen') || videoTrackCount === 2) {
          if (!screenEl.srcObject) {
            screenEl.srcObject = event.streams[0];
            screenEl.onloadedmetadata = () => {
              console.log('Screen video metadata loaded, playing stream');
              screenEl.play().catch(e => console.error('Screen play error:', e));
              updateStatus('Playing Android camera, screen, and audio');
              document.body.style.backgroundColor = '#e8f5e8';
            };
          }
        } else {
          console.warn('Unexpected video track:', event.transceiver.mid, event.track.label, 'count:', videoTrackCount);
        }
      } else if (event.track.kind === 'audio') {
        if (!videoEl.srcObject) {
          videoEl.srcObject = event.streams[0];
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
      console.log('Adding ICE candidate:', signal.candidate);
      await peer.addIceCandidate(new RTCIceCandidate(signal.candidate));
    }
  } catch (err) {
    console.error('Error handling signal:', err);
    updateStatus('Error: ' + err.message);
  }
});

socket.on('android-client-disconnected', () => {
  updateStatus('Android client disconnected');
  if (peer) {
    peer.close();
    peer = null;
    videoEl.srcObject = null;
    screenEl.srcObject = null;
    videoTrackCount = 0;
    document.body.style.backgroundColor = '#f4f4f4';
  }
});

socket.on('connect_error', (error) => {
  console.error('Socket.IO connect error:', error);
  updateStatus('Signaling connection error');
});

updateStatus('Waiting for Android stream...');
console.log('Web client ready for incoming stream');