package com.github.obhen233.router.local;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for {@link KeywordLearner}.
 */
public class KeywordLearnerTest {

    private Path tempFile;
    private KeywordStore store;
    private KeywordLearner learner;
    private List<CategoryDef> categories;
    private CategoryDef featureCategory;

    @Before
    public void setUp() throws IOException {
        tempFile = Files.createTempFile("router-keywords-", ".json");
        store = new KeywordStore(tempFile);
        categories = CategoryDef.defaults();
        learner = new KeywordLearner(store, true);
        featureCategory = categories.stream()
                .filter(c -> "feature".equals(c.getId()))
                .findFirst().orElseThrow(() -> new RuntimeException("feature category not found"));
    }

    @After
    public void tearDown() throws IOException {
        store.close();
        Files.deleteIfExists(tempFile);
        Files.deleteIfExists(tempFile.resolveSibling(tempFile.getFileName() + ".tmp"));
    }

    @Test
    public void testStopWordFiltering() {
        // "is", "a", "the" are stop words — should not be learned
        List<String> tokens = Arrays.asList("is", "a", "the", "sorting", "algorithm");
        learner.learnPartialMatch("test message", featureCategory, tokens, 1);

        // "sorting" and "algorithm" should be learned, stop words should not
        assertEquals("Should only learn non-stop words", 2, store.size());
        assertNotNull("Should learn 'sorting'", store.get("sorting"));
        assertNotNull("Should learn 'algorithm'", store.get("algorithm"));
    }

    @Test
    public void testChineseStopWordFiltering() {
        // "的", "了" are Chinese stop words — should not be learned
        // "冒泡", "快速" are NOT built-in keywords — should be learned
        List<String> tokens = Arrays.asList("的", "冒泡", "快速");
        learner.learnPartialMatch("test message", featureCategory, tokens, 1);

        assertEquals("Should only learn non-stop words", 2, store.size());
        assertNotNull("Should learn '冒泡'", store.get("冒泡"));
        assertNotNull("Should learn '快速'", store.get("快速"));
    }

    @Test
    public void testDeduplicationAgainstBuiltinKeywords() {
        // "sorting" is similar to "排序" — but "实现", "功能" are built-in keywords
        // "实现" is a built-in keyword for feature category — should NOT be learned
        List<String> tokens = Arrays.asList("sorting", "实现", "全新", "功能");
        learner.learnPartialMatch("test message", featureCategory, tokens, 1);

        // "sorting" and "全新" should be learned
        // "实现" and "功能" are built-in keywords — should be filtered
        assertEquals("Should filter built-in keywords", 2, store.size());
        assertNotNull("Should learn 'sorting'", store.get("sorting"));
        assertNotNull("Should learn '全新'", store.get("全新"));
    }

    @Test
    public void testAlreadyLearnedKeywordsSkipped() {
        store.learn("sorting"); // already learned

        List<String> tokens = Arrays.asList("sorting", "algorithm");
        learner.learnPartialMatch("test message", featureCategory, tokens, 1);

        // "sorting" already in store, "algorithm" is new
        assertEquals("Should only add new keywords", 2, store.size());
        // "sorting" should be frequency 1 (the learnPartialMatch doesn't reinforce)
    }

    @Test
    public void testReinforceSuccess() {
        store.learn("sorting");
        store.learn("algorithm");

        assertEquals("initial freq should be 1",
                1, store.get("sorting").getFrequency());

        Set<String> matched = new HashSet<>(Arrays.asList("sorting", "algorithm"));
        learner.reinforceSuccess(matched);

        assertEquals("frequency should increase to 2",
                2, store.get("sorting").getFrequency());
        assertEquals("frequency should increase to 2",
                2, store.get("algorithm").getFrequency());
    }

    @Test
    public void testReinforceSuccessWithEmptySet() {
        store.learn("sorting");
        int freqBefore = store.get("sorting").getFrequency();

        learner.reinforceSuccess(null);
        learner.reinforceSuccess(Collections.<String>emptySet());

        assertEquals("Should not change frequency", freqBefore, store.get("sorting").getFrequency());
    }

    @Test
    public void testGetLearnedContribution() {
        store.learn("sorting"); // weight = 0.22
        store.learn("algorithm"); // weight = 0.22

        Set<String> tokenSet = new HashSet<>(Arrays.asList("sorting", "algorithm", "other"));
        Set<String> matchedLearned = new HashSet<>();

        double contribution = learner.getLearnedContribution(tokenSet, matchedLearned);

        assertEquals("Contribution should be sum of weights", 0.44, contribution, 0.001);
        assertEquals("Should have 2 matched keywords", 2, matchedLearned.size());
        assertTrue("Should contain 'sorting'", matchedLearned.contains("sorting"));
        assertTrue("Should contain 'algorithm'", matchedLearned.contains("algorithm"));
    }

    @Test
    public void testGetLearnedContributionWithNoMatch() {
        store.learn("sorting");

        Set<String> tokenSet = new HashSet<>(Arrays.asList("unmatched", "tokens"));
        double contribution = learner.getLearnedContribution(tokenSet, null);

        assertEquals("No matching tokens should return 0", 0.0, contribution, 0.001);
    }

    @Test
    public void testDisabledLearnerDoesNothing() {
        KeywordLearner disabledLearner = new KeywordLearner(store, false);

        List<String> tokens = Arrays.asList("sorting", "algorithm");
        disabledLearner.learnPartialMatch("test", featureCategory, tokens, 1);
        assertTrue("Disabled learner should not add keywords", store.allKeywords().isEmpty());

        Set<String> matched = new HashSet<>(Arrays.asList("sorting"));
        disabledLearner.reinforceSuccess(matched);
        assertTrue("Disabled learner should not reinforce", store.allKeywords().isEmpty());

        double contribution = disabledLearner.getLearnedContribution(
                new HashSet<>(Arrays.asList("sorting")), null);
        assertEquals("Disabled learner should return 0 contribution", 0.0, contribution, 0.001);
    }

    @Test
    public void testIsStopWord() {
        assertTrue("'the' is a stop word", KeywordLearner.isStopWord("the"));
        assertTrue("'a' is a stop word", KeywordLearner.isStopWord("a"));
        assertTrue("'的' is a stop word", KeywordLearner.isStopWord("的"));
        assertFalse("'sorting' is not a stop word", KeywordLearner.isStopWord("sorting"));
        assertFalse("'算法' is not a stop word", KeywordLearner.isStopWord("算法"));
    }

    @Test
    public void testIsChinese() {
        assertTrue("'排序' is Chinese", KeywordLearner.isChinese("排序"));
        assertTrue("'算法' is Chinese", KeywordLearner.isChinese("算法"));
        assertFalse("'sorting' is not Chinese", KeywordLearner.isChinese("sorting"));
        assertTrue("Contains Chinese chars", KeywordLearner.isChinese("排序abc"));
        assertFalse("Empty string", KeywordLearner.isChinese(""));
    }

    @Test
    public void testDuplicateInSameMessage() {
        // Same token appears twice in the message — should only be learned once
        List<String> tokens = Arrays.asList("sorting", "sorting", "algorithm");
        learner.learnPartialMatch("test", featureCategory, tokens, 1);

        assertEquals("Should not create duplicates in same message", 2, store.size());
    }

    @Test
    public void testPartialMatchWithNullTokens() {
        // Should not throw
        learner.learnPartialMatch("test", featureCategory, null, 1);
        assertTrue("No tokens should result in no keywords", store.allKeywords().isEmpty());
    }

    @Test
    public void testPartialMatchWithEmptyTokens() {
        // Should not throw
        learner.learnPartialMatch("test", featureCategory, Collections.<String>emptyList(), 1);
        assertTrue("Empty tokens should result in no keywords", store.allKeywords().isEmpty());
    }
}
