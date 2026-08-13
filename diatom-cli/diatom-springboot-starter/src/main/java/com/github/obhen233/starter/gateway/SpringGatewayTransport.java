package com.github.obhen233.starter.gateway;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 Spring Web 的 Gateway 传输层
 * 用于 Gateway → Worker 的 HTTP 通信
 */
public class SpringGatewayTransport {
    private static final Logger logger = LoggerFactory.getLogger(SpringGatewayTransport.class);

    private final WorkerRegistry registry;

    public SpringGatewayTransport(WorkerRegistry registry) {
        this.registry = registry;
    }

    /**
     * 发送聊天请求到指定 Worker，仅在 HTTP 2xx 时返回响应体，否则返回 null。
     * <p>如需区分 503 过载与普通错误，请使用 {@link #sendChatRequestResult}。</p>
     */
    public String sendChatRequest(String workerId, String requestBody) {
        HttpResult result = sendChatRequestResult(workerId, requestBody);
        return (result != null && result.isSuccess()) ? result.getBody() : null;
    }

    /**
     * 发送聊天请求到指定 Worker，返回带 HTTP 状态码的结果。
     * <p>Gateway 侧据此识别 503/429（Worker 过载）并转排队或换 worker 重试。</p>
     */
    public HttpResult sendChatRequestResult(String workerId, String requestBody) {
        WorkerInfo worker = registry.getWorker(workerId);
        if (worker == null) {
            logger.warn("Worker not found: {}", workerId);
            return new HttpResult(-1, null);
        }
        return httpPostResult(worker.getBaseUrl() + "/worker/v1/chat", requestBody);
    }

    /**
     * 发送取消请求到指定 Worker
     */
    public boolean sendCancelRequest(String workerId, String taskId) {
        WorkerInfo worker = registry.getWorker(workerId);
        if (worker == null) return false;
        Map<String, Object> cancelBody = new LinkedHashMap<>();
        cancelBody.put("taskId", taskId);
        String body = com.github.obhen233.util.JsonUtils.toJson(cancelBody);
        String response = httpPost(worker.getBaseUrl() + "/worker/v1/cancel", body);
        return response != null;
    }

    /** 连接超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 读取超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 600_000;

    private String httpPost(String urlStr, String body) {
        HttpResult result = httpPostResult(urlStr, body);
        return (result != null && result.isSuccess()) ? result.getBody() : null;
    }

    private HttpResult httpPostResult(String urlStr, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();

            // 读取完整响应（支持长文本），错误流也可能有内容（如 503 过载原因）
            try (InputStream is = (code >= 200 && code < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream()) {
                return new HttpResult(code, readFully(is));
            }
        } catch (Exception e) {
            logger.warn("HTTP POST failed to {}: {}", urlStr, e.getMessage());
            return new HttpResult(-1, null);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * HTTP 请求结果：携带状态码与响应体，供 Gateway 侧识别过载（429/503）等状态。
     */
    public static class HttpResult {
        private final int statusCode;
        private final String body;

        HttpResult(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }
        public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
        public boolean isOverloaded() { return statusCode == 429 || statusCode == 503; }
        public boolean isError() { return statusCode >= 300; }
    }

    /**
     * 从 InputStream 读取全部字节并转为 UTF-8 字符串。
     */
    private static String readFully(InputStream is) throws java.io.IOException {
        if (is == null) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk, 0, chunk.length)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
