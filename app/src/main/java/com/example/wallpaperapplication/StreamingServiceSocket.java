package com.example.wallpaperapplication;

import io.socket.client.IO;
import io.socket.client.Socket;
import java.net.URISyntaxException;

public class StreamingServiceSocket {
    private static Socket socket = null;
    private static final String SIGNALING_URL = "http://192.168.29.10:3000";

    public static synchronized Socket getSocket() {
        if (socket == null) {
            try {
                IO.Options opts = new IO.Options();
                opts.transports = new String[]{"websocket"};
                opts.reconnection = true;
                opts.reconnectionAttempts = 5;
                opts.reconnectionDelay = 5000;
                socket = IO.socket(SIGNALING_URL, opts);
            } catch (URISyntaxException e) {
                e.printStackTrace();
            }
        }
        return socket;
    }
}