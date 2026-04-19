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
├── TcpServerFactory.java      // TCP 服务端
└── TcpServerListener.java     // 服务端回调接口
```

## 通信协议

- 传输编码：UTF-8
- 消息分隔：以换行符 `\n` 作为消息边界（兼容 `\r\n`）
- 单行限制：最大 64KB，超出部分自动丢弃

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
| `setReconnect(int maxCount, long intervalMs)` | 设置重连策略，maxCount=0 表示不重连 | `0, 3000` |

> 所有配置方法仅在客户端未启动时可调用，运行中调用将抛出 `IllegalStateException`。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `startClient(TcpClientListener listener)` | 启动客户端，开始连接服务端 |
| `stopClient()` | 停止客户端，断开连接并停止重连 |
| `sendMessage(String message)` | 发送消息（线程安全），返回是否提交成功 |
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
        .setReconnect(3, 5000);  // 最多重连 3 次，间隔 5 秒

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

> 所有配置方法仅在服务端未启动时可调用，运行中调用将抛出 `IllegalStateException`。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `startServer(TcpServerListener listener)` | 启动服务端，开始监听端口 |
| `stopServer()` | 停止服务端，断开所有客户端 |
| `sendMessage(String clientId, String message)` | 向指定客户端发送消息，返回是否提交成功 |
| `broadcastMessage(String message)` | 向所有已连接客户端广播消息 |
| `disconnectClient(String clientId)` | 断开指定客户端 |
| `getClientCount()` | 获取当前连接的客户端数量 |
| `isRunning()` | 查询服务端是否在运行 |
| `release()` | 释放所有资源，清除回调引用，防止 Activity 泄漏 |

#### 使用示例

```java
// 创建并配置服务端
TcpServerFactory server = new TcpServerFactory()
        .setServerPort(9090)
        .setMaxClients(20)
        .setClientSoTimeout(60000);  // 1 分钟无数据则认为死连接

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
