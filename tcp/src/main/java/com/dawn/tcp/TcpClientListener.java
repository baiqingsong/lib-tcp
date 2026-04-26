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

    /**
     * 文件接收进度回调。
     *
     * @param fileName 文件名
     * @param progress 进度（0-100）
     */
    default void onFileProgress(String fileName, int progress) {}

    /**
     * 文件接收完成回调。
     *
     * @param filePath         保存后的本地文件绝对路径
     * @param originalFileName 原始文件名
     */
    default void onFileReceived(String filePath, String originalFileName) {}

    /**
     * 文件接收失败回调。
     *
     * @param fileName     文件名
     * @param errorMessage 错误信息
     */
    default void onFileError(String fileName, String errorMessage) {}

    /**
     * 大数据接收完成回调。
     *
     * @param data 接收到的完整字节数组
     */
    default void onReceiveLargeData(byte[] data) {}

    /**
     * 大数据接收进度回调。
     *
     * @param progress 进度（0-100）
     */
    default void onLargeDataProgress(int progress) {}

    /**
     * 大数据接收失败回调。
     *
     * @param errorMessage 错误信息
     */
    default void onLargeDataError(String errorMessage) {}
}
