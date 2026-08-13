package com.github.obhen233.core.agent.loop;

import com.github.obhen233.core.model.ChatMessage;
import com.github.obhen233.core.model.ToolCall;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class LoopDetectorTest {

    private LoopDetector detector;

    @Before
    public void setUp() {
        detector = new LoopDetector();
    }

    @Test
    public void testNoLoopInitially() {
        ChatMessage msg = new ChatMessage("assistant", "Hello");
        assertFalse(detector.detectLoop(msg));
    }

    @Test
    public void testNoLoopWithDifferentToolCalls() {
        ChatMessage msg1 = createToolCallMessage("tool1", "{}");
        ChatMessage msg2 = createToolCallMessage("tool2", "{}");

        assertFalse(detector.detectLoop(msg1));
        assertFalse(detector.detectLoop(msg2));
    }

    @Test
    public void testNoLoopWithDifferentArgs() {
        ChatMessage msg1 = createToolCallMessage("tool1", "{\"arg\": \"value1\"}");
        ChatMessage msg2 = createToolCallMessage("tool1", "{\"arg\": \"value2\"}");

        assertFalse(detector.detectLoop(msg1));
        assertFalse(detector.detectLoop(msg2));
    }

    @Test
    public void testLoopDetectedAfterThreshold() {
        ChatMessage msg = createToolCallMessage("sameTool", "{\"arg\": \"same\"}");

        // First call: sameToolCallCount = 0, no match (lastToolCall is null)
        // Second call: sameToolCallCount = 1 (match found)
        // Third call: sameToolCallCount = 2
        // Fourth call: sameToolCallCount = 3
        // Fifth call: sameToolCallCount = 4
        // Sixth call: sameToolCallCount = 5, loop detected!

        for (int i = 0; i < 5; i++) {
            assertFalse("Should not detect loop at attempt " + (i + 1), detector.detectLoop(msg));
        }

        // 6th call should trigger loop (sameToolCallCount reaches 5)
        assertTrue("Should detect loop at 6th attempt", detector.detectLoop(msg));
    }

    @Test
    public void testResetAfterFinalResponse() {
        ChatMessage toolMsg = createToolCallMessage("tool1", "{}");

        // Trigger some loops
        detector.detectLoop(toolMsg);
        detector.detectLoop(toolMsg);

        // Final response (no tool calls) should reset
        ChatMessage finalMsg = new ChatMessage("assistant", "Done");
        assertFalse(detector.detectLoop(finalMsg));

        // Counter should be reset
        assertNull(detector.getLastToolCall());
        assertEquals(0, detector.getSameToolCallCount());
    }

    @Test
    public void testResetMethod() {
        ChatMessage msg = createToolCallMessage("tool1", "{}");
        detector.detectLoop(msg);
        detector.detectLoop(msg);

        detector.reset();

        assertNull(detector.getLastToolCall());
        assertEquals(0, detector.getSameToolCallCount());
    }

    @Test
    public void testBuildLoopExceededMessage() {
        String result = detector.buildLoopExceededMessage(100, 50, 150, null);
        assertTrue(result.contains("loop_timeout"));
    }

    @Test
    public void testNullMessage() {
        detector.reset();
        assertFalse(detector.detectLoop(null));
    }

    private ChatMessage createToolCallMessage(String toolName, String args) {
        ToolCall tc = new ToolCall("id-" + toolName, toolName, args);
        ChatMessage msg = new ChatMessage("assistant", "");
        java.util.List<ToolCall> toolCalls = new java.util.ArrayList<>();
        toolCalls.add(tc);
        msg.setToolCalls(toolCalls);
        return msg;
    }
}