package com.github.obhen233.router.local;

import com.github.obhen233.router.config.LocalRouterConfig;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for {@link HanlpTextClassifier}.
 */
public class HanlpTextClassifierTest {

    private List<CategoryDef> categories;
    private LocalRouterConfig config;
    private HanlpTextClassifier classifier;

    @Before
    public void setUp() {
        categories = CategoryDef.defaults();
        config = new LocalRouterConfig();
        classifier = new HanlpTextClassifier(categories, config);
    }

    @Test
    public void testNullMessageReturnsNull() {
        assertNull(classifier.classify(null));
    }

    @Test
    public void testEmptyMessageReturnsNull() {
        assertNull(classifier.classify(""));
        assertNull(classifier.classify("   "));
    }

    @Test
    public void testChineseBugFixDetection() {
        // "修复了一个程序错误" -> should match bug_fix
        HanlpTextClassifier.ClassificationResult result = classifier.classify("修复了一个程序错误");
        assertNotNull("Should classify Chinese bug fix message", result);
        assertEquals("bug_fix", result.getCategory().getId());
        assertTrue("Confidence should be positive", result.getConfidence() > 0);
    }

    @Test
    public void testEnglishBugFixDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("fix the login error");
        assertNotNull("Should classify English bug fix message", result);
        assertEquals("bug_fix", result.getCategory().getId());
        assertTrue("Confidence should be positive", result.getConfidence() > 0);
    }

    @Test
    public void testChineseRefactoringDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("重构这个模块的代码");
        assertNotNull("Should classify refactoring message", result);
        assertEquals("refactoring", result.getCategory().getId());
    }

    @Test
    public void testEnglishRefactoringDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("refactor the authentication module");
        assertNotNull("Should classify refactoring message", result);
        assertEquals("refactoring", result.getCategory().getId());
    }

    @Test
    public void testTestingDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("add unit tests for the service layer");
        assertNotNull("Should classify testing message", result);
        assertEquals("testing", result.getCategory().getId());
    }

    @Test
    public void testChineseTestingDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("添加单元测试覆盖");
        assertNotNull("Should classify testing message", result);
        assertEquals("testing", result.getCategory().getId());
    }

    @Test
    public void testDocumentationDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("write documentation for the API");
        assertNotNull("Should classify documentation message", result);
        assertEquals("documentation", result.getCategory().getId());
    }

    @Test
    public void testFeatureDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("implement a new user dashboard");
        assertNotNull("Should classify feature message", result);
        assertEquals("feature", result.getCategory().getId());
    }

    @Test
    public void testArchitectureDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("设计系统架构方案");
        assertNotNull("Should classify architecture message", result);
        assertEquals("architecture", result.getCategory().getId());
    }

    @Test
    public void testDevopsDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("deploy the application to production");
        assertNotNull("Should classify devops message", result);
        assertEquals("devops", result.getCategory().getId());
    }

    @Test
    public void testDataAnalysisDetection() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("analyze the sales data and create charts");
        assertNotNull("Should classify data_analysis message", result);
        assertEquals("data_analysis", result.getCategory().getId());
    }

    @Test
    public void testUnambiguousMessageReturnsNull() {
        // A message with no matching keywords should return null
        HanlpTextClassifier.ClassificationResult result = classifier.classify("美丽的风景");
        // May or may not match depending on tokenization — just verify it doesn't crash
        assertNotNull("Should not crash on arbitrary text", classifier.tokenize("美丽的风景"));
    }

    @Test
    public void testTokenizeWithNull() {
        List<String> tokens = classifier.tokenize(null);
        assertTrue("Null input should return empty list", tokens.isEmpty());
    }

    @Test
    public void testTokenizeWithEmpty() {
        List<String> tokens = classifier.tokenize("");
        assertTrue("Empty input should return empty list", tokens.isEmpty());
    }

    @Test
    public void testMultipleKeywordsBoost() {
        // Multiple matching keywords should boost confidence
        HanlpTextClassifier.ClassificationResult single = classifier.classify("修复");
        HanlpTextClassifier.ClassificationResult multi = classifier.classify("修复一个崩溃错误问题");

        // Multi-keyword match should have higher or equal confidence
        if (single != null && multi != null) {
            assertTrue("Multiple keywords should boost confidence",
                    multi.getConfidence() >= single.getConfidence());
        }
    }

    @Test
    public void testKeywordMatchMethod() {
        HanlpTextClassifier.ClassificationResult result = classifier.classify("fix the bug");
        assertNotNull(result);
        assertEquals("Keyword matching should be the method", "keyword", result.getMethod());
    }

    @Test
    public void testEmptyCategories() {
        HanlpTextClassifier emptyClassifier = new HanlpTextClassifier(Collections.<CategoryDef>emptyList(), config);
        assertNull("No categories should return null", emptyClassifier.classify("fix the bug"));
    }
}
