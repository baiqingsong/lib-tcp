package com.dawn.tcp;

public interface TcpServerListener {
    void onServerStarted(int port);
    void onServerStopped();
    void onClientConnected(String clientId);
    void onClientDisconnected(String clientId);
    void onReceiveData(String clientId, String data);
    void onError(String errorMessage);
}
