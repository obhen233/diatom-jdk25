package com.github.obhen233.core.http;

import com.github.obhen233.util.BuildInfo;
import com.github.obhen233.util.JsonUtils;
import okhttp3.*;
import okhttp3.internal.http2.StreamResetException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AiHttpClient {
    private static final Logger logger = LoggerFactory.getLogger(AiHttpClient.class);
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 500;

    public enum AuthStyle {
        BEARER,     // Authorization: Bearer <key> (OpenAI, DeepSeek, etc.)
        ANTHROPIC   // x-api-key: <key> + anthropic-version header
    }

    private final OkHttpClient client;
    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile AuthStyle authStyle;
    private volatile String userAgent = "Diatom-CLI/" + BuildInfo.getVersion();
    // Tracks whether a thinking content block is currently in progress
    private volatile boolean thinkingInProgress = false;
    // Set to true when thinking is detected during this stream; never reset (for post-stream warnings)
    private volatile boolean hasSeenThinking = false;
    private final CircuitBreaker.Manager circuitBreakerManager;

    public AiHttpClient(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, AuthStyle.BEARER);
    }

    public AiHttpClient(String apiKey, String baseUrl, AuthStyle authStyle) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.authStyle = authStyle;
        this.circuitBreakerManager = new CircuitBreaker.Manager();
        ConnectionPool connectionPool = new ConnectionPool(5, 30, TimeUnit.SECONDS);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(connectionPool)
                .dispatcher(new Dispatcher(Executors.newVirtualThreadPerTaskExecutor()))
                .addInterceptor(new RetryInterceptor(connectionPool))
                .build();
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public AuthStyle getAuthStyle() { return authStyle; }
    public void setAuthStyle(AuthStyle authStyle) { this.authStyle = authStyle; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) {
        if (userAgent != null && !userAgent.isEmpty()) {
            this.userAgent = userAgent;
        }
    }

    public String post(String endpoint, String jsonBody) throws IOException {
        CircuitBreaker breaker = circuitBreakerManager.getForEndpoint(endpoint);

        try {
            return breaker.execute(() -> doPost(endpoint, jsonBody));
        } catch (CircuitBreaker.CircuitOpenException e) {
            throw new IOException(e.getMessage());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Request failed: " + e.getMessage(), e);
        }
    }

    private String doPost(String endpoint, String jsonBody) throws IOException {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", userAgent)
                .post(body);

        // Apply auth headers based on style
        if (authStyle == AuthStyle.ANTHROPIC) {
            requestBuilder.addHeader("x-api-key", apiKey);
            requestBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            String responseBody = "";
            try {
                responseBody = response.body() != null ? response.body().string() : "";
            } catch (IllegalStateException e) {
                // Response body was already consumed or closed during retry handling
                // Return empty body to let caller handle the error
                logger.warn("Response body already consumed, status: {}", response.code());
            }
            logger.debug("Response status: {}, body: {}", response.code(), responseBody);
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response.code() + ", body: " + responseBody);
            }
            return responseBody;
        }
    }

    public void postStream(String endpoint, String jsonBody, StreamCallback callback) {
        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("User-Agent", userAgent)
                .post(body);

        if (authStyle == AuthStyle.ANTHROPIC) {
            requestBuilder.addHeader("x-api-key", apiKey);
            requestBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("Unexpected code " + response));
                    return;
                }
                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) return;
                    java.io.BufferedReader reader = new java.io.BufferedReader(responseBody.charStream());
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data:")) {
                            // Handle both "data: {...}" and "data:{...}" (with or without space)
                            String data = line.length() > 5 ? line.substring(5).trim() : "";
                            if ("[DONE]".equals(data)) break;
                            callback.onData(data);
                        }
                    }
                }
            }
        });
    }

    public interface StreamCallback {
        void onData(String data);
        void onError(Throwable t);
    }

    /**
     * Enhanced stream consumer interface for real-time output
     */
    public interface StreamConsumer {
        /**
         * Called for each token received
         */
        void onToken(String token);

        /**
         * Called when stream completes with full response
         */
        void onComplete(String fullResponse);

        /**
         * Called for raw SSE data (optional)
         */
        default void onData(String data) {}

        /**
         * Called when token usage info is available from the stream
         * (optional — default no-op). Used by AtomicAgentLoop to track
         * token usage in streaming responses where usage info comes in
         * dedicated SSE events (e.g. OpenAI stream_options, Anthropic message_delta).
         */
        default void onUsage(long promptTokens, long completionTokens, long totalTokens) {}

        /**
         * Called when an error occurs
         */
        void onError(Throwable e);
    }

    /**
     * Synchronous streaming POST - blocks until stream completes.
     * Tokens are passed to consumer as they arrive.
     */
    public String postStreamSync(String endpoint, String jsonBody, StreamConsumer consumer) throws IOException {
        CircuitBreaker breaker = circuitBreakerManager.getForEndpoint(endpoint);

        // Check circuit breaker state
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            throw new IOException("Circuit breaker is open for endpoint: " + endpoint);
        }

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Accept", "text/event-stream")
                .addHeader("User-Agent", userAgent)
                .post(body);

        if (authStyle == AuthStyle.ANTHROPIC) {
            requestBuilder.addHeader("x-api-key", apiKey);
            requestBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "";
                logger.error("API response not successful: code={}, body='{}'", response.code(), errorBody);
                throw new IOException("Unexpected code " + response.code() + ", body: " + errorBody);
            }

            StringBuilder fullResponse = new StringBuilder();
            // Track streaming token usage from SSE data events
            // OpenAI: last chunk has {"choices":[],"usage":{"prompt_tokens":X,"completion_tokens":Y,"total_tokens":Z}}
            // Anthropic: message_start.usage.input_tokens + message_delta.usage.output_tokens
            final long[] streamPromptTokens = {0};
            final long[] streamCompletionTokens = {0};
            try (ResponseBody responseBody = response.body()) {
                if (responseBody == null) {
                    consumer.onComplete(fullResponse.toString());
                    return fullResponse.toString();
                }
                java.io.BufferedReader reader = new java.io.BufferedReader(responseBody.charStream());
                String line;
                boolean isSseFormat = false;
                int lineCount = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) continue; // Skip empty lines
                    lineCount++;
                    if (lineCount <= 5) {
                        logger.debug("SSE raw line #{}: {}", lineCount, line.length() > 200 ? line.substring(0, 200) + "..." : line);
                    }

                    // Skip SSE protocol lines (event, id, retry) — these are metadata
                    // that describe the following data line, not content to be displayed.
                    if (line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
                        if (!isSseFormat) isSseFormat = true; // Mark as SSE-only, skip
                        continue;
                    }

                    if (line.startsWith("data:")) {
                        isSseFormat = true;
                        // Handle both "data: {...}" and "data:{...}" (with or without space)
                        String data = line.length() > 5 ? line.substring(5).trim() : "";
                        if ("[DONE]".equals(data)) break;

                        // Check for API error response embedded in SSE data
                        checkStreamError(data);

                        // Parse the SSE data to extract content based on API format
                        String content = extractContentFromSSE(data);
                        if (content != null && !content.isEmpty()) {
                            consumer.onToken(content);
                            fullResponse.append(content);
                        } else if (data.length() < 500 && !isExpectedNonContentEvent(data)) {
                            logger.debug("extractContent returned null for data: {}", data);
                        }
                        // Only call onData if there's meaningful raw data to pass
                        consumer.onData(data);
                        // Track streaming token usage from SSE metadata events
                        detectStreamUsage(data, streamPromptTokens, streamCompletionTokens);
                    } else if (line.startsWith("{")) {
                        // Handle JSON lines that don't have "data:" prefix (some servers)
                        isSseFormat = true;

                        // Check for API error response
                        checkStreamError(line);

                        String content = extractContentFromSSE(line);
                        if (content != null && !content.isEmpty()) {
                            consumer.onToken(content);
                            fullResponse.append(content);
                        }
                        consumer.onData(line);
                        detectStreamUsage(line, streamPromptTokens, streamCompletionTokens);
                        // Non-SSE plain text response (e.g., MiniMax streaming plain text)
                        // Treat the entire line as content
                                consumer.onToken(line);
                        fullResponse.append(line);
                    }
                }
                // Call onUsage if we detected any token usage from the stream
                if (streamPromptTokens[0] > 0 || streamCompletionTokens[0] > 0) {
                    consumer.onUsage(streamPromptTokens[0], streamCompletionTokens[0],
                        streamPromptTokens[0] + streamCompletionTokens[0]);
                }
                // Call onComplete BEFORE any post-stream logging to avoid
                // interleaving streaming output (stdout) with log messages (stderr)
                consumer.onComplete(fullResponse.toString());
                if (fullResponse.length() == 0 && hasSeenThinking) {
                    logger.warn("Stream ended with thinking content only ({} lines), no text/tool_use output",
                        lineCount);
                }
                logger.debug("SSE stream ended: totalLines={}, extractedLength={}", lineCount, fullResponse.length());
            }

            // Record success
            try {
                breaker.execute(() -> null);
            } catch (Exception ignored) {}

            return fullResponse.toString();
        } catch (IOException e) {
            // Record failure
            try {
                breaker.execute(() -> null);
            } catch (Exception ignored) {}
            consumer.onError(e);
            throw e;
        }
    }

    /**
     * Enhanced streaming with cancel support
     */
    public void postStreamEnhanced(String endpoint, String jsonBody, StreamConsumer consumer) {
        CircuitBreaker breaker = circuitBreakerManager.getForEndpoint(endpoint);

        // Check circuit breaker state
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            consumer.onError(new IOException("Circuit breaker is open for endpoint: " + endpoint));
            return;
        }

        RequestBody body = RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8"));
        Request.Builder requestBuilder = new Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Accept", "text/event-stream")
                .addHeader("User-Agent", userAgent)
                .post(body);

        if (authStyle == AuthStyle.ANTHROPIC) {
            requestBuilder.addHeader("x-api-key", apiKey);
            requestBuilder.addHeader("anthropic-version", "2023-06-01");
        } else {
            requestBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        Request request = requestBuilder.build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                try {
                    breaker.execute(() -> null); // Record failure
                } catch (Exception ignored) {}
                consumer.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    consumer.onError(new IOException("Unexpected code " + response.code()));
                    return;
                }

                StringBuilder fullResponse = new StringBuilder();
                try (ResponseBody responseBody = response.body()) {
                    if (responseBody == null) {
                        consumer.onComplete(fullResponse.toString());
                        return;
                    }
                    java.io.BufferedReader reader = new java.io.BufferedReader(responseBody.charStream());
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) continue;

                        // Skip SSE protocol lines (event, id, retry)
                        if (line.startsWith("event:") || line.startsWith("id:") || line.startsWith("retry:")) {
                            continue;
                        }

                        if (line.startsWith("data:")) {
                            // Handle both "data: {...}" and "data:{...}" (with or without space)
                            String data = line.length() > 5 ? line.substring(5).trim() : "";
                            if ("[DONE]".equals(data)) break;

                            // Check for API error response embedded in SSE data
                            checkStreamError(data);

                            // Parse the SSE data to extract content based on API format
                            String content = extractContentFromSSE(data);
                            if (content != null && !content.isEmpty()) {
                                consumer.onToken(content);
                                fullResponse.append(content);
                            }
                            consumer.onData(data);
                        } else if (line.startsWith("{")) {
                            // Check for API error response
                            checkStreamError(line);

                            String content = extractContentFromSSE(line);
                            if (content != null && !content.isEmpty()) {
                                consumer.onToken(content);
                                fullResponse.append(content);
                            }
                            consumer.onData(line);
                        }
                    }
                } catch (Exception e) {
                    consumer.onError(e);
                    return;
                }

                // Record success
                try {
                    breaker.execute(() -> null);
                } catch (Exception ignored) {}

                consumer.onComplete(fullResponse.toString());
            }
        });
    }

    /**
     * Check if a JSON response contains an API error.
     * Throws IOException if an error is detected (e.g., MiniMax base_resp with non-zero status_code,
     * or OpenAI format error field).
     */
    private void checkStreamError(String jsonData) throws IOException {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(jsonData);

            // MiniMax API error format: {"base_resp":{"status_code":1002,"status_msg":"rate limit exceeded"}}
            if (root.has("base_resp")) {
                com.fasterxml.jackson.databind.JsonNode baseResp = root.get("base_resp");
                if (baseResp.has("status_code")) {
                    int statusCode = baseResp.get("status_code").asInt();
                    if (statusCode != 0) {
                        String statusMsg = baseResp.has("status_msg") ? baseResp.get("status_msg").asText() : "Unknown error";
                        logger.error("API error in stream: [{}] {}", statusCode, statusMsg);
                        throw new IOException("API error [" + statusCode + "]: " + statusMsg);
                    }
                }
            }
            // OpenAI error format: {"error":{"message":"...","code":...}}
            if (root.has("error") && !root.get("error").isNull()) {
                com.fasterxml.jackson.databind.JsonNode error = root.get("error");
                String errorMsg = error.has("message") ? error.get("message").asText() : "Unknown error";
                logger.error("API error in stream: {}", errorMsg);
                throw new IOException("API error: " + errorMsg);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // Not a JSON error response, continue normally
        }
    }

    /**
     * Extract content from SSE data based on API format (OpenAI or Anthropic)
     * Returns the extracted content text, or null if no content found.
     */
    private String extractContentFromSSE(String data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(data);

            if (authStyle == AuthStyle.ANTHROPIC) {
                // Anthropic SSE format: {"type": "content_block", "index": 0, "content_block": {"type": "text", "text": "..."}}
                // or: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "..."}}
                // or: {"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": "..."}}
                // or: {"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "..."}}
                if (root.has("type")) {
                    String type = root.get("type").asText();
                    if ("content_block_start".equals(type)) {
                        com.fasterxml.jackson.databind.JsonNode contentBlock = root.get("content_block");
                        if (contentBlock != null && "thinking".equals(contentBlock.get("type").asText())) {
                            thinkingInProgress = true;
                            hasSeenThinking = true;
                            return null;
                        }
                    } else if ("content_block_delta".equals(type)) {
                        com.fasterxml.jackson.databind.JsonNode delta = root.get("delta");
                        if (delta != null) {
                            String deltaType = delta.get("type").asText();
                            if ("text_delta".equals(deltaType)) {
                                return delta.get("text").asText();
                            } else if ("thinking_delta".equals(deltaType)) {
                                thinkingInProgress = true;
                                hasSeenThinking = true;
                                return null;
                            }
                        }
                    } else if ("content_block_stop".equals(type)) {
                        if (thinkingInProgress) {
                            thinkingInProgress = false;
                        }
                        return null;
                    } else if ("content_block".equals(type)) {
                        com.fasterxml.jackson.databind.JsonNode contentBlock = root.get("content_block");
                        if (contentBlock != null && "text".equals(contentBlock.get("type").asText())) {
                            return contentBlock.get("text").asText();
                        }
                    }
                }
            } else {
                // Responses API SSE: response.output_text.delta carries the delta text
                if (isResponsesSse(root)) {
                    String type = root.has("type") ? root.get("type").asText() : "";
                    if ("response.output_text.delta".equals(type)) {
                        return root.has("delta") ? root.get("delta").asText() : null;
                    }
                    return null;
                }
                // OpenAI format: choices[0].delta.content
                if (root.has("choices")) {
                    com.fasterxml.jackson.databind.JsonNode choices = root.get("choices");
                    if (choices.isArray() && choices.size() > 0) {
                        com.fasterxml.jackson.databind.JsonNode delta = choices.get(0).get("delta");
                        if (delta != null && delta.has("content")) {
                            return delta.get("content").asText();
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to extract content from SSE: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if an SSE JSON event belongs to the OpenAI Responses API stream.
     * Responses events carry a {@code type} starting with {@code "response."}.
     */
    private boolean isResponsesSse(com.fasterxml.jackson.databind.JsonNode root) {
        if (root == null || !root.has("type")) return false;
        String type = root.get("type").asText();
        return type != null && type.startsWith("response.");
    }

    /**
     * Check if the SSE data is an expected non-content event (Anthropic format).
     * These events always return null from extractContentFromSSE and don't need debug logging,
     * avoiding interleaving between streaming output and log messages.
     */
    private boolean isExpectedNonContentEvent(String data) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(data);
            // Responses API: every event except output_text.delta is expected non-content
            if (isResponsesSse(root)) {
                String type = root.has("type") ? root.get("type").asText() : "";
                return !"response.output_text.delta".equals(type);
            }
            if (authStyle == AuthStyle.ANTHROPIC && root.has("type")) {
                String type = root.get("type").asText();
                return "content_block_stop".equals(type)
                    || "content_block_start".equals(type)
                    || "content_block_delta".equals(type)
                    || "message_delta".equals(type)
                    || "message_stop".equals(type)
                    || "ping".equals(type);
            }
        } catch (Exception e) {
            // ignore parse errors
        }
        return false;
    }

    /**
     * Extract token usage info from SSE metadata events.
     * For Anthropic: message_start.usage.input_tokens + message_delta.usage.output_tokens
     * For OpenAI: final chunk with {"usage":{"prompt_tokens":X,"completion_tokens":Y,"total_tokens":Z}}
     *
     * @param data        raw SSE data JSON string
     * @param promptOut   single-element array to receive prompt token count
     * @param completionOut single-element array to receive completion token count
     */
    private void detectStreamUsage(String data, long[] promptOut, long[] completionOut) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(data);
            if (authStyle == AuthStyle.ANTHROPIC) {
                if (root.has("type")) {
                    String type = root.get("type").asText();
                    if ("message_start".equals(type)) {
                        com.fasterxml.jackson.databind.JsonNode msg = root.get("message");
                        if (msg != null && msg.has("usage") && msg.get("usage").has("input_tokens")) {
                            promptOut[0] = msg.get("usage").get("input_tokens").asLong();
                        }
                    } else if ("message_delta".equals(type)) {
                        if (root.has("usage") && root.get("usage").has("output_tokens")) {
                            completionOut[0] = root.get("usage").get("output_tokens").asLong();
                        }
                    }
                }
            } else {
                // Responses API SSE: response.completed carries usage.input_tokens/output_tokens/total_tokens
                if (isResponsesSse(root)) {
                    String type = root.has("type") ? root.get("type").asText() : "";
                    if ("response.completed".equals(type) && root.has("response")) {
                        com.fasterxml.jackson.databind.JsonNode resp = root.get("response");
                        com.fasterxml.jackson.databind.JsonNode usage = resp.get("usage");
                        if (usage != null) {
                            promptOut[0] = usage.has("input_tokens") ? usage.get("input_tokens").asLong() : 0;
                            completionOut[0] = usage.has("output_tokens") ? usage.get("output_tokens").asLong() : 0;
                        }
                    }
                    return;
                }
                if (root.has("usage") && root.get("usage").has("total_tokens")) {
                    com.fasterxml.jackson.databind.JsonNode usage = root.get("usage");
                    promptOut[0] = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : 0;
                    completionOut[0] = usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : 0;
                }
            }
        } catch (Exception ignored) {
            // not a JSON event or no usage info — ignore
        }
    }

    /**
     * Get the circuit breaker manager for status checking
     */
    public CircuitBreaker.Manager getCircuitBreakerManager() {
        return circuitBreakerManager;
    }

    private static class RetryInterceptor implements Interceptor {
        private final ConnectionPool connectionPool;

        RetryInterceptor(ConnectionPool connectionPool) {
            this.connectionPool = connectionPool;
        }

        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            IOException lastException = null;

            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try {
                    Response response = chain.proceed(request);
                    if (response.isSuccessful()) {
                        return response;
                    }
                    int code = response.code();

                    // Retry on server errors and rate limiting
                    if (code >= 500 || code == 429) {
                        response.close(); // Don't need body for retried requests
                        if (attempt < MAX_RETRIES - 1) {
                            long delay = RETRY_DELAY_MS * (1 << attempt); // exponential backoff
                            logger.warn("Request failed with {}, retrying in {}ms (attempt {}/{})",
                                    code, delay, attempt + 1, MAX_RETRIES);
                            Thread.sleep(delay);
                            continue;
                        }
                    }
                    // For non-retryable codes (e.g. 4xx), DON'T close the response —
                    // preserve the body so the caller can read the actual error message.
                    return response;
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < MAX_RETRIES - 1) {
                        // On HTTP/2 stream reset, evict stale connections so retry gets a fresh one
                        if (e instanceof StreamResetException) {
                            logger.warn("HTTP/2 stream reset by server (attempt {}/{}): {} — evicting idle connections",
                                    attempt + 1, MAX_RETRIES, e.getMessage());
                            if (connectionPool != null) {
                                connectionPool.evictAll();
                            }
                        } else {
                            logger.warn("Request exception {}, retrying in {}ms (attempt {}/{})",
                                    e.getMessage(), RETRY_DELAY_MS * (1 << attempt), attempt + 1, MAX_RETRIES);
                        }
                        long delay = RETRY_DELAY_MS * (1 << attempt);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Retry interrupted", ie);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Retry interrupted", e);
                }
            }

            if (lastException instanceof StreamResetException) {
                throw new IOException("API connection was interrupted, please try again", lastException);
            }
            throw lastException != null ? lastException : new IOException("Max retries exceeded");
        }
    }
}