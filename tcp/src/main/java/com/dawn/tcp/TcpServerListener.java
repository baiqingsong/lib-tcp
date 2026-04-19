package com.dawn.tcp;

public interface TcpServerListener {
    void onServerStarted(int port);
    void onServerStopped();
    void onClientConnected(String clientId);
    void onClientDisconnected(String clientId);
    void onReceiveData(String clientId, String data);
    void onError(String errorMessage);

    /**
     * 文件接收进度回调。
     *
     * @param clientId 发送方客户端ID
     * @param fileName 文件名
     * @param progress 进度（0-100）
     */
    default void onFileProgress(String clientId, String fileName, int progress) {}

    /**
     * 文件接收完成回调。
     *
     * @param clientId         发送方客户端ID
     * @param filePath         保存后的本地文件绝对路径
     * @param originalFileName 原始文件名
     */
    default void onFileReceived(String clientId, String filePath, String originalFileName) {}

    /**
     * 文件接收失败回调。
     *
     * @param clientId     发送方客户端ID
     * @param fileName     文件名
     * @param errorMessage 错误信息
     */
    default void onFileError(String clientId, String fileName, String errorMessage) {}
}
