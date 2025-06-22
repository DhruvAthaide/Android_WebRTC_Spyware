package com.example.wallpaperapplication;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import org.webrtc.ScreenCapturerAndroid;
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "streaming_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SIGNALING_URL = "http://192.168.29.10:3000";
    public static final String ACTION_MEDIA_PROJECTION_RESULT = "com.example.wallpaperapplication.MEDIA_PROJECTION_RESULT";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private SurfaceTextureHelper surfaceHelper;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoCapturer screenCapturer;
    private VideoSource screenSource;
    private PeerConnection peerConnection;
    private Socket socket;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        startForeground(NOTIFICATION_ID, createNotification());
        if (!hasRequiredPermissions()) {
            broadcastPermissionError();
            stopSelf();
            return;
        }
        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP_STREAMING".equals(action)) {
                stopSelf();
            } else if (ACTION_MEDIA_PROJECTION_RESULT.equals(action)) {
                int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1);
                Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
                if (resultCode == RESULT_OK && resultData != null) {
                    startScreenCapture(resultCode, resultData);
                } else {
                    Log.e(TAG, "MediaProjection permission denied");
                    broadcastPermissionError();
                    stopSelf();
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        cleanup();
        if (socket != null) socket.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean hasRequiredPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        if (!camera) Log.e(TAG, "Camera permission missing");
        if (!audio) Log.e(TAG, "Record audio permission missing");
        if (!notify) Log.e(TAG, "Notifications permission missing");
        return camera && audio && notify;
    }

    private void broadcastPermissionError() {
        Intent err = new Intent("com.example.wallpaperapplication.PERMISSION_ERROR");
        sendBroadcast(err);
    }

    private void initializeWebRTC() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions());
        eglBase = EglBase.create();
        factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .createPeerConnectionFactory();
    }

    private void setupMediaStreaming() {
        setupVideoCapture();
        setupAudioCapture();
        setupPeerConnection();
    }

    private void setupVideoCapture() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String device = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                device = name; break;
            }
        }
        if (device == null && enumerator.getDeviceNames().length > 0) {
            device = enumerator.getDeviceNames()[0];
        }
        if (device == null) {
            Log.e(TAG, "No camera available");
            broadcastPermissionError();
            stopSelf();
            return;
        }
        videoCapturer = enumerator.createCapturer(device, null);
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        videoCapturer.initialize(surfaceHelper, getApplicationContext(), videoSource.getCapturerObserver());
        try {
            videoCapturer.startCapture(640, 480, 30);
            Log.d(TAG, "Video capture started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start video capture", e);
            broadcastPermissionError();
            stopSelf();
        }
    }

    private void setupAudioCapture() {
        MediaConstraints audioConstraints = new MediaConstraints();
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
        audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
        audioSource = factory.createAudioSource(audioConstraints);
        Log.d(TAG, "Audio capture initialized");
    }

    private void startScreenCapture(int resultCode, Intent resultData) {
        screenCapturer = new ScreenCapturerAndroid(resultData, new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection stopped");
            }
        });
        screenSource = factory.createVideoSource(true); // isScreencast = true
        SurfaceTextureHelper screenSurfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.getEglBaseContext());
        screenCapturer.initialize(screenSurfaceHelper, getApplicationContext(), screenSource.getCapturerObserver());
        try {
            screenCapturer.startCapture(1280, 720, 30); // Adjust resolution as needed
            Log.d(TAG, "Screen capture started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start screen capture", e);
            broadcastPermissionError();
            return;
        }

        VideoTrack screenTrack = factory.createVideoTrack("screen", screenSource);
        peerConnection.addTransceiver(screenTrack, new RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("screen")));
        Log.d(TAG, "Screen track added");
    }

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:numb.viagenie.ca")
                .setUsername("your@email.com")
                .setPassword("yourpassword")
                .createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

        peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState s) {
                Log.d(TAG, "Signaling state: " + s);
            }
            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                Log.d(TAG, "ICE connection state: " + s);
            }
            @Override
            public void onIceConnectionReceivingChange(boolean receiving) {}
            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState s) {
                Log.d(TAG, "ICE gathering state: " + s);
            }
            @Override
            public void onIceCandidate(IceCandidate c) {
                if (webClientId == null) return;
                try {
                    JSONObject candidate = new JSONObject();
                    candidate.put("sdpMid", c.sdpMid);
                    candidate.put("sdpMLineIndex", c.sdpMLineIndex);
                    candidate.put("candidate", c.sdp);
                    JSONObject signal = new JSONObject();
                    signal.put("candidate", candidate);
                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId);
                    msg.put("from", socket.id());
                    msg.put("signal", signal);
                    socket.emit("signal", msg);
                    Log.d(TAG, "Sent ICE candidate: " + c.sdpMid);
                } catch (JSONException e) {
                    Log.e(TAG, "ICE send failed", e);
                }
            }
            @Override
            public void onIceCandidatesRemoved(IceCandidate[] cs) {}
            @Override
            public void onAddStream(org.webrtc.MediaStream ms) {}
            @Override
            public void onRemoveStream(org.webrtc.MediaStream ms) {}
            @Override
            public void onDataChannel(org.webrtc.DataChannel dc) {}
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms) {
                Log.d(TAG, "Track added: " + r.id());
            }
        });

        if (videoSource != null) {
            VideoTrack vt = factory.createVideoTrack("video", videoSource);
            peerConnection.addTransceiver(vt, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Video track added");
        }
        if (audioSource != null) {
            AudioTrack at = factory.createAudioTrack("audio", audioSource);
            peerConnection.addTransceiver(at, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Audio track added");
        }
    }

    private String webClientId = null;

    private void connectSignaling() {
        Log.d(TAG, "Connecting to signaling at " + SIGNALING_URL);
        IO.Options opts = new IO.Options();
        opts.transports = new String[]{"websocket"};
        opts.reconnection = true;
        opts.reconnectionAttempts = 5;
        opts.reconnectionDelay = 5000;

        try {
            socket = IO.socket(SIGNALING_URL, opts);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Bad signaling URL", e);
            stopSelf();
            return;
        }

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket.IO CONNECTED");
            socket.emit("identify", "android");
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + Arrays.toString(args));
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on("web-client-ready", args -> {
            webClientId = (String) args[0];
            Log.d(TAG, "Web client ready: " + webClientId);
            createAndSendOffer();
        }).on("signal", args -> {
            Log.d(TAG, "Signal incoming");
            if (args[0] instanceof JSONObject) {
                handleSignaling((JSONObject) args[0]);
            }
        });

        socket.connect();
    }

    private void createAndSendOffer() {
        if (webClientId == null) {
            Log.w(TAG, "No web client available");
            return;
        }

        Log.d(TAG, "Creating offer for web client: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                String modifiedSdp = sdp.description.replace("a=sendrecv", "a=sendonly")
                        .replace("a=recvonly", "a=sendonly");
                SessionDescription modifiedSession = new SessionDescription(sdp.type, modifiedSdp);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject signal = new JSONObject();
                            signal.put("type", "offer");
                            signal.put("sdp", modifiedSession.description);
                            JSONObject msg = new JSONObject();
                            msg.put("to", webClientId);
                            msg.put("from", socket.id());
                            msg.put("signal", signal);
                            socket.emit("signal", msg);
                            Log.d(TAG, "Sent offer to web client");
                        } catch (JSONException e) {
                            Log.e(TAG, "Offer send fail", e);
                        }
                    }
                    @Override
                    public void onSetFailure(String err) {
                        Log.e(TAG, "Set local desc fail: " + err);
                    }
                    @Override
                    public void onCreateSuccess(SessionDescription s) {}
                    @Override
                    public void onCreateFailure(String f) {
                        Log.e(TAG, "Create offer fail: " + f);
                    }
                }, modifiedSession);
            }
            @Override
            public void onSetSuccess() {}
            @Override
            public void onCreateFailure(String err) {
                Log.e(TAG, "Create offer fail: " + err);
            }
            @Override
            public void onSetFailure(String err) {
                Log.e(TAG, "Set desc fail: " + err);
            }
        }, mc);
    }

    private void handleSignaling(JSONObject msg) {
        try {
            JSONObject signal = msg.getJSONObject("signal");
            String type = signal.optString("type", "");
            if ("answer".equals(type)) {
                SessionDescription ans = new SessionDescription(
                        SessionDescription.Type.ANSWER, signal.getString("sdp"));
                peerConnection.setRemoteDescription(simpleSdpObserver, ans);
                Log.d(TAG, "Processed answer from web client");
            } else if (signal.has("candidate")) {
                JSONObject candidate = signal.getJSONObject("candidate");
                IceCandidate c = new IceCandidate(
                        candidate.getString("sdpMid"),
                        candidate.getInt("sdpMLineIndex"),
                        candidate.getString("candidate"));
                peerConnection.addIceCandidate(c);
                Log.d(TAG, "Added ICE candidate");
            }
        } catch (JSONException e) {
            Log.e(TAG, "Handle signaling error", e);
        }
    }

    private final SdpObserver simpleSdpObserver = new SdpObserver() {
        @Override
        public void onCreateSuccess(SessionDescription s) {}
        @Override
        public void onSetSuccess() {
            Log.d(TAG, "SDP set success");
        }
        @Override
        public void onCreateFailure(String e) {
            Log.e(TAG, "SDP create fail: " + e);
        }
        @Override
        public void onSetFailure(String e) {
            Log.e(TAG, "SDP set fail: " + e);
        }
    };

    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Camera, mic, and screen streaming");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, StreamingService.class);
        stop.setAction("STOP_STREAMING");
        PendingIntent stopPI = PendingIntent.getService(this, 0, stop,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Camera, mic, and screen streaming")
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void cleanup() {
        if (screenCapturer != null) {
            try {
                screenCapturer.stopCapture();
            } catch (InterruptedException ignored) {}
            screenCapturer.dispose();
            screenCapturer = null;
        }
        if (screenSource != null) {
            screenSource.dispose();
            screenSource = null;
        }
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException ignored) {}
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (surfaceHelper != null) {
            surfaceHelper.dispose();
            surfaceHelper = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
    }
}