package com.github.obhen233.jdtls;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket ↔ LSP4J 桥接。
 *
 * vscode-ws-jsonrpc (前端) 发送的是纯 JSON 消息（无 Content-Length header），
 * 但 LSP4J 的 StreamMessageProducer 需要 Content-Length header。
 * 因此：
 *   前端 → 后端：给 JSON 加上 Content-Length header 再写入管道
 *   后端 → 前端：从管道读取带 header 的消息，剥离 header 后发送纯 JSON
 *
 * 使用基于 BlockingQueue 的流替代 PipedInputStream/PipedOutputStream，
 * 彻底避免 "Read end dead" 问题（PipedInputStream 会跟踪写入线程，
 * 当 WebSocket I/O 线程被回收或切换时，读取端误判写入端已死）。
 */
public class JdtLsSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(JdtLsSocketHandler.class);

    private final LanguageServer languageServer;

    private QueueOutputStream clientToServerOut;
    private QueueInputStream clientToServerIn;
    private QueueInputStream serverToClientIn;
    private QueueOutputStream serverToClientOut;
    private Future<?> listeningFuture;
    private Thread readerThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public JdtLsSocketHandler(LanguageServer languageServer) {
        this.languageServer = languageServer;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 如果之前有连接残留，先清理
        cleanup();

        // 前端 → LSP 服务端（共享同一个阻塞队列）
        BlockingQueue<byte[]> clientToServerQueue = new LinkedBlockingQueue<>();
        clientToServerOut = new QueueOutputStream(clientToServerQueue);
        clientToServerIn = new QueueInputStream(clientToServerQueue);

        // LSP 服务端 → 前端（共享同一个阻塞队列）
        BlockingQueue<byte[]> serverToClientQueue = new LinkedBlockingQueue<>();
        serverToClientOut = new QueueOutputStream(serverToClientQueue);
        serverToClientIn = new QueueInputStream(serverToClientQueue);

        Launcher<LanguageClient> launcher = Launcher.createLauncher(
                languageServer,
                LanguageClient.class,
                clientToServerIn,
                serverToClientOut
        );

        if (languageServer instanceof SimpleLanguageServer simple) {
            simple.connect(launcher.getRemoteProxy());
        }

        listeningFuture = launcher.startListening();
        connected.set(true);

        // 后台虚拟线程：读取 LSP 响应，剥离 Content-Length header，发送纯 JSON 给前端
        // （阻塞 I/O 密集，使用虚拟线程，节省平台线程资源）
        readerThread = Thread.ofVirtual()
                .name("lsp-to-ws-" + session.getId())
                .start(() -> {
                    try {
                        forwardLspToWebSocket(session);
                    } catch (Exception e) {
                        // 连接关闭时正常退出，不打印错误
                        if (connected.get()) {
                            log.error("[JDT.LS] Reader thread error: {}", e.getMessage());
                        }
                    }
                });

        log.info("[JDT.LS] WebSocket connected, LSP bridge started");
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (!connected.get() || clientToServerOut == null) {
            return;
        }
        // 前端发来的是纯 JSON（vscode-ws-jsonrpc 不带 Content-Length header）
        // 需要加上 Content-Length header 后写入管道给 LSP4J
        String json = message.getPayload();
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + jsonBytes.length + "\r\n\r\n";

        try {
            clientToServerOut.write(header.getBytes(StandardCharsets.UTF_8));
            clientToServerOut.write(jsonBytes);
            clientToServerOut.flush();
        } catch (IOException e) {
            if (connected.get()) {
                log.error("[JDT.LS] Write to pipe failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("[JDT.LS] WebSocket closed: {}", status);
        cleanup();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("[JDT.LS] WebSocket transport error: {}", exception.getMessage());
        cleanup();
    }

    /**
     * 从 LSP4J 输出流读取带 Content-Length header 的消息，
     * 剥离 header 后将纯 JSON 通过 WebSocket 发送给前端。
     */
    private void forwardLspToWebSocket(WebSocketSession session) throws Exception {
        InputStream in = serverToClientIn;

        while (connected.get() && session.isOpen()) {
            // 1. 读取 headers
            int contentLength = -1;
            StringBuilder headerLine = new StringBuilder();

            while (true) {
                int b;
                try {
                    b = in.read();
                } catch (IOException e) {
                    return;
                }
                if (b == -1) return;

                if (b == '\r') {
                    int next;
                    try {
                        next = in.read();
                    } catch (IOException e) {
                        return;
                    }
                    if (next == '\n') {
                        String line = headerLine.toString();
                        if (line.isEmpty()) {
                            break; // 空行 = header 结束
                        }
                        if (line.startsWith("Content-Length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                        headerLine.setLength(0);
                    }
                } else {
                    headerLine.append((char) b);
                }
            }

            if (contentLength <= 0) break;

            // 2. 读取 JSON body
            byte[] body = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read;
                try {
                    read = in.read(body, totalRead, contentLength - totalRead);
                } catch (IOException e) {
                    return;
                }
                if (read == -1) break;
                totalRead += read;
            }

            // 3. 发送纯 JSON 给前端（不带 Content-Length header）
            if (connected.get() && session.isOpen()) {
                String jsonBody = new String(body, 0, totalRead, StandardCharsets.UTF_8);
                try {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(jsonBody));
                    }
                } catch (IOException e) {
                    return;
                }
            }
        }
    }

    private void cleanup() {
        connected.set(false);

        // 1. 先关闭流，注入 EOF 信号，让阻塞在 QueueInputStream.read() 上的线程正常退出
        closeQuietly(clientToServerOut);
        closeQuietly(clientToServerIn);
        closeQuietly(serverToClientOut);
        closeQuietly(serverToClientIn);

        // 2. 取消 LSP4J launcher 的监听线程（此时流已关闭，不会触发 InterruptedException 异常链）
        if (listeningFuture != null) {
            listeningFuture.cancel(true);
            listeningFuture = null;
        }

        // 3. 中断 reader 线程（此时流已关闭，不会触发 InterruptedException 异常链）
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }

        clientToServerOut = null;
        clientToServerIn = null;
        serverToClientOut = null;
        serverToClientIn = null;
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    // ==================== 基于 BlockingQueue 的流实现 ====================

    /**
     * 基于 BlockingQueue 的 OutputStream。
     * 不跟踪写入线程，任何线程都可以安全写入，不会触发 "Read end dead"。
     */
    static class QueueOutputStream extends OutputStream {
        private final BlockingQueue<byte[]> queue;
        private volatile boolean closed = false;

        QueueOutputStream(BlockingQueue<byte[]> queue) {
            this.queue = queue;
        }

        @Override
        public void write(int b) throws IOException {
            if (closed) throw new IOException("Stream closed");
            try {
                queue.put(new byte[]{(byte) b});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("Stream closed");
            byte[] copy = new byte[len];
            System.arraycopy(b, off, copy, 0, len);
            try {
                queue.put(copy);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }

        @Override
        public void close() {
            closed = true;
            // 放入空数组作为 EOF 信号
            queue.offer(new byte[0]);
        }
    }

    /**
     * 基于 BlockingQueue 的 InputStream。
     * 从队列中取出字节块，逐字节返回给调用方。
     * 不依赖线程存活状态，彻底避免 "Read end dead"。
     */
    static class QueueInputStream extends InputStream {
        private final BlockingQueue<byte[]> queue;
        private byte[] current;
        private int pos;
        private volatile boolean closed = false;

        QueueInputStream(BlockingQueue<byte[]> queue) {
            this.queue = queue;
        }

        @Override
        public int read() throws IOException {
            while (true) {
                if (closed) return -1;
                if (current != null && pos < current.length) {
                    return current[pos++] & 0xFF;
                }
                // 需要从队列取下一个块
                try {
                    current = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    // 如果流已关闭或被标记为中断，直接返回 EOF，
                    // 避免 LSP4J 收到 IOException 后抛出 JsonRpcException
                    if (closed) return -1;
                    throw new IOException("Interrupted", e);
                }
                if (current.length == 0) {
                    // EOF 信号
                    closed = true;
                    return -1;
                }
                pos = 0;
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            // 先读一个字节（阻塞等待数据）
            int first = read();
            if (first == -1) return -1;
            b[off] = (byte) first;
            int count = 1;
            // 尽量多读（非阻塞），填满 buffer
            while (count < len) {
                if (current != null && pos < current.length) {
                    int available = current.length - pos;
                    int toCopy = Math.min(available, len - count);
                    System.arraycopy(current, pos, b, off + count, toCopy);
                    pos += toCopy;
                    count += toCopy;
                } else {
                    // 尝试非阻塞取下一个块
                    current = queue.poll();
                    if (current == null || current.length == 0) {
                        if (current != null && current.length == 0) closed = true;
                        break;
                    }
                    pos = 0;
                }
            }
            return count;
        }

        @Override
        public int available() {
            if (current != null && pos < current.length) {
                return current.length - pos;
            }
            return 0;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
