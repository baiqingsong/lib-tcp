package com.dawn.tcp;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpClientFactory {

    private static final String TAG = "TcpClientFactory";

    /** 默认连接超时（毫秒） */
    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    /** 默认读超时（毫秒） */
    private static final int DEFAULT_SO_TIMEOUT = 0;
    /** 读取单行最大长度限制，防止恶意大数据攻击 */
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    /** 默认重连间隔（毫秒） */
    private static final long DEFAULT_RECONNECT_INTERVAL = 3_000;
    /** 默认最大重连次数，0表示不重连 */
    private static final int DEFAULT_MAX_RECONNECT_COUNT = 0;
    /** 心跳请求标识 */
    static final String HEARTBEAT_PING = "##HB##";
    /** 心跳响应标识 */
    static final String HEARTBEAT_PONG = "##HB_ACK##";

    private String serverIp = "127.0.0.1";
    private int serverPort = 8088;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int soTimeout = DEFAULT_SO_TIMEOUT;
    private long reconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    private int maxReconnectCount = DEFAULT_MAX_RECONNECT_COUNT;
    private long heartbeatInterval = 0;
    private long heartbeatTimeout = 0;
    private volatile File fileSaveDir;

    private volatile Socket socket;
    private volatile PrintWriter output;
    private volatile TcpClientListener listener;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean wasConnected = new AtomicBoolean(false);
    private final AtomicInteger sessionId = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile ExecutorService connectExecutor;
    private volatile ExecutorService sendExecutor;
    private volatile ScheduledExecutorService heartbeatScheduler;
    private volatile long lastDataReceivedTime = 0;
    private final TcpFileHelper.FileReceiveContext fileReceiveCtx = new TcpFileHelper.FileReceiveContext();

    public TcpClientFactory() {
    }

    // ==================== 配置方法（链式调用，仅在未启动时调用） ====================

    public TcpClientFactory setServerIp(String ip) {
        checkNotRunning();
        if (ip == null || ip.isEmpty()) {
            throw new IllegalArgumentException("Server IP cannot be null or empty");
        }
        this.serverIp = ip;
        return this;
    }

    public TcpClientFactory setServerPort(int port) {
        checkNotRunning();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.serverPort = port;
        return this;
    }

    public TcpClientFactory setConnectTimeout(int timeoutMs) {
        checkNotRunning();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Connect timeout cannot be negative");
        }
        this.connectTimeout = timeoutMs;
        return this;
    }

    public TcpClientFactory setSoTimeout(int timeoutMs) {
        checkNotRunning();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("SO timeout cannot be negative");
        }
        this.soTimeout = timeoutMs;
        return this;
    }

    public TcpClientFactory setReconnect(int maxCount, long intervalMs) {
        checkNotRunning();
        if (maxCount < -1) {
            throw new IllegalArgumentException("Max reconnect count must be >= -1 (-1 for infinite)");
        }
        if (intervalMs < 0) {
            throw new IllegalArgumentException("Reconnect interval cannot be negative");
        }
        this.maxReconnectCount = maxCount;
        this.reconnectInterval = intervalMs;
        return this;
    }

    /**
     * 设置心跳参数，用于快速检测死连接。
     * 心跳通过内部协议自动处理，对业务层透明（不会触发 onReceiveData）。
     *
     * @param intervalMs 心跳发送间隔（毫秒），0 表示禁用心跳
     * @param timeoutMs  心跳超时（毫秒），超过此时间未收到任何数据则认为连接已死
     * @return this
     */
    public TcpClientFactory setHeartbeat(long intervalMs, long timeoutMs) {
        checkNotRunning();
        if (intervalMs < 0) {
            throw new IllegalArgumentException("Heartbeat interval cannot be negative");
        }
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Heartbeat timeout cannot be negative");
        }
        if (intervalMs > 0 && timeoutMs <= intervalMs) {
            throw new IllegalArgumentException("Heartbeat timeout must be greater than interval");
        }
        this.heartbeatInterval = intervalMs;
        this.heartbeatTimeout = timeoutMs;
        return this;
    }

    /**
     * 设置文件接收保存目录。未设置则不支持接收文件，收到文件传输消息会触发 onFileError。
     *
     * @param dir 保存目录
     * @return this
     */
    public TcpClientFactory setFileSaveDir(File dir) {
        this.fileSaveDir = dir;
        return this;
    }

    private void checkNotRunning() {
        if (isRunning.get()) {
            throw new IllegalStateException("Cannot change config while client is running");
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 启动客户端连接
     *
     * @param listener 回调监听器
     */
    public synchronized void startClient(TcpClientListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Client is already running");
            return;
        }
        this.listener = listener;
        wasConnected.set(false);
        connectExecutor = Executors.newSingleThreadExecutor();
        sendExecutor = Executors.newSingleThreadExecutor();
        final int session = sessionId.incrementAndGet();
        connectExecutor.execute(() -> connectLoop(session));
    }

    /**
     * 停止客户端连接
     */
    public synchronized void stopClient() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }
        stopHeartbeat();
        closeConnection();
        shutdownExecutors();
        if (wasConnected.compareAndSet(true, false)) {
            notifyDisconnected();
        }
        notifyStopped();
        this.listener = null;
    }

    /**
     * 释放资源，清除所有回调引用，防止 Activity 泄漏。
     * 调用后此实例不可再使用，需重新创建。
     */
    public synchronized void release() {
        stopClient();
        mainHandler.removeCallbacksAndMessages(null);
        listener = null;
    }

    /**
     * 发送消息（线程安全）
     *
     * @param message 要发送的消息
     * @return 是否提交发送成功（不代表对端一定收到）
     */
    public boolean sendMessage(String message) {
        if (message == null) {
            return false;
        }
        PrintWriter writer = this.output;
        ExecutorService exec = this.sendExecutor;
        if (writer != null && exec != null && !exec.isShutdown() && isConnected()) {
            try {
                exec.execute(() -> {
                    try {
                        writer.println(message);
                        if (writer.checkError()) {
                            notifyError("Send message failed: stream error");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Send message error", e);
                        notifyError("Send message error: " + e.getMessage());
                    }
                });
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Send rejected, executor is shut down");
            }
        }
        return false;
    }

    /**
     * 当前是否已连接
     */
    public boolean isConnected() {
        Socket s = this.socket;
        return s != null && s.isConnected() && !s.isClosed();
    }

    /**
     * 发送文件（线程安全）。文件通过 Base64 编码分块传输，接收端自动还原。
     * 传输过程对业务层透明，不会触发对端的 onReceiveData 回调。
     *
     * @param filePath 要发送的文件路径
     * @return 是否提交发送成功
     */
    public boolean sendFile(String filePath) {
        if (filePath == null) {
            return false;
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            Log.e(TAG, "File not found: " + filePath);
            return false;
        }
        PrintWriter writer = this.output;
        ExecutorService exec = this.sendExecutor;
        if (writer != null && exec != null && !exec.isShutdown() && isConnected()) {
            try {
                exec.execute(() -> doSendFile(writer, file));
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "SendFile rejected, executor is shut down");
            }
        }
        return false;
    }

    // ==================== 内部实现 ====================

    private void connectLoop(int session) {
        final ExecutorService localSendExec = this.sendExecutor;
        final ExecutorService localConnectExec = this.connectExecutor;
        int reconnectCount = 0;

        while (isRunning.get() && sessionId.get() == session) {
            Socket currentSocket = null;
            PrintWriter currentOutput = null;
            try {
                currentSocket = doConnect();
                currentOutput = this.output;
                if (sessionId.get() != session || !isRunning.get()) {
                    break;
                }
                reconnectCount = 0;
                if (sessionId.get() != session || !isRunning.get()) {
                    break;
                }
                wasConnected.set(true);
                notifyConnected();
                startHeartbeat();
                readMessages(currentSocket, session);
            } catch (IOException e) {
                Log.e(TAG, "Connection error", e);
                if (isRunning.get() && sessionId.get() == session) {
                    notifyError("Connection error: " + e.getMessage());
                }
            } finally {
                stopHeartbeat();
                if (currentOutput != null) {
                    currentOutput.close();
                    if (this.output == currentOutput) {
                        this.output = null;
                    }
                }
                if (currentSocket != null) {
                    try {
                        currentSocket.close();
                    } catch (IOException e) {
                        Log.e(TAG, "Close socket error", e);
                    }
                    if (this.socket == currentSocket) {
                        this.socket = null;
                    }
                }
            }

            if (sessionId.get() != session) {
                break;
            }

            if (sessionId.get() == session && wasConnected.compareAndSet(true, false)) {
                notifyDisconnected();
            }

            if (!isRunning.get()) {
                break;
            }
            if (maxReconnectCount == 0) {
                if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                    notifyStopped();
                }
                break;
            }
            reconnectCount++;
            if (maxReconnectCount > 0 && reconnectCount > maxReconnectCount) {
                if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                    notifyError("Max reconnect attempts reached (" + maxReconnectCount + ")");
                    notifyStopped();
                }
                break;
            }
            Log.i(TAG, "Reconnecting in " + reconnectInterval + "ms (attempt " + reconnectCount
                    + (maxReconnectCount > 0 ? "/" + maxReconnectCount : "/\u221e") + ")");
            try {
                Thread.sleep(reconnectInterval);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                    notifyStopped();
                }
                break;
            }
        }
        if (localSendExec != null && !localSendExec.isShutdown()) {
            localSendExec.shutdownNow();
        }
        if (localConnectExec != null && !localConnectExec.isShutdown()) {
            localConnectExec.shutdownNow();
        }
    }

    private Socket doConnect() throws IOException {
        Socket s = new Socket();
        this.socket = s;
        try {
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            if (soTimeout > 0) {
                s.setSoTimeout(soTimeout);
            }
            s.connect(new InetSocketAddress(serverIp, serverPort), connectTimeout);
            this.output = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
            return s;
        } catch (IOException e) {
            if (this.socket == s) {
                this.socket = null;
            }
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void readMessages(Socket s, int session) throws IOException {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        char[] buffer = new char[4096];
        StringBuilder lineBuilder = new StringBuilder();

        while (isRunning.get() && sessionId.get() == session) {
            int charsRead;
            try {
                charsRead = input.read(buffer);
            } catch (SocketTimeoutException e) {
                continue;
            }
            if (charsRead == -1) {
                break;
            }
            for (int i = 0; i < charsRead; i++) {
                char c = buffer[i];
                if (c == '\n') {
                    String line = lineBuilder.toString();
                    if (line.endsWith("\r")) {
                        line = line.substring(0, line.length() - 1);
                    }
                    lastDataReceivedTime = System.currentTimeMillis();
                    if (HEARTBEAT_PING.equals(line)) {
                        sendHeartbeat(HEARTBEAT_PONG);
                    } else if (!HEARTBEAT_PONG.equals(line)) {
                        if (TcpFileHelper.isFileProtocol(line)) {
                            handleFileProtocol(line);
                        } else {
                            notifyReceiveData(line);
                        }
                    }
                    lineBuilder.setLength(0);
                } else {
                    if (lineBuilder.length() < MAX_LINE_LENGTH) {
                        lineBuilder.append(c);
                    }
                }
            }
        }
    }

    private void closeConnection() {
        PrintWriter w = this.output;
        if (w != null) {
            w.close();
            this.output = null;
        }
        Socket s = this.socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                Log.e(TAG, "Close socket error", e);
            }
            this.socket = null;
        }
    }

    private void shutdownExecutors() {
        ExecutorService se = this.sendExecutor;
        if (se != null && !se.isShutdown()) {
            se.shutdownNow();
        }
        this.sendExecutor = null;

        ExecutorService ce = this.connectExecutor;
        if (ce != null && !ce.isShutdown()) {
            ce.shutdownNow();
        }
        this.connectExecutor = null;
    }

    // ==================== 文件传输 ====================

    private void doSendFile(PrintWriter writer, File file) {
        String fileName = file.getName();
        long fileSize = file.length();
        try {
            String md5 = TcpFileHelper.md5(file);
            writer.println(TcpFileHelper.FILE_START + fileName + "##" + fileSize);
            if (writer.checkError()) {
                notifyError("Send file header failed: " + fileName);
                return;
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[TcpFileHelper.CHUNK_SIZE];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                String base64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                writer.println(TcpFileHelper.FILE_DATA + base64);
                if (writer.checkError()) {
                    fis.close();
                    notifyError("Send file data failed: " + fileName);
                    return;
                }
            }
            fis.close();
            writer.println(TcpFileHelper.FILE_END + md5);
            if (writer.checkError()) {
                notifyError("Send file end failed: " + fileName);
            }
        } catch (IOException e) {
            Log.e(TAG, "Send file error: " + fileName, e);
            notifyError("Send file error: " + e.getMessage());
        }
    }

    private void handleFileProtocol(String line) {
        if (line.startsWith(TcpFileHelper.FILE_START)) {
            String payload = line.substring(TcpFileHelper.FILE_START.length());
            String[] parts = payload.split("##", 2);
            if (parts.length != 2) {
                notifyFileError("unknown", "Invalid file start header");
                return;
            }
            String fileName = parts[0];
            long fileSize;
            try {
                fileSize = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                notifyFileError(fileName, "Invalid file size");
                return;
            }
            File saveDir = this.fileSaveDir;
            if (saveDir == null) {
                notifyFileError(fileName, "File save directory not set, call setFileSaveDir() first");
                return;
            }
            if (!fileReceiveCtx.begin(saveDir, fileName, fileSize)) {
                notifyFileError(fileName, "Failed to initialize file receive");
            }
        } else if (line.startsWith(TcpFileHelper.FILE_DATA)) {
            if (!fileReceiveCtx.isReceiving()) {
                return;
            }
            String base64Data = line.substring(TcpFileHelper.FILE_DATA.length());
            int progress = fileReceiveCtx.writeChunk(base64Data);
            if (progress < 0) {
                String fn = fileReceiveCtx.fileName;
                fileReceiveCtx.reset();
                notifyFileError(fn != null ? fn : "unknown", "Write chunk failed");
            } else {
                notifyFileProgress(fileReceiveCtx.fileName, progress);
            }
        } else if (line.startsWith(TcpFileHelper.FILE_END)) {
            if (!fileReceiveCtx.isReceiving()) {
                return;
            }
            String expectedMd5 = line.substring(TcpFileHelper.FILE_END.length());
            String fileName = fileReceiveCtx.fileName;
            String filePath = fileReceiveCtx.finish(expectedMd5);
            if (filePath != null) {
                notifyFileReceived(filePath, fileName);
            } else {
                notifyFileError(fileName != null ? fileName : "unknown", "File MD5 verification failed");
            }
        }
    }

    // ==================== 心跳机制 ====================

    private void startHeartbeat() {
        if (heartbeatInterval <= 0) {
            return;
        }
        lastDataReceivedTime = System.currentTimeMillis();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        this.heartbeatScheduler = scheduler;
        scheduler.scheduleWithFixedDelay(() -> {
            if (!isRunning.get()) {
                return;
            }
            sendHeartbeat(HEARTBEAT_PING);
            if (heartbeatTimeout > 0) {
                long elapsed = System.currentTimeMillis() - lastDataReceivedTime;
                if (elapsed > heartbeatTimeout) {
                    Log.w(TAG, "Heartbeat timeout (" + elapsed + "ms), closing connection");
                    closeConnection();
                }
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService scheduler = this.heartbeatScheduler;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        this.heartbeatScheduler = null;
    }

    private void sendHeartbeat(String message) {
        PrintWriter w = this.output;
        if (w != null) {
            w.println(message);
        }
    }

    // ==================== 回调通知（主线程） ====================

    private void notifyConnected() {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(l::onConnected);
        }
    }

    private void notifyDisconnected() {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(l::onDisconnected);
        }
    }

    private void notifyReceiveData(String data) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onReceiveData(data));
        }
    }

    private void notifyError(String errorMessage) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onError(errorMessage));
        }
    }

    private void notifyStopped() {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(l::onStopped);
        }
    }

    private void notifyFileProgress(String fileName, int progress) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onFileProgress(fileName, progress));
        }
    }

    private void notifyFileReceived(String filePath, String originalFileName) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onFileReceived(filePath, originalFileName));
        }
    }

    private void notifyFileError(String fileName, String errorMessage) {
        TcpClientListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onFileError(fileName, errorMessage));
        }
    }
}
