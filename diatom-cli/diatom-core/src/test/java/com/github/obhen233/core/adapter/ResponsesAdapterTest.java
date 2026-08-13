package com.github.obhen233.core.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.obhen233.config.AppConfig;
import com.github.obhen233.core.CoreInitializer;
import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ChatResponse;
import com.github.obhen233.core.model.ToolCall;
import com.github.obhen233.core.tool.Tool;
import com.github.obhen233.util.ApiUrlUtils;
import com.github.obhen233.util.JsonUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Test suite for {@link ResponsesAdapter} — OpenAI Responses API wire format
 * conversion plus the runtime fallback to chat completions.
 */
public class ResponsesAdapterTest {

    private String originalJarDir;
    private final List<Path> tempDirs = new ArrayList<>();

    @Before
    public void setUp() {
        originalJarDir = System.getProperty("diatom.jar.dir");
    }

    @After
    public void tearDown() throws Exception {
        if (originalJarDir != null) {
            System.setProperty("diatom.jar.dir", originalJarDir);
        } else {
            System.clearProperty("diatom.jar.dir");
        }
        for (Path dir : tempDirs) {
            deleteDirectory(dir);
        }
        tempDirs.clear();
    }

    // ==================== buildRequest ====================

    @Test
    public void testBuildRequest_WireFormatConversion() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "You are a helpful assistant."));
        messages.add(new ChatMessage("user", "What's the weather in SF?"));
        ChatMessage assistant = new ChatMessage("assistant", null);
        List<ToolCall> toolCalls = new ArrayList<>();
        toolCalls.add(new ToolCall("call_1", "get_weather", "{\"city\":\"SF\"}"));
        assistant.setToolCalls(toolCalls);
        messages.add(assistant);
        messages.add(new ChatMessage("tool", "sunny", "call_1"));

        String json = adapter.buildRequest(messages, new ArrayList<>(), false);
        JsonNode root = JsonUtils.getMapper().readTree(json);

        assertEquals("gpt-4o", root.get("model").asText());
        assertEquals("You are a helpful assistant.", root.get("instructions").asText());
        assertTrue("max_output_tokens should be present", root.has("max_output_tokens"));
        assertFalse(root.get("stream").asBoolean());
        assertFalse("no tools passed -> no tools key", root.has("tools"));

        JsonNode input = root.get("input");
        assertNotNull(input);
        assertEquals(3, input.size());

        // user item
        JsonNode userItem = input.get(0);
        assertEquals("user", userItem.get("role").asText());
        assertEquals("What's the weather in SF?", userItem.get("content").asText());

        // assistant item -> function_call content (no reasoning echoed)
        JsonNode assistantItem = input.get(1);
        assertEquals("assistant", assistantItem.get("role").asText());
        JsonNode content = assistantItem.get("content");
        assertEquals(1, content.size());
        JsonNode fcItem = content.get(0);
        assertEquals("function_call", fcItem.get("type").asText());
        assertEquals("call_1", fcItem.get("id").asText());
        assertEquals("call_1", fcItem.get("call_id").asText());
        assertEquals("get_weather", fcItem.get("name").asText());
        assertEquals("{\"city\":\"SF\"}", fcItem.get("arguments").asText());
        assertFalse("reasoning must not be echoed back", assistantItem.has("reasoning"));

        // tool item
        JsonNode toolItem = input.get(2);
        assertEquals("function_call_output", toolItem.get("type").asText());
        assertEquals("call_1", toolItem.get("call_id").asText());
        assertEquals("sunny", toolItem.get("output").asText());
    }

    @Test
    public void testBuildRequest_SystemAndUserOnly_NoInstructionsWhenNoSystem() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "hello"));

        String json = adapter.buildRequest(messages, new ArrayList<>(), true);
        JsonNode root = JsonUtils.getMapper().readTree(json);

        assertFalse("no system message -> no instructions", root.has("instructions"));
        assertTrue(root.get("stream").asBoolean());
        JsonNode input = root.get("input");
        assertEquals(1, input.size());
        assertEquals("hello", input.get(0).get("content").asText());
    }

    @Test
    public void testBuildRequest_ToolsFlatStructureAndEmptySchemaFallback() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "hi"));
        List<Tool> tools = new ArrayList<>();
        tools.add(new Tool("write_file", "Writes a file", "{}"));
        tools.add(new Tool("read_file", "Reads a file", null));

        String json = adapter.buildRequest(messages, tools, false);
        JsonNode root = JsonUtils.getMapper().readTree(json);
        JsonNode toolsArr = root.get("tools");
        assertEquals(2, toolsArr.size());

        // Sorted by name: read_file < write_file
        JsonNode readTool = toolsArr.get(0);
        assertEquals("function", readTool.get("type").asText());
        assertEquals("read_file", readTool.get("name").asText());
        assertEquals("Reads a file", readTool.get("description").asText());
        assertFalse("tools must NOT be nested under 'function'", readTool.has("function"));
        assertEquals("object", readTool.get("parameters").get("type").asText());
        assertTrue(readTool.get("parameters").get("properties").isObject());

        JsonNode writeTool = toolsArr.get(1);
        assertEquals("write_file", writeTool.get("name").asText());
        assertEquals("object", writeTool.get("parameters").get("type").asText());
        assertFalse(writeTool.has("function"));
    }

    // ==================== parseResponse ====================

    @Test
    public void testParseResponse_TextFunctionCallReasoningAndUsage() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        String json = "{"
                + "\"id\":\"resp_123\","
                + "\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30},"
                + "\"output\":["
                + "  {\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Let me think\"}]},"
                + "  {\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\"}]},"
                + "  {\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"fc_1\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"SF\\\"}\"}"
                + "]}";

        ChatResponse response = adapter.parseResponse(json);
        assertNotNull(response);
        ChatMessage msg = response.getMessage();
        assertNotNull(msg);
        assertEquals("assistant", msg.getRole());
        assertEquals("Hello", msg.getContent());
        assertEquals("Let me think", msg.getReasoningContent());
        assertTrue(msg.hasToolCalls());
        assertEquals(1, msg.getToolCalls().size());
        ToolCall tc = msg.getToolCalls().get(0);
        assertEquals("fc_1", tc.getId());
        assertEquals("get_weather", tc.getName());
        assertEquals("{\"city\":\"SF\"}", tc.getArguments());
        assertEquals(Integer.valueOf(0), tc.getIndex());

        assertNotNull(response.getUsage());
        assertEquals(10, response.getUsage().getPromptTokens());
        assertEquals(20, response.getUsage().getCompletionTokens());
        assertEquals(30, response.getUsage().getTotalTokens());

        assertEquals("tool_calls", response.getFinishReason());
    }

    @Test
    public void testParseResponse_IncompleteMaxOutputTokensIsLengthLimited() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        String json = "{\"id\":\"resp_1\",\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"partial\"}]}]}";
        ChatResponse response = adapter.parseResponse(json);
        assertNotNull(response);
        assertEquals("length", response.getFinishReason());
        assertTrue(response.isLengthLimited());
    }

    @Test
    public void testParseResponse_CompletedTextStop() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        String json = "{\"id\":\"resp_1\",\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}";
        ChatResponse response = adapter.parseResponse(json);
        assertNotNull(response);
        assertEquals("done", response.getMessage().getContent());
        assertEquals("stop", response.getFinishReason());
        assertFalse(response.isLengthLimited());
    }

    @Test(expected = OpenAIAdapter.ApiException.class)
    public void testParseResponse_ErrorThrowsApiException() {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        adapter.parseResponse("{\"error\":{\"message\":\"The model 'gpt-4' is not supported for the responses endpoint.\"}}");
    }

    @Test
    public void testParseMessages_ReturnsAssistantMessage() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        String json = "{\"status\":\"completed\","
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}";
        List<ChatMessage> messages = adapter.parseMessages(json);
        assertEquals(1, messages.size());
        assertEquals("assistant", messages.get(0).getRole());
        assertEquals("ok", messages.get(0).getContent());
    }

    @Test
    public void testGetModelType_IsOpenAI() {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4o", 8192);
        assertEquals(ModelAdapter.ModelType.OPENAI, adapter.getModelType());
    }

    @Test
    public void testSetModel_Propagates() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4", 8192);
        adapter.setModel("gpt-4o");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "hi"));
        String json = adapter.buildRequest(messages, new ArrayList<>(), false);
        JsonNode root = JsonUtils.getMapper().readTree(json);
        assertEquals("gpt-4o", root.get("model").asText());
    }

    // ==================== runtime fallback ====================

    @Test
    public void testFallback_ActivatesOnModelNotSupported() throws Exception {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4", 8192);
        assertFalse(adapter.isFallbackActive());

        IOException e = new IOException("Unexpected response code: 400, body: "
                + "{\"error\":{\"code\":\"model_not_supported\",\"message\":\"The model 'gpt-4' is not supported for the responses endpoint.\"}}");
        assertTrue(adapter.tryActivateFallback(e));
        assertTrue(adapter.isFallbackActive());

        assertEquals("https://api.openai.com/v1/chat/completions",
                adapter.effectiveEndpoint("https://api.openai.com/v1/responses"));

        // After fallback, buildRequest emits OpenAI chat completions format
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("user", "hi"));
        String json = adapter.buildRequest(messages, new ArrayList<>(), false);
        JsonNode root = JsonUtils.getMapper().readTree(json);
        assertEquals("gpt-4", root.get("model").asText());
        assertTrue("chat format has messages", root.has("messages"));
        assertTrue("chat format has max_tokens", root.has("max_tokens"));
        assertFalse(root.has("instructions"));
        assertFalse(root.has("input"));
    }

    @Test
    public void testFallback_ActivatesOn404() {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4", 8192);
        IOException e = new IOException("Unexpected response code: 404, body: {\"error\":{\"message\":\"Not Found\"}}");
        assertTrue(adapter.tryActivateFallback(e));
        assertTrue(adapter.isFallbackActive());
    }

    @Test
    public void testFallback_DoesNotActivateOnUnrelatedError() {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4", 8192);
        assertFalse(adapter.tryActivateFallback(new IOException("Unexpected response code: 500, body: server error")));
        assertFalse(adapter.isFallbackActive());
        assertFalse(adapter.tryActivateFallback(null));
        assertFalse(adapter.isFallbackActive());
    }

    @Test
    public void testFallback_EffectiveEndpointUnchangedWhenNotActive() {
        ResponsesAdapter adapter = new ResponsesAdapter("gpt-4", 8192);
        assertEquals("https://api.openai.com/v1/responses",
                adapter.effectiveEndpoint("https://api.openai.com/v1/responses"));
    }

    // ==================== ApiUrlUtils + CoreInitializer ====================

    @Test
    public void testApiUrlUtils_ResponsesUrls() {
        assertEquals("https://api.openai.com/v1/responses",
                ApiUrlUtils.openaiResponsesUrl("https://api.openai.com"));
        assertEquals("https://api.openai.com/v1/responses",
                ApiUrlUtils.openaiResponsesUrl("https://api.openai.com/v1"));
        assertEquals("https://api.openai.com/v1/responses",
                ApiUrlUtils.openaiResponsesUrl("https://api.openai.com/v1/"));

        assertEquals("https://api.openai.com/v1/chat/completions",
                ApiUrlUtils.responsesToChatUrl("https://api.openai.com/v1/responses"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                ApiUrlUtils.responsesToChatUrl("https://api.openai.com/v1/responses/"));
        assertEquals("https://api.openai.com/v1/responses/custom",
                ApiUrlUtils.responsesToChatUrl("https://api.openai.com/v1/responses/custom"));
        assertEquals("https://api.openai.com/v1/chat/completions",
                ApiUrlUtils.responsesToChatUrl("https://api.openai.com/v1/chat/completions"));
    }

    @Test
    public void testDetectResponsesFormat() {
        assertTrue(CoreInitializer.detectResponsesFormat(configWith("api.format", "responses")));
        assertTrue(CoreInitializer.detectResponsesFormat(
                configWith("api.format", "auto", "api.endpoint", "/v1/responses")));
        assertFalse(CoreInitializer.detectResponsesFormat(configWith("api.format", "openai")));
        assertFalse(CoreInitializer.detectResponsesFormat(configWith("api.format", "anthropic")));
        assertFalse(CoreInitializer.detectResponsesFormat(configWith("api.format", "auto")));
        assertFalse(CoreInitializer.detectResponsesFormat(
                configWith("api.format", "auto", "api.endpoint", "/v1/chat/completions")));

        // Explicit responses format wins over the claude model-name heuristic
        assertTrue(CoreInitializer.detectResponsesFormat(
                configWith("api.format", "responses", "api.model", "claude-sonnet-4-6")));
        assertFalse(CoreInitializer.detectAnthropicFormat(
                configWith("api.format", "responses", "api.model", "claude-sonnet-4-6")));
    }

    @Test
    public void testResolveResponsesEndpoint() {
        assertEquals("https://api.openai.com/v1/responses",
                CoreInitializer.resolveResponsesEndpoint(configWith("api.format", "responses")));
        assertEquals("https://api.openai.com/v1/responses",
                CoreInitializer.resolveResponsesEndpoint(
                        configWith("api.format", "responses", "api.url", "https://api.openai.com/v1")));
        assertEquals("https://api.openai.com/custom",
                CoreInitializer.resolveResponsesEndpoint(
                        configWith("api.format", "responses", "api.endpoint", "/custom")));
    }

    // ==================== helpers ====================

    private AppConfig configWith(String... keyValues) {
        try {
            Path tempDir = Files.createTempDirectory("diatom-resp-test");
            tempDirs.add(tempDir);
            System.setProperty("diatom.jar.dir", tempDir.toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        AppConfig config = new AppConfig();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            config.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return config;
    }

    private void deleteDirectory(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try {
            try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                walk.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
            }
        } catch (IOException e) {
            // ignore
        }
    }
}
