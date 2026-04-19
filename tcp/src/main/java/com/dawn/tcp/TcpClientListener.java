package com.dawn.tcp;

public interface TcpClientListener {
    void onConnected();
    void onDisconnected();
    void onReceiveData(String data);
    void onError(String errorMessage);

    /**
     * 客户端完全停止（不会再重连），可在此回调中做最终清理。
     * 调用时机：所有重连耗尽 / 主动 stopClient / 不重连且连接断开。
     */
    void onStopped();
}
