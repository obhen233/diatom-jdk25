package com.github.obhen233.core.agent.context;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for ToolResultSummarizer
 */
public class ToolResultSummarizerTest {
    
    @Test
    public void testInformationToolDetection() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String content = "package com.example;\n\npublic class Test {\n    public void doSomething() {}\n}";
        
        // First call should return full content
        String result1 = summarizer.processResult("read_file", "{\"path\":\"Test.java\"}", content);
        assertNotNull(result1);
        assertTrue("First call should return content containing 'class'", result1.contains("class"));
        
        // Second call should return summary for large content
        String largeContent = generateLargeContent(60000);
        String result2 = summarizer.processResult("read_file", "{\"path\":\"Large.java\"}", largeContent);
        assertNotNull(result2);
        
        // Third reference should get cached summary
        String cached = summarizer.getCachedResult("read_file", "{\"path\":\"Large.java\"}");
        assertNotNull("Should return cached summary on second+ reference", cached);
        assertTrue("Cached result should indicate summary", cached.contains("SUMMARY"));
    }
    
    @Test
    public void testStructureExplorationTimestamp() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String dirListing = "<folder name=\"src\"/>\n<file name=\"App.java\"/>\n<file name=\"Main.java\"/>";
        
        String result = summarizer.processResult("list_directory", "{\"path\":\"src\"}", dirListing);
        
        assertNotNull(result);
        assertTrue("Should contain timestamp annotation", result.contains("CACHE_INFO"));
        assertTrue("Should contain timestamp", result.contains("timestamp="));
        assertTrue("Should contain English hint", result.contains("Tip"));
    }
    
    @Test
    public void testStructureExplorationCacheHint() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String dirListing = "<folder name=\"test\"/>\n<file name=\"Test.java\"/>";
        
        // First call
        summarizer.processResult("list_files", "{\"path\":\"test\"}", dirListing);
        
        // Second call should get cached indicator
        String cached = summarizer.getCachedResult("list_files", "{\"path\":\"test\"}");
        assertNotNull("Should return cached result indicator", cached);
        assertTrue("Should indicate cached result", cached.contains("CACHED_RESULT"));
    }
    
    @Test
    public void testReferenceCount() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String content = "test content";
        String args = "{\"path\":\"test.txt\"}";
        
        assertEquals("Initial reference count should be 0", 0, summarizer.getReferenceCount("read_file", args));
        
        // First call
        summarizer.processResult("read_file", args, content);
        assertEquals("After first call, count should be 1", 1, summarizer.getReferenceCount("read_file", args));
        
        // Second call
        summarizer.processResult("read_file", args, content);
        assertEquals("After second call, count should be 2", 2, summarizer.getReferenceCount("read_file", args));
    }
    
    @Test
    public void testInvalidateForPath() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String content = "test content";
        String args = "{\"path\":\"src/App.java\"}";
        
        summarizer.processResult("read_file", args, content);
        assertTrue("Should have cached result", summarizer.hasCachedResult("read_file", args));
        
        summarizer.invalidateForPath("src/App.java");
        assertFalse("Should not have cached result after invalidation", summarizer.hasCachedResult("read_file", args));
    }
    
    @Test
    public void testCacheStats() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String content = "test content";
        summarizer.processResult("read_file", "{\"path\":\"test1.txt\"}", content);
        summarizer.processResult("list_directory", "{\"path\":\"src\"}", content);
        
        ToolResultSummarizer.CacheStats stats = summarizer.getStats();
        assertEquals("Should have 2 cached results", 2, stats.cachedResults);
    }
    
    @Test
    public void testCodeSummaryGeneration() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String javaContent = "package com.example;\n\n" +
            "public class UserService {\n" +
            "    private String name;\n" +
            "    \n" +
            "    public void createUser() {}\n" +
            "    public void deleteUser() {}\n" +
            "    private void validate() {}\n" +
            "}";
        
        // Process multiple times to trigger summary on large content
        String largeJavaContent = repeatString(javaContent, 2000); // Make it large enough
        String args = "{\"path\":\"UserService.java\"}";
        
        summarizer.processResult("read_file", args, largeJavaContent);
        String summary = summarizer.getCachedResult("read_file", args);
        
        assertNotNull("Should have summary on second reference", summary);
        assertTrue("Summary should mention class", summary.contains("UserService"));
    }
    
    @Test
    public void testDirectorySummary() {
        ToolResultSummarizer summarizer = new ToolResultSummarizer();
        
        String dirContent = "<folder name=\"main\"/>\n" +
            "<file name=\"App.java\"/>\n" +
            "<file name=\"Service.java\"/>\n" +
            "<file name=\"Config.xml\"/>\n" +
            "<folder name=\"resources\"/>\n";
        
        String result = summarizer.processResult("list_directory", "{\"path\":\"src\"}", dirContent);
        
        assertTrue("Should have timestamp", result.contains("timestamp"));
    }
    
    // Helper
    private String generateLargeContent(int size) {
        StringBuilder sb = new StringBuilder(size);
        String line = "// This is a test line for generating large content\n";
        while (sb.length() < size) {
            sb.append(line);
        }
        return sb.toString();
    }
    
    private String repeatString(String str, int times) {
        StringBuilder sb = new StringBuilder(str.length() * times);
        for (int i = 0; i < times; i++) {
            sb.append(str);
        }
        return sb.toString();
    }
}
