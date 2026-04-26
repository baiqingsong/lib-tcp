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
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TcpServerFactory {

    private static final String TAG = "TcpServerFactory";

    /** 读取单行最大长度限制，防止恶意大数据攻击 */
    private static final int MAX_LINE_LENGTH = 64 * 1024;
    /** 默认最大客户端连接数 */
    private static final int DEFAULT_MAX_CLIENTS = 50;
    /** 客户端 Socket 读超时（毫秒），用于检测死连接，0表示无限等待 */
    private static final int DEFAULT_CLIENT_SO_TIMEOUT = 5 * 60 * 1000;
    /** 心跳请求标识 */
    static final String HEARTBEAT_PING = "##HB##";
    /** 心跳响应标识 */
    static final String HEARTBEAT_PONG = "##HB_ACK##";

    private int serverPort = 8088;
    private int maxClients = DEFAULT_MAX_CLIENTS;
    private int clientSoTimeout = DEFAULT_CLIENT_SO_TIMEOUT;
    private long heartbeatInterval = 0;
    private long heartbeatTimeout = 0;
    private volatile File fileSaveDir;

    private volatile ServerSocket serverSocket;
    private volatile TcpServerListener listener;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger clientIdGenerator = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ClientConnection> clientMap = new ConcurrentHashMap<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean serverStarted = new AtomicBoolean(false);
    private final AtomicInteger sessionId = new AtomicInteger(0);
    private volatile ExecutorService acceptExecutor;
    private volatile ExecutorService clientExecutor;
    private volatile ScheduledExecutorService heartbeatScheduler;

    public TcpServerFactory() {
    }

    // ==================== 配置方法（链式调用，仅在未启动时调用） ====================

    public TcpServerFactory setServerPort(int port) {
        checkNotRunning();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        this.serverPort = port;
        return this;
    }

    public TcpServerFactory setMaxClients(int maxClients) {
        checkNotRunning();
        if (maxClients <= 0) {
            throw new IllegalArgumentException("Max clients must be positive");
        }
        this.maxClients = maxClients;
        return this;
    }

    public TcpServerFactory setClientSoTimeout(int timeoutMs) {
        checkNotRunning();
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Client SO timeout cannot be negative");
        }
        this.clientSoTimeout = timeoutMs;
        return this;
    }

    /**
     * 设置心跳参数，用于快速检测死连接并自动踢掉无响应客户端。
     * 心跳通过内部协议自动处理，对业务层透明（不会触发 onReceiveData）。
     *
     * @param intervalMs 心跳检查间隔（毫秒），0 表示禁用心跳
     * @param timeoutMs  心跳超时（毫秒），超过此时间未收到任何数据则踢掉客户端
     * @return this
     */
    public TcpServerFactory setHeartbeat(long intervalMs, long timeoutMs) {
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
    public TcpServerFactory setFileSaveDir(File dir) {
        this.fileSaveDir = dir;
        return this;
    }

    private void checkNotRunning() {
        if (isRunning.get()) {
            throw new IllegalStateException("Cannot change config while server is running");
        }
    }

    // ==================== 核心方法 ====================

    /**
     * 启动服务端
     *
     * @param listener 回调监听器
     */
    public synchronized void startServer(TcpServerListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener cannot be null");
        }
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Server is already running");
            return;
        }
        this.listener = listener;
        serverStarted.set(false);
        clientMap.clear();
        acceptExecutor = Executors.newSingleThreadExecutor();
        clientExecutor = Executors.newCachedThreadPool();
        final int session = sessionId.incrementAndGet();
        acceptExecutor.execute(() -> acceptLoop(session));
    }

    /**
     * 停止服务端，断开所有客户端
     */
    public synchronized void stopServer() {
        if (!isRunning.compareAndSet(true, false)) {
            return;
        }
        stopServerHeartbeat();
        ServerSocket ss = this.serverSocket;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                Log.e(TAG, "Close server socket error", e);
            }
            this.serverSocket = null;
        }
        for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
            ClientConnection conn = clientMap.remove(entry.getKey());
            if (conn != null) {
                conn.close();
                notifyClientDisconnected(entry.getKey());
            }
        }
        shutdownExecutors();
        if (serverStarted.compareAndSet(true, false)) {
            notifyServerStopped();
        }
        this.listener = null;
    }

    /**
     * 释放资源，清除所有回调引用，防止 Activity 泄漏。
     * 调用后此实例不可再使用，需重新创建。
     */
    public synchronized void release() {
        stopServer();
        mainHandler.removeCallbacksAndMessages(null);
        listener = null;
    }

    /**
     * 向指定客户端发送消息
     *
     * @param clientId 客户端ID
     * @param message  消息内容
     * @return 是否提交发送成功
     */
    public boolean sendMessage(String clientId, String message) {
        if (clientId == null || message == null) {
            return false;
        }
        ClientConnection conn = clientMap.get(clientId);
        ExecutorService exec = this.clientExecutor;
        if (conn != null && exec != null && !exec.isShutdown()) {
            try {
                exec.execute(() -> conn.send(message));
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Send rejected, executor is shut down");
            }
        }
        return false;
    }

    /**
     * 向所有已连接客户端广播消息
     *
     * @param message 消息内容
     */
    public void broadcastMessage(String message) {
        if (message == null) {
            return;
        }
        ExecutorService exec = this.clientExecutor;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
            ClientConnection conn = entry.getValue();
            try {
                exec.execute(() -> conn.send(message));
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Broadcast rejected, executor is shut down");
                break;
            }
        }
    }

    /**
     * 断开指定客户端
     *
     * @param clientId 客户端ID
     */
    public void disconnectClient(String clientId) {
        ClientConnection conn = clientMap.remove(clientId);
        if (conn != null) {
            conn.close();
            notifyClientDisconnected(clientId);
        }
    }

    /**
     * 向指定客户端发送文件。文件通过 Base64 编码分块传输，接收端自动还原。
     * 传输过程对业务层透明，不会触发对端的 onReceiveData 回调。
     *
     * @param clientId 目标客户端ID
     * @param filePath 要发送的文件路径
     * @return 是否提交发送成功
     */
    public boolean sendFile(String clientId, String filePath) {
        if (clientId == null || filePath == null) {
            return false;
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            Log.e(TAG, "File not found: " + filePath);
            return false;
        }
        ClientConnection conn = clientMap.get(clientId);
        ExecutorService exec = this.clientExecutor;
        if (conn != null && exec != null && !exec.isShutdown()) {
            try {
                exec.execute(() -> doSendFile(conn, file));
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "SendFile rejected, executor is shut down");
            }
        }
        return false;
    }

    /**
     * 向指定客户端发送大数据。数据通过 Base64 编码分块传输，接收端自动还原。
     * 传输过程对业务层透明，不会触发对端的 onReceiveData 回调。
     *
     * @param clientId 目标客户端ID
     * @param data     要发送的字节数据
     * @return 是否提交发送成功
     */
    public boolean sendLargeData(String clientId, byte[] data) {
        if (clientId == null || data == null || data.length == 0) return false;
        ClientConnection conn = clientMap.get(clientId);
        ExecutorService exec = this.clientExecutor;
        if (conn != null && exec != null && !exec.isShutdown()) {
            try {
                exec.execute(() -> doSendLargeData(conn, data));
                return true;
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "SendLargeData rejected, executor is shut down");
            }
        }
        return false;
    }

    /**
     * 向指定客户端发送大文本数据。
     *
     * @param clientId 目标客户端ID
     * @param text     要发送的文本
     * @return 是否提交发送成功
     */
    public boolean sendLargeData(String clientId, String text) {
        if (text == null) return false;
        return sendLargeData(clientId, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 获得当前连接的客户端数量
     */
    public int getClientCount() {
        return clientMap.size();
    }

    /**
     * 获取所有已连接客户端的 ID 集合
     *
     * @return 不可修改的客户端 ID 集合
     */
    public Set<String> getConnectedClientIds() {
        return Collections.unmodifiableSet(new HashSet<>(clientMap.keySet()));
    }

    /**
     * 获取指定客户端的远程地址
     *
     * @param clientId 客户端ID
     * @return 远程地址字符串（如 "/192.168.1.100:54321"），客户端不存在则返回 null
     */
    public String getClientAddress(String clientId) {
        ClientConnection conn = clientMap.get(clientId);
        if (conn != null && !conn.socket.isClosed()) {
            return conn.socket.getRemoteSocketAddress().toString();
        }
        return null;
    }

    /**
     * 服务端是否在运行
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    // ==================== 内部实现 ====================

    private void acceptLoop(int session) {
        final ExecutorService localClientExec = this.clientExecutor;
        final ExecutorService localAcceptExec = this.acceptExecutor;
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            serverSocket = ss;
            ss.bind(new java.net.InetSocketAddress(serverPort));
            if (sessionId.get() != session || !isRunning.get()) {
                return;
            }
            serverStarted.set(true);
            notifyServerStarted(serverPort);
            startServerHeartbeat();

            while (isRunning.get() && sessionId.get() == session) {
                Socket clientSocket = ss.accept();

                if (clientMap.size() >= maxClients) {
                    Log.w(TAG, "Max clients reached, rejecting connection");
                    clientSocket.close();
                    continue;
                }

                clientSocket.setTcpNoDelay(true);
                clientSocket.setKeepAlive(true);
                if (clientSoTimeout > 0) {
                    clientSocket.setSoTimeout(clientSoTimeout);
                }

                String clientId = "client_" + clientIdGenerator.incrementAndGet();
                ClientConnection conn = new ClientConnection(clientId, clientSocket);
                if (!conn.isValid()) {
                    Log.e(TAG, "Failed to initialize client connection: " + clientId);
                    conn.close();
                    continue;
                }
                clientMap.put(clientId, conn);
                notifyClientConnected(clientId);

                ExecutorService exec = localClientExec;
                if (exec != null && !exec.isShutdown()) {
                    try {
                        exec.execute(() -> handleClient(conn));
                    } catch (RejectedExecutionException e) {
                        clientMap.remove(clientId);
                        conn.close();
                        notifyClientDisconnected(clientId);
                        Log.w(TAG, "Client handler rejected, executor is shut down");
                    }
                } else {
                    clientMap.remove(clientId);
                    conn.close();
                    notifyClientDisconnected(clientId);
                }
            }
        } catch (IOException e) {
            if (sessionId.get() == session && isRunning.compareAndSet(true, false)) {
                stopServerHeartbeat();
                Log.e(TAG, "Accept loop error", e);
                notifyError("Server error: " + e.getMessage());
                for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
                    ClientConnection c = clientMap.remove(entry.getKey());
                    if (c != null) {
                        c.close();
                        notifyClientDisconnected(entry.getKey());
                    }
                }
                if (serverStarted.compareAndSet(true, false)) {
                    notifyServerStopped();
                }
            }
            if (localClientExec != null && !localClientExec.isShutdown()) {
                localClientExec.shutdownNow();
            }
            if (localAcceptExec != null && !localAcceptExec.isShutdown()) {
                localAcceptExec.shutdownNow();
            }
        } finally {
            if (ss != null && !ss.isClosed()) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleClient(ClientConnection conn) {
        try {
            BufferedReader input = new BufferedReader(
                    new InputStreamReader(conn.socket.getInputStream(), StandardCharsets.UTF_8));
            char[] buffer = new char[4096];
            StringBuilder lineBuilder = new StringBuilder();

            while (isRunning.get() && !conn.socket.isClosed()) {
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
                        conn.lastActivityTime = System.currentTimeMillis();
                        if (HEARTBEAT_PING.equals(line)) {
                            conn.send(HEARTBEAT_PONG);
                        } else if (!HEARTBEAT_PONG.equals(line)) {
                            if (TcpFileHelper.isFileProtocol(line)) {
                                handleFileProtocol(conn, line);
                            } else if (TcpFileHelper.isLargeDataProtocol(line)) {
                                handleLargeDataProtocol(conn, line);
                            } else {
                                notifyReceiveData(conn.clientId, line);
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
        } catch (IOException e) {
            if (isRunning.get()) {
                Log.e(TAG, "Client " + conn.clientId + " read error", e);
            }
        } finally {
            if (clientMap.remove(conn.clientId) != null) {
                notifyClientDisconnected(conn.clientId);
            }
            conn.close();
        }
    }

    private void shutdownExecutors() {
        ExecutorService ce = this.clientExecutor;
        if (ce != null && !ce.isShutdown()) {
            ce.shutdownNow();
        }
        this.clientExecutor = null;

        ExecutorService ae = this.acceptExecutor;
        if (ae != null && !ae.isShutdown()) {
            ae.shutdownNow();
        }
        this.acceptExecutor = null;
    }

    // ==================== 文件传输 ====================

    private void doSendFile(ClientConnection conn, File file) {
        String fileName = file.getName();
        long fileSize = file.length();
        try {
            String md5 = TcpFileHelper.md5(file);
            conn.send(TcpFileHelper.FILE_START + fileName + "##" + fileSize);
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[TcpFileHelper.CHUNK_SIZE];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                String base64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                conn.send(TcpFileHelper.FILE_DATA + base64);
            }
            fis.close();
            conn.send(TcpFileHelper.FILE_END + md5);
        } catch (IOException e) {
            Log.e(TAG, "Send file error to " + conn.clientId + ": " + fileName, e);
            notifyError("Send file error: " + e.getMessage());
        }
    }

    private void handleFileProtocol(ClientConnection conn, String line) {
        if (line.startsWith(TcpFileHelper.FILE_START)) {
            String payload = line.substring(TcpFileHelper.FILE_START.length());
            String[] parts = payload.split("##", 2);
            if (parts.length != 2) {
                notifyFileError(conn.clientId, "unknown", "Invalid file start header");
                return;
            }
            String fileName = parts[0];
            long fileSize;
            try {
                fileSize = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                notifyFileError(conn.clientId, fileName, "Invalid file size");
                return;
            }
            File saveDir = this.fileSaveDir;
            if (saveDir == null) {
                notifyFileError(conn.clientId, fileName, "File save directory not set, call setFileSaveDir() first");
                return;
            }
            if (!conn.fileReceiveCtx.begin(saveDir, fileName, fileSize)) {
                notifyFileError(conn.clientId, fileName, "Failed to initialize file receive");
            }
        } else if (line.startsWith(TcpFileHelper.FILE_DATA)) {
            if (!conn.fileReceiveCtx.isReceiving()) {
                return;
            }
            String base64Data = line.substring(TcpFileHelper.FILE_DATA.length());
            int progress = conn.fileReceiveCtx.writeChunk(base64Data);
            if (progress < 0) {
                String fn = conn.fileReceiveCtx.fileName;
                conn.fileReceiveCtx.reset();
                notifyFileError(conn.clientId, fn != null ? fn : "unknown", "Write chunk failed");
            } else {
                notifyFileProgress(conn.clientId, conn.fileReceiveCtx.fileName, progress);
            }
        } else if (line.startsWith(TcpFileHelper.FILE_END)) {
            if (!conn.fileReceiveCtx.isReceiving()) {
                return;
            }
            String expectedMd5 = line.substring(TcpFileHelper.FILE_END.length());
            String fileName = conn.fileReceiveCtx.fileName;
            String filePath = conn.fileReceiveCtx.finish(expectedMd5);
            if (filePath != null) {
                notifyFileReceived(conn.clientId, filePath, fileName);
            } else {
                notifyFileError(conn.clientId, fileName != null ? fileName : "unknown", "File MD5 verification failed");
            }
        }
    }

    // ==================== 大数据传输 ====================

    private void doSendLargeData(ClientConnection conn, byte[] data) {
        String md5 = TcpFileHelper.md5(data);
        conn.send(TcpFileHelper.LARGE_START + data.length);
        for (int i = 0; i < data.length; i += TcpFileHelper.CHUNK_SIZE) {
            int end = Math.min(i + TcpFileHelper.CHUNK_SIZE, data.length);
            String b64 = android.util.Base64.encodeToString(data, i, end - i, android.util.Base64.NO_WRAP);
            conn.send(TcpFileHelper.LARGE_DATA + b64);
        }
        conn.send(TcpFileHelper.LARGE_END + md5);
    }

    private void handleLargeDataProtocol(ClientConnection conn, String line) {
        if (line.startsWith(TcpFileHelper.LARGE_START)) {
            String sizeStr = line.substring(TcpFileHelper.LARGE_START.length());
            long dataSize;
            try {
                dataSize = Long.parseLong(sizeStr);
            } catch (NumberFormatException e) {
                notifyLargeDataError(conn.clientId, "Invalid large data size");
                return;
            }
            if (!conn.largeDataReceiveCtx.begin(dataSize)) {
                notifyLargeDataError(conn.clientId, "Failed to initialize large data receive");
            }
        } else if (line.startsWith(TcpFileHelper.LARGE_DATA)) {
            if (!conn.largeDataReceiveCtx.isReceiving()) return;
            String b64 = line.substring(TcpFileHelper.LARGE_DATA.length());
            int progress = conn.largeDataReceiveCtx.writeChunk(b64);
            if (progress < 0) {
                conn.largeDataReceiveCtx.reset();
                notifyLargeDataError(conn.clientId, "Write large data chunk failed");
            } else {
                notifyLargeDataProgress(conn.clientId, progress);
            }
        } else if (line.startsWith(TcpFileHelper.LARGE_END)) {
            if (!conn.largeDataReceiveCtx.isReceiving()) return;
            String expectedMd5 = line.substring(TcpFileHelper.LARGE_END.length());
            byte[] data = conn.largeDataReceiveCtx.finish(expectedMd5);
            if (data != null) {
                notifyReceiveLargeData(conn.clientId, data);
            } else {
                notifyLargeDataError(conn.clientId, "Large data MD5 verification failed");
            }
        }
    }

    // ==================== 心跳机制 ====================

    private void startServerHeartbeat() {
        if (heartbeatInterval <= 0) {
            return;
        }
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        this.heartbeatScheduler = scheduler;
        scheduler.scheduleWithFixedDelay(() -> {
            if (!isRunning.get()) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Map.Entry<String, ClientConnection> entry : clientMap.entrySet()) {
                ClientConnection conn = entry.getValue();
                long elapsed = now - conn.lastActivityTime;
                if (heartbeatTimeout > 0 && elapsed > heartbeatTimeout) {
                    Log.w(TAG, "Client " + conn.clientId + " heartbeat timeout (" + elapsed + "ms)");
                    disconnectClient(conn.clientId);
                } else if (elapsed > heartbeatInterval) {
                    conn.send(HEARTBEAT_PING);
                }
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private void stopServerHeartbeat() {
        ScheduledExecutorService scheduler = this.heartbeatScheduler;
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        this.heartbeatScheduler = null;
    }

    // ==================== 客户端连接封装 ====================

    private static class ClientConnection {
        final String clientId;
        final Socket socket;
        private volatile PrintWriter output;
        private final Object sendLock = new Object();
        volatile long lastActivityTime;
        final TcpFileHelper.FileReceiveContext fileReceiveCtx = new TcpFileHelper.FileReceiveContext();
        final TcpFileHelper.LargeDataReceiveContext largeDataReceiveCtx = new TcpFileHelper.LargeDataReceiveContext();

        ClientConnection(String clientId, Socket socket) {
            this.clientId = clientId;
            this.socket = socket;
            this.lastActivityTime = System.currentTimeMillis();
            try {
                this.output = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            } catch (IOException e) {
                Log.e(TAG, "Create output stream error for " + clientId, e);
                this.output = null;
            }
        }

        boolean isValid() {
            return output != null && !socket.isClosed();
        }

        void send(String message) {
            synchronized (sendLock) {
                PrintWriter w = this.output;
                if (w != null) {
                    w.println(message);
                    if (w.checkError()) {
                        Log.e(TAG, "Send error to " + clientId);
                    }
                }
            }
        }

        void close() {
            synchronized (sendLock) {
                PrintWriter w = this.output;
                if (w != null) {
                    w.close();
                    this.output = null;
                }
            }
            try {
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Close client socket error: " + clientId, e);
            }
        }
    }

    // ==================== 回调通知（主线程） ====================

    private void notifyServerStarted(int port) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onServerStarted(port));
        }
    }

    private void notifyServerStopped() {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(l::onServerStopped);
        }
    }

    private void notifyClientConnected(String clientId) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onClientConnected(clientId));
        }
    }

    private void notifyClientDisconnected(String clientId) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onClientDisconnected(clientId));
        }
    }

    private void notifyReceiveData(String clientId, String data) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onReceiveData(clientId, data));
        }
    }

    private void notifyError(String errorMessage) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onError(errorMessage));
        }
    }

    private void notifyFileProgress(String clientId, String fileName, int progress) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onFileProgress(clientId, fileName, progress));
        }
    }

    private void notifyFileReceived(String clientId, String filePath, String originalFileName) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onFileReceived(clientId, filePath, originalFileName));
        }
    }

    private void notifyFileError(String clientId, String fileName, String errorMessage) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onFileError(clientId, fileName, errorMessage));
        }
    }

    private void notifyReceiveLargeData(String clientId, byte[] data) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onReceiveLargeData(clientId, data));
        }
    }

    private void notifyLargeDataProgress(String clientId, int progress) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onLargeDataProgress(clientId, progress));
        }
    }

    private void notifyLargeDataError(String clientId, String errorMessage) {
        TcpServerListener l = listener;
        if (l != null) {
            mainHandler.post(() -> l.onLargeDataError(clientId, errorMessage));
        }
    }
}
