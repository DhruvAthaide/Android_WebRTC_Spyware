package com.example.wallpaperapplication;

import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

public class SdpObserverAdapter implements SdpObserver {
    @Override public void onCreateSuccess(SessionDescription s) {}
    @Override public void onSetSuccess() {}
    @Override public void onCreateFailure(String err) {}
    @Override public void onSetFailure(String err) {}
}
