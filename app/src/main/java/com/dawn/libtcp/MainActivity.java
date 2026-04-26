package com.dawn.libtcp;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import com.dawn.tcp.TcpClientFactory;
import com.dawn.tcp.TcpClientListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private TcpClientFactory tcpClient;

    private EditText etIp, etPort, etMessage;
    private Button btnConnect, btnDisconnect, btnSendMsg;
    private Button btnSendLargeData, btnSendFile, btnSendImage, btnClearLog;
    private TextView tvLog, tvStatus;
    private ScrollView scrollLog;

    private ActivityResultLauncher<String[]> filePickerLauncher;
    private ActivityResultLauncher<String[]> imagePickerLauncher;

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        initPickers();
        setupClickListeners();
    }

    private void initViews() {
        etIp = findViewById(R.id.etIp);
        etPort = findViewById(R.id.etPort);
        etMessage = findViewById(R.id.etMessage);
        btnConnect = findViewById(R.id.btnConnect);
        btnDisconnect = findViewById(R.id.btnDisconnect);
        btnSendMsg = findViewById(R.id.btnSendMsg);
        btnSendLargeData = findViewById(R.id.btnSendLargeData);
        btnSendFile = findViewById(R.id.btnSendFile);
        btnSendImage = findViewById(R.id.btnSendImage);
        btnClearLog = findViewById(R.id.btnClearLog);
        tvLog = findViewById(R.id.tvLog);
        tvStatus = findViewById(R.id.tvStatus);
        scrollLog = findViewById(R.id.scrollLog);
    }

    private void initPickers() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) sendFileFromUri(uri, false); });
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) sendFileFromUri(uri, true); });
    }

    private void setupClickListeners() {
        btnConnect.setOnClickListener(v -> connect());
        btnDisconnect.setOnClickListener(v -> disconnect());
        btnSendMsg.setOnClickListener(v -> sendMessage());
        btnSendLargeData.setOnClickListener(v -> sendLargeData());
        btnSendFile.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));
        btnSendImage.setOnClickListener(v -> imagePickerLauncher.launch(new String[]{"image/*"}));
        btnClearLog.setOnClickListener(v -> tvLog.setText(""));
    }

    // ===================== 连接管理 =====================

    private void connect() {
        String ip = etIp.getText().toString().trim();
        String portStr = etPort.getText().toString().trim();
        if (ip.isEmpty() || portStr.isEmpty()) {
            log("错误：请输入服务器 IP 和端口");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            log("错误：端口号无效");
            return;
        }

        if (tcpClient != null) {
            tcpClient.release();
        }

        File recvDir = new File(getExternalFilesDir(null), "tcp_received");

        tcpClient = new TcpClientFactory()
                .setServerIp(ip)
                .setServerPort(port)
                .setConnectTimeout(10000)
                .setReconnect(-1, 3000)          // 无限重连，间隔 3s
                .setHeartbeat(10000, 30000)       // 心跳间隔 10s，超时 30s
                .setFileSaveDir(recvDir);

        tcpClient.startClient(new TcpClientListener() {
            @Override
            public void onConnected() {
                log("✓ 已连接  " + ip + ":" + port);
                setStatus("● 已连接", 0xFF4CAF50);
                setButtons(false, true);
            }

            @Override
            public void onDisconnected() {
                log("↺ 连接断开，等待重连...");
                setStatus("● 重连中...", 0xFFFF9800);
            }

            @Override
            public void onReceiveData(String data) {
                log("← 收到消息：" + data);
            }

            @Override
            public void onError(String errorMessage) {
                log("⚠ 错误：" + errorMessage);
            }

            @Override
            public void onStopped() {
                log("■ 客户端已停止");
                setStatus("● 未连接", 0xFF888888);
                setButtons(true, false);
            }

            @Override
            public void onFileProgress(String fileName, int progress) {
                if (progress % 25 == 0 || progress == 100) {
                    log("↓ 文件 " + fileName + " : " + progress + "%");
                }
            }

            @Override
            public void onFileReceived(String filePath, String originalFileName) {
                log("✓ 文件接收完成：" + originalFileName);
                log("  保存路径：" + filePath);
            }

            @Override
            public void onFileError(String fileName, String errorMessage) {
                log("✗ 文件接收失败：" + fileName + " - " + errorMessage);
            }

            @Override
            public void onReceiveLargeData(byte[] data) {
                log("✓ 大数据接收完成：" + formatSize(data.length));
            }

            @Override
            public void onLargeDataProgress(int progress) {
                if (progress % 25 == 0 || progress == 100) {
                    log("↓ 大数据接收：" + progress + "%");
                }
            }

            @Override
            public void onLargeDataError(String errorMessage) {
                log("✗ 大数据接收失败：" + errorMessage);
            }
        });

        setStatus("● 连接中...", 0xFFFF9800);
        setButtons(false, false);
        log("正在连接 " + ip + ":" + port + " ...");
    }

    private void disconnect() {
        if (tcpClient != null) {
            tcpClient.stopClient();
        }
    }

    // ===================== 数据发送 =====================

    private void sendMessage() {
        String msg = etMessage.getText().toString();
        if (msg.isEmpty()) { log("请输入消息内容"); return; }
        if (tcpClient == null || !tcpClient.isConnected()) { log("✗ 未连接"); return; }
        boolean ok = tcpClient.sendMessage(msg);
        if (ok) {
            log("→ 发送消息：" + msg);
            etMessage.setText("");
        } else {
            log("✗ 发送失败");
        }
    }

    private void sendLargeData() {
        if (tcpClient == null || !tcpClient.isConnected()) { log("✗ 未连接"); return; }
        log("正在生成 1MB 随机数据...");
        new Thread(() -> {
            byte[] data = new byte[1024 * 1024];
            new Random().nextBytes(data);
            boolean ok = tcpClient.sendLargeData(data);
            runOnUiThread(() -> {
                if (ok) log("→ 大数据已提交发送：" + formatSize(data.length));
                else    log("✗ 大数据发送失败：未连接");
            });
        }).start();
    }

    private void sendFileFromUri(Uri uri, boolean isImage) {
        if (tcpClient == null || !tcpClient.isConnected()) { log("✗ 未连接"); return; }
        new Thread(() -> {
            try {
                String name = resolveFileName(uri);
                File tmp = new File(getCacheDir(), "tcp_send_" + System.currentTimeMillis() + "_" + name);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    if (in == null) { runOnUiThread(() -> log("✗ 无法读取文件")); return; }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                boolean ok = tcpClient.sendFile(tmp.getAbsolutePath());
                final long size = tmp.length();
                final String type = isImage ? "图片" : "文件";
                runOnUiThread(() -> {
                    if (ok) log("→ " + type + "已提交发送：" + name + " (" + formatSize(size) + ")");
                    else    log("✗ " + type + "发送失败：未连接");
                });
            } catch (Exception e) {
                runOnUiThread(() -> log("✗ 文件处理异常：" + e.getMessage()));
            }
        }).start();
    }

    // ===================== 工具方法 =====================

    private String resolveFileName(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path != null) {
            int slash = path.lastIndexOf('/');
            if (slash >= 0) path = path.substring(slash + 1);
            // 去除冒号后内容（DocumentProvider 路径）
            int colon = path.lastIndexOf(':');
            if (colon >= 0) path = path.substring(colon + 1);
        }
        return (path != null && !path.isEmpty()) ? path : ("file_" + System.currentTimeMillis());
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        return String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024));
    }

    private void setStatus(String text, int color) {
        runOnUiThread(() -> {
            tvStatus.setText(text);
            tvStatus.setTextColor(color);
        });
    }

    private void setButtons(boolean connectEnabled, boolean disconnectEnabled) {
        runOnUiThread(() -> {
            btnConnect.setEnabled(connectEnabled);
            btnDisconnect.setEnabled(disconnectEnabled);
        });
    }

    private void log(String msg) {
        String line = "[" + sdf.format(new Date()) + "] " + msg + "\n";
        runOnUiThread(() -> {
            tvLog.append(line);
            scrollLog.post(() -> scrollLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tcpClient != null) {
            tcpClient.release();
            tcpClient = null;
        }
    }
}

