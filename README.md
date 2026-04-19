# lib-tcp

Android TCP 通信库，提供线程安全的 TCP 客户端和服务端实现。支持链式配置、自动重连、多客户端管理、主线程回调。主要用于同局域网下两个不同安卓主板之间的数据传递。

## 引用

Step 1. Add the JitPack repository to your build file

```groovy
allprojects {
    repositories {
        maven { url 'https://jitpack.io' }
    }
}
```

Step 2. Add the dependency

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-tcp:Tag'
}
```

## 权限

在 `AndroidManifest.xml` 中添加以下权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## 模块结构

```
tcp/src/main/java/com/dawn/tcp/
├── TcpClientFactory.java      // TCP 客户端
├── TcpClientListener.java     // 客户端回调接口
├── TcpFileHelper.java         // 文件传输协议工具类
├── TcpServerFactory.java      // TCP 服务端
└── TcpServerListener.java     // 服务端回调接口
```

## 通信协议

- 传输编码：UTF-8
- 消息分隔：以换行符 `\n` 作为消息边界（兼容 `\r\n`）
- 单行限制：最大 64KB，超出部分自动丢弃
- 心跳协议：内部使用 `##HB##` / `##HB_ACK##` 作为心跳探测/响应，对业务层完全透明
- 文件传输协议：内部使用 `##FILE_START##` / `##FILE_DATA##` / `##FILE_END##` 传输文件，Base64 分块编码，对业务层完全透明
  - 开始：`##FILE_START##文件名##文件大小`
  - 数据：`##FILE_DATA##base64编码数据`（每块 32KB 原始数据）
  - 结束：`##FILE_END##md5校验值`（接收完成后自动校验 MD5）

---

## TCP 客户端

### TcpClientFactory

TCP 客户端工厂类，支持链式配置、自动重连、线程安全发送。所有回调在 Android 主线程执行。

#### 配置方法

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `setServerIp(String ip)` | 设置服务端 IP 地址 | `"127.0.0.1"` |
| `setServerPort(int port)` | 设置服务端端口（1-65535） | `8088` |
| `setConnectTimeout(int ms)` | 设置连接超时（毫秒），0 表示无限等待 | `10000` |
| `setSoTimeout(int ms)` | 设置读取超时（毫秒），0 表示无限等待 | `0` |
| `setReconnect(int maxCount, long intervalMs)` | 设置重连策略，maxCount=0 不重连，-1 无限重连 | `0, 3000` |
| `setHeartbeat(long intervalMs, long timeoutMs)` | 设置心跳参数，0 表示禁用。timeout 必须大于 interval | `0, 0` |
| `setFileSaveDir(File dir)` | 设置文件接收保存目录，未设置则不支持接收文件 | `null` |

> 所有配置方法仅在客户端未启动时可调用，运行中调用将抛出 `IllegalStateException`。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `startClient(TcpClientListener listener)` | 启动客户端，开始连接服务端 |
| `stopClient()` | 停止客户端，断开连接并停止重连 |
| `sendMessage(String message)` | 发送消息（线程安全），返回是否提交成功 |
| `sendFile(String filePath)` | 发送文件（线程安全），自动分块 Base64 编码传输 |
| `isConnected()` | 查询当前是否已连接 |
| `release()` | 释放所有资源，清除回调引用，防止 Activity 泄漏 |

#### 使用示例

```java
// 创建并配置客户端
TcpClientFactory client = new TcpClientFactory()
        .setServerIp("192.168.1.100")
        .setServerPort(9090)
        .setConnectTimeout(5000)
        .setSoTimeout(30000)
        .setReconnect(-1, 5000)   // 无限重连，间隔 5 秒
        .setHeartbeat(15000, 45000);  // 15秒发一次心跳，45秒无响应则断开

// 启动连接
client.startClient(new TcpClientListener() {
    @Override
    public void onConnected() {
        Log.d("TCP", "已连接到服务端");
        client.sendMessage("Hello Server");
    }

    @Override
    public void onDisconnected() {
        Log.d("TCP", "连接已断开");
    }

    @Override
    public void onReceiveData(String data) {
        Log.d("TCP", "收到消息: " + data);
    }

    @Override
    public void onError(String errorMessage) {
        Log.e("TCP", "错误: " + errorMessage);
    }

    @Override
    public void onStopped() {
        Log.d("TCP", "客户端已完全停止");
    }
});

// 发送消息
client.sendMessage("Hello");

// 停止客户端
client.stopClient();

// 在 Activity/Fragment 的 onDestroy 中释放资源
client.release();
```

### TcpClientListener

客户端回调接口，所有回调均在主线程执行。

| 回调 | 说明 |
|------|------|
| `onConnected()` | 成功连接到服务端 |
| `onDisconnected()` | 与服务端断开连接（可能触发重连） |
| `onReceiveData(String data)` | 收到服务端发来的一行数据 |
| `onError(String errorMessage)` | 发生错误（连接失败、发送失败等） |
| `onStopped()` | 客户端完全停止，不会再重连。终态回调，适合做最终清理 |
| `onFileProgress(String fileName, int progress)` | 文件接收进度（0-100），fileName 为原始文件名 |
| `onFileReceived(String filePath, String originalFileName)` | 文件接收完成并通过 MD5 校验，filePath 为本地保存路径 |
| `onFileError(String fileName, String errorMessage)` | 文件传输出错（校验失败、写入失败等） |

#### 回调顺序

```
正常流程：    onConnected → onReceiveData* → onDisconnected → onStopped
重连流程：    onConnected → onDisconnected → onConnected → ... → onStopped
连接失败：    onError → onStopped
主动停止：    onDisconnected（如已连接） → onStopped
```

> `onStopped` 是终态回调，之后不会再有任何回调。

---

## TCP 服务端

### TcpServerFactory

TCP 服务端工厂类，支持多客户端并发连接、广播消息、指定客户端通信。所有回调在 Android 主线程执行。

#### 配置方法

| 方法 | 说明 | 默认值 |
|------|------|--------|
| `setServerPort(int port)` | 设置监听端口（1-65535） | `8088` |
| `setMaxClients(int max)` | 设置最大客户端连接数 | `50` |
| `setClientSoTimeout(int ms)` | 设置客户端读超时（毫秒），用于检测死连接，0 表示无限等待 | `300000`（5分钟） |
| `setHeartbeat(long intervalMs, long timeoutMs)` | 设置心跳参数，0 表示禁用。超时无响应自动踢掉客户端 | `0, 0` |
| `setFileSaveDir(File dir)` | 设置文件接收保存目录，未设置则不支持接收文件 | `null` |

> 所有配置方法仅在服务端未启动时可调用，运行中调用将抛出 `IllegalStateException`。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `startServer(TcpServerListener listener)` | 启动服务端，开始监听端口 |
| `stopServer()` | 停止服务端，断开所有客户端 |
| `sendMessage(String clientId, String message)` | 向指定客户端发送消息，返回是否提交成功 |
| `sendFile(String clientId, String filePath)` | 向指定客户端发送文件，自动分块 Base64 编码传输 |
| `broadcastMessage(String message)` | 向所有已连接客户端广播消息 |
| `disconnectClient(String clientId)` | 断开指定客户端 |
| `getClientCount()` | 获取当前连接的客户端数量 |
| `getConnectedClientIds()` | 获取所有已连接客户端的 ID 集合 |
| `getClientAddress(String clientId)` | 获取指定客户端的远程 IP 地址 |
| `isRunning()` | 查询服务端是否在运行 |
| `release()` | 释放所有资源，清除回调引用，防止 Activity 泄漏 |

#### 使用示例

```java
// 创建并配置服务端
TcpServerFactory server = new TcpServerFactory()
        .setServerPort(9090)
        .setMaxClients(20)
        .setClientSoTimeout(60000)
        .setHeartbeat(15000, 45000);  // 15秒心跳间隔，45秒无响应踢掉客户端

// 启动服务端
server.startServer(new TcpServerListener() {
    @Override
    public void onServerStarted(int port) {
        Log.d("TCP", "服务端已启动，监听端口: " + port);
    }

    @Override
    public void onServerStopped() {
        Log.d("TCP", "服务端已停止");
    }

    @Override
    public void onClientConnected(String clientId) {
        Log.d("TCP", "客户端已连接: " + clientId);
        server.sendMessage(clientId, "Welcome!");
    }

    @Override
    public void onClientDisconnected(String clientId) {
        Log.d("TCP", "客户端已断开: " + clientId);
    }

    @Override
    public void onReceiveData(String clientId, String data) {
        Log.d("TCP", "收到 " + clientId + " 的消息: " + data);
        // 回复消息
        server.sendMessage(clientId, "Echo: " + data);
        // 广播给所有客户端
        server.broadcastMessage(clientId + " says: " + data);
    }

    @Override
    public void onError(String errorMessage) {
        Log.e("TCP", "服务端错误: " + errorMessage);
    }
});

// 向指定客户端发送消息
server.sendMessage("client_1", "Hello Client");

// 广播消息
server.broadcastMessage("Server announcement");

// 断开指定客户端
server.disconnectClient("client_1");

// 停止服务端
server.stopServer();

// 在 Activity/Fragment 的 onDestroy 中释放资源
server.release();
```

### TcpServerListener

服务端回调接口，所有回调均在主线程执行。

| 回调 | 说明 |
|------|------|
| `onServerStarted(int port)` | 服务端启动成功，返回监听端口 |
| `onServerStopped()` | 服务端已停止。终态回调 |
| `onClientConnected(String clientId)` | 新客户端连接，clientId 格式为 `client_N` |
| `onClientDisconnected(String clientId)` | 客户端断开连接 |
| `onReceiveData(String clientId, String data)` | 收到指定客户端发来的一行数据 |
| `onError(String errorMessage)` | 发生错误（端口绑定失败、IO 异常等） |
| `onFileProgress(String clientId, String fileName, int progress)` | 文件接收进度（0-100） |
| `onFileReceived(String clientId, String filePath, String originalFileName)` | 文件接收完成并通过 MD5 校验 |
| `onFileError(String clientId, String fileName, String errorMessage)` | 文件传输出错 |

#### 回调顺序

```
正常流程：    onServerStarted → onClientConnected* → onReceiveData* → onClientDisconnected* → onServerStopped
主动停止：    onClientDisconnected*（逐个通知） → onServerStopped
```

> `onServerStopped` 之前，所有客户端的 `onClientDisconnected` 会先被回调。

---

## 注意事项

1. **生命周期管理**：在 `Activity.onDestroy()` 或 `Fragment.onDestroyView()` 中务必调用 `release()`，防止内存泄漏。
2. **线程安全**：`sendMessage()` / `broadcastMessage()` 可在任何线程调用，内部通过线程池异步发送。
3. **快速重启**：支持 `stopClient() → startClient()` 或 `stopServer() → startServer()` 快速重启，内部通过会话隔离机制保证线程安全。
4. **配置时机**：所有 `set` 方法仅在未启动时调用，运行中修改配置会抛出 `IllegalStateException`。
5. **消息格式**：消息以换行符分隔，`sendMessage()` 内部使用 `println()` 自动追加换行符，接收端自动按行解析。
6. **心跳机制**：建议在局域网场景下启用心跳（`setHeartbeat`），推荐参数：`intervalMs=15000, timeoutMs=45000`。心跳消息对业务层完全透明，不会触发 `onReceiveData` 回调。
7. **无限重连**：局域网安卓主板场景建议使用 `setReconnect(-1, 5000)` 开启无限重连，确保设备重启后自动恢复连接。
8. **文件传输**：支持发送图片、APK、ZIP 等任意文件。文件通过 Base64 分块编码传输（每块 32KB），接收端自动还原并校验 MD5。使用前需调用 `setFileSaveDir()` 设置接收目录。文件传输不会触发 `onReceiveData` 回调。

---

## 文件传输示例

### 客户端发送文件

```java
TcpClientFactory client = new TcpClientFactory()
        .setServerIp("192.168.1.100")
        .setServerPort(9090)
        .setFileSaveDir(new File(getExternalFilesDir(null), "received"));

client.startClient(new TcpClientListener() {
    @Override
    public void onConnected() {
        // 发送文件
        client.sendFile("/sdcard/Download/photo.jpg");
    }

    @Override
    public void onFileProgress(String fileName, int progress) {
        Log.d("TCP", "接收文件 " + fileName + " 进度: " + progress + "%");
    }

    @Override
    public void onFileReceived(String filePath, String originalFileName) {
        Log.d("TCP", "文件接收完成: " + filePath);
    }

    @Override
    public void onFileError(String fileName, String errorMessage) {
        Log.e("TCP", "文件传输失败: " + fileName + " - " + errorMessage);
    }

    // ... 其他回调
});
```

### 服务端发送文件

```java
TcpServerFactory server = new TcpServerFactory()
        .setServerPort(9090)
        .setFileSaveDir(new File(getExternalFilesDir(null), "received"));

server.startServer(new TcpServerListener() {
    @Override
    public void onClientConnected(String clientId) {
        // 向客户端发送文件
        server.sendFile(clientId, "/sdcard/Download/update.apk");
    }

    @Override
    public void onFileProgress(String clientId, String fileName, int progress) {
        Log.d("TCP", clientId + " 接收文件 " + fileName + " 进度: " + progress + "%");
    }

    @Override
    public void onFileReceived(String clientId, String filePath, String originalFileName) {
        Log.d("TCP", clientId + " 文件接收完成: " + filePath);
    }

    @Override
    public void onFileError(String clientId, String fileName, String errorMessage) {
        Log.e("TCP", clientId + " 文件传输失败: " + fileName + " - " + errorMessage);
    }

    // ... 其他回调
});
```
