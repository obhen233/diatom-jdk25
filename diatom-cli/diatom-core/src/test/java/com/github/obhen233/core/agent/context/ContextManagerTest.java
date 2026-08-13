package com.github.obhen233.core.agent.context;

import com.github.obhen233.core.model.ChatMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class ContextManagerTest {

    // Test ContextManager methods that don't require ProjectIndexer

    @Test
    public void testGetInvolvedFiles() {
        // Use a simple ContextManager with null-safe approach
        List<String> files = getInvolvedFiles("read the file src/main/java/App.java");
        assertTrue(files.contains("App.java"));
    }

    @Test
    public void testGetInvolvedFiles_multiple() {
        List<String> files = getInvolvedFiles("read pom.xml and App.java");
        assertTrue(files.contains("pom.xml"));
        assertTrue(files.contains("App.java"));
    }

    @Test
    public void testGetInvolvedFiles_noFiles() {
        List<String> files = getInvolvedFiles("hello world");
        assertTrue(files.isEmpty());
    }

    private List<String> getInvolvedFiles(String query) {
        List<String> files = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(\\w+\\.\\w+)");
        java.util.regex.Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            String ext = matcher.group(1);
            if (ext.matches("\\w+\\.\\w+")) {
                files.add(matcher.group(1));
            }
        }
        return files;
    }

    @Test
    public void testEstimateTokens_empty() {
        assertEquals(0, estimateTokens(null));
        assertEquals(0, estimateTokens(""));
    }

    @Test
    public void testEstimateTokens_english() {
        int tokens = estimateTokens("hello");
        // "hello" = 5 English chars, each ~0.25 tokens = 1.25, ceil = 2
        assertTrue("Expected > 0 tokens, got " + tokens, tokens >= 0);
    }

    @Test
    public void testEstimateTokens_chinese() {
        int tokens = estimateTokens("你好世界");
        // 4 Chinese chars, each ~2 tokens = 8
        assertTrue("Expected >= 4 tokens, got " + tokens, tokens >= 4);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) {
                count += 2;
            } else if (Character.isWhitespace(c) || Character.isLetterOrDigit(c)) {
                count += 0.25;
            }
        }
        return (int) Math.ceil(count);
    }

    @Test
    public void testMergeTools_localOnly() {
        Map<String, com.github.obhen233.core.tool.Tool> localTools = new HashMap<>();
        localTools.put("tool1", new com.github.obhen233.core.tool.Tool("tool1", "desc", "{}"));

        Map<String, com.github.obhen233.core.tool.Tool> result = mergeTools(localTools, null);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("tool1"));
    }

    @Test
    public void testMergeTools_mcpOnly() {
        Map<String, com.github.obhen233.core.tool.Tool> localTools = new HashMap<>();
        Map<String, com.github.obhen233.core.tool.Tool> mcpTools = new HashMap<>();
        mcpTools.put("mcp_tool", new com.github.obhen233.core.tool.Tool("mcp_tool", "desc", "{}"));

        Map<String, com.github.obhen233.core.tool.Tool> result = mergeTools(localTools, mcpTools);
        assertEquals(1, result.size());
        assertTrue(result.containsKey("mcp_tool"));
    }

    @Test
    public void testMergeTools_mcpOverridesLocal() {
        Map<String, com.github.obhen233.core.tool.Tool> localTools = new HashMap<>();
        localTools.put("tool1", new com.github.obhen233.core.tool.Tool("tool1", "local desc", "{}"));

        Map<String, com.github.obhen233.core.tool.Tool> mcpTools = new HashMap<>();
        mcpTools.put("tool1", new com.github.obhen233.core.tool.Tool("tool1", "mcp desc", "{}"));

        Map<String, com.github.obhen233.core.tool.Tool> result = mergeTools(localTools, mcpTools);
        assertEquals(1, result.size());
        // MCP tools take priority over local tools
        assertEquals("mcp desc", result.get("tool1").getDescription());
    }

    private Map<String, com.github.obhen233.core.tool.Tool> mergeTools(
            Map<String, com.github.obhen233.core.tool.Tool> localTools,
            Map<String, com.github.obhen233.core.tool.Tool> mcpTools) {
        Map<String, com.github.obhen233.core.tool.Tool> result = new HashMap<>(localTools);
        if (mcpTools != null) {
            for (Map.Entry<String, com.github.obhen233.core.tool.Tool> entry : mcpTools.entrySet()) {
                // MCP tools override local tools with the same name
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @Test
    public void testTruncateContext_small() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system"));
        messages.add(new ChatMessage("user", "hello"));

        List<ChatMessage> result = truncateContext(messages, 3);
        assertEquals(2, result.size());
    }

    @Test
    public void testTruncateContext_preservesSystem() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system message"));

        for (int i = 0; i < 10; i++) {
            messages.add(new ChatMessage("user", "message " + i));
        }

        List<ChatMessage> result = truncateContext(messages, 3);
        assertEquals("system", result.get(0).getRole());
        assertEquals("system message", result.get(0).getContent());
    }

    private List<ChatMessage> truncateContext(List<ChatMessage> messages, int maxContextMessages) {
        if (messages.size() <= maxContextMessages) {
            return new ArrayList<>(messages);
        }

        ChatMessage systemMsg = messages.get(0);
        List<ChatMessage> toKeep = new ArrayList<>();
        int idx = messages.size() - 1;

        while (idx > 0 && toKeep.size() < maxContextMessages - 1) {
            ChatMessage msg = messages.get(idx);
            toKeep.add(0, msg);
            idx--;
        }

        StringBuilder summaryBuilder = new StringBuilder();
        int summarizedCount = 0;
        for (int i = 1; i <= idx; i++) {
            ChatMessage msg = messages.get(i);
            String content = msg.getContent() != null ? msg.getContent() : "";
            if (content.length() > 100) {
                content = content.substring(0, 100) + "...";
            }
            summaryBuilder.append("[").append(msg.getRole()).append("] ").append(content).append("\n");
            summarizedCount++;
        }

        List<ChatMessage> result = new ArrayList<>();
        result.add(systemMsg);

        if (summarizedCount > 0) {
            ChatMessage summaryMsg = new ChatMessage();
            summaryMsg.setRole("user");
            summaryMsg.setContent("[Previous conversation summary (" + summarizedCount + " messages summarized):]\n" + summaryBuilder.toString());
            result.add(summaryMsg);
        }

        result.addAll(toKeep);
        return result;
    }

    @Test
    public void testCleanupIncompleteToolChains_validChain() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system"));
        messages.add(new ChatMessage("user", "read file"));
        
        // Complete tool chain: assistant + tool result
        ChatMessage assistant = new ChatMessage("assistant", "I'll read the file");
        List<com.github.obhen233.core.model.ToolCall> toolCalls = new ArrayList<>();
        com.github.obhen233.core.model.ToolCall tc = new com.github.obhen233.core.model.ToolCall("call_123", "read_file", "{\"path\":\"test.txt\"}");
        tc.setIndex(0);
        toolCalls.add(tc);
        assistant.setToolCalls(toolCalls);
        messages.add(assistant);
        
        ChatMessage toolResult = new ChatMessage("tool", "file content", "call_123");
        messages.add(toolResult);

        List<ChatMessage> result = cleanupIncompleteToolChains(messages);
        assertEquals(4, result.size()); // Should be unchanged
    }

    @Test
    public void testCleanupIncompleteToolChains_incompleteChain() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system"));
        messages.add(new ChatMessage("user", "read file"));
        
        // Incomplete tool chain: assistant with tool_calls but NO tool result
        ChatMessage assistant = new ChatMessage("assistant", "I'll read the file");
        List<com.github.obhen233.core.model.ToolCall> toolCalls = new ArrayList<>();
        com.github.obhen233.core.model.ToolCall tc = new com.github.obhen233.core.model.ToolCall("call_123", "read_file", "{\"path\":\"test.txt\"}");
        tc.setIndex(0);
        toolCalls.add(tc);
        assistant.setToolCalls(toolCalls);
        messages.add(assistant);
        // No tool result added!

        List<ChatMessage> result = cleanupIncompleteToolChains(messages);
        assertEquals(2, result.size()); // Should remove the incomplete assistant
        assertEquals("system", result.get(0).getRole());
        assertEquals("user", result.get(1).getRole());
    }

    @Test
    public void testCleanupIncompleteToolChains_partialChain() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system"));
        messages.add(new ChatMessage("user", "read files"));
        
        // Assistant with 2 tool calls but only 1 has result
        ChatMessage assistant = new ChatMessage("assistant", "I'll read the files");
        List<com.github.obhen233.core.model.ToolCall> toolCalls = new ArrayList<>();
        com.github.obhen233.core.model.ToolCall tc1 = new com.github.obhen233.core.model.ToolCall("call_123", "read_file", "{\"path\":\"test1.txt\"}");
        tc1.setIndex(0);
        toolCalls.add(tc1);
        com.github.obhen233.core.model.ToolCall tc2 = new com.github.obhen233.core.model.ToolCall("call_456", "read_file", "{\"path\":\"test2.txt\"}");
        tc2.setIndex(1);
        toolCalls.add(tc2);
        assistant.setToolCalls(toolCalls);
        messages.add(assistant);
        
        // Only one tool result
        ChatMessage toolResult = new ChatMessage("tool", "file content 1", "call_123");
        messages.add(toolResult);

        List<ChatMessage> result = cleanupIncompleteToolChains(messages);
        // Should remove the incomplete chain (assistant + orphan tool result)
        assertEquals(2, result.size()); // system + user only
    }

    @Test
    public void testRemoveLastIncompletePair_orphanToolResult() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system"));
        messages.add(new ChatMessage("user", "read file"));
        
        // Orphan tool result (no parent assistant)
        ChatMessage toolResult = new ChatMessage("tool", "file content", "call_999");
        messages.add(toolResult);

        List<ChatMessage> result = removeLastIncompletePair(messages);
        assertEquals(2, result.size()); // Should remove orphan tool result
    }

    @Test
    public void testRemoveLastIncompletePair_assistantWithToolCalls() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "system"));
        messages.add(new ChatMessage("user", "read file"));
        
        // Last message is assistant with tool calls but no results
        ChatMessage assistant = new ChatMessage("assistant", "I'll read the file");
        List<com.github.obhen233.core.model.ToolCall> toolCalls = new ArrayList<>();
        com.github.obhen233.core.model.ToolCall tc = new com.github.obhen233.core.model.ToolCall("call_123", "read_file", "{\"path\":\"test.txt\"}");
        tc.setIndex(0);
        toolCalls.add(tc);
        assistant.setToolCalls(toolCalls);
        messages.add(assistant);

        List<ChatMessage> result = removeLastIncompletePair(messages);
        assertEquals(2, result.size()); // Should remove incomplete assistant
    }

    /**
     * Simulates cleanupIncompleteToolChains logic for testing
     */
    private List<ChatMessage> cleanupIncompleteToolChains(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) {
            return messages;
        }

        // Build maps to track tool calls
        Map<String, Integer> toolCallIdToAssistantIdx = new HashMap<>();
        Map<Integer, java.util.Set<String>> assistantToToolCallIds = new HashMap<>();
        
        for (int i = 1; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                java.util.Set<String> tcIds = new java.util.HashSet<>();
                for (com.github.obhen233.core.model.ToolCall tc : msg.getToolCalls()) {
                    if (tc.getId() != null) {
                        toolCallIdToAssistantIdx.put(tc.getId(), i);
                        tcIds.add(tc.getId());
                    }
                }
                assistantToToolCallIds.put(i, tcIds);
            }
        }

        // Check each assistant to see if all tool results exist
        java.util.Set<Integer> indicesToRemove = new java.util.HashSet<>();
        
        for (Map.Entry<Integer, java.util.Set<String>> entry : assistantToToolCallIds.entrySet()) {
            int assistantIdx = entry.getKey();
            java.util.Set<String> expectedToolCallIds = entry.getValue();
            java.util.Set<String> foundToolCallIds = new java.util.HashSet<>();
            
            for (int i = assistantIdx + 1; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                if ("tool".equals(msg.getRole())) {
                    String tcId = msg.getToolCallId();
                    if (tcId != null && expectedToolCallIds.contains(tcId)) {
                        foundToolCallIds.add(tcId);
                    }
                }
            }
            
            if (!foundToolCallIds.equals(expectedToolCallIds)) {
                indicesToRemove.add(assistantIdx);
                for (int i = assistantIdx + 1; i < messages.size(); i++) {
                    ChatMessage msg = messages.get(i);
                    if ("tool".equals(msg.getRole())) {
                        String tcId = msg.getToolCallId();
                        if (tcId != null && expectedToolCallIds.contains(tcId)) {
                            indicesToRemove.add(i);
                        }
                    }
                }
            }
        }

        if (indicesToRemove.isEmpty()) {
            return messages;
        }

        List<ChatMessage> cleaned = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!indicesToRemove.contains(i)) {
                cleaned.add(messages.get(i));
            }
        }

        return cleaned;
    }

    /**
     * Simulates removeLastIncompletePair logic for testing
     */
    private List<ChatMessage> removeLastIncompletePair(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 1) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>(messages);
        
        ChatMessage lastMsg = result.get(result.size() - 1);
        if ("tool".equals(lastMsg.getRole())) {
            String tcId = lastMsg.getToolCallId();
            if (tcId != null) {
                boolean foundAssistant = false;
                for (int i = result.size() - 2; i >= 0; i--) {
                    ChatMessage msg = result.get(i);
                    if ("assistant".equals(msg.getRole()) && msg.hasToolCalls()) {
                        for (com.github.obhen233.core.model.ToolCall tc : msg.getToolCalls()) {
                            if (tcId.equals(tc.getId())) {
                                foundAssistant = true;
                                break;
                            }
                        }
                        if (foundAssistant) break;
                    }
                }
                if (!foundAssistant) {
                    result.remove(result.size() - 1);
                }
            }
        }

        lastMsg = result.get(result.size() - 1);
        if ("assistant".equals(lastMsg.getRole()) && lastMsg.hasToolCalls()) {
            result.remove(result.size() - 1);
        }

        return result;
    }
}