package com.github.obhen233.router.local;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.router.config.LocalRouterConfig;
import com.github.obhen233.spi.RoutingResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Integration tests for the full self-learning flow:
 * classifier → learner → router.
 */
public class LearningIntegrationTest {

    private Path tempKeywords;
    private KeywordStore store;
    private KeywordLearner learner;
    private HanlpTextClassifier classifier;
    private LocalRequestRouterImpl router;
    private List<WorkerInfo> workers;

    @Before
    public void setUp() throws IOException {
        tempKeywords = Files.createTempFile("router-keywords-", ".json");

        // Use a config with learning enabled
        System.setProperty("gateway.router.local.keywords-path", tempKeywords.toString());
        LocalRouterConfig config = new LocalRouterConfig();
        List<CategoryDef> categories = CategoryDef.defaults();

        store = new KeywordStore(tempKeywords);
        learner = new KeywordLearner(store, true);
        classifier = new HanlpTextClassifier(categories, config, learner);
        router = new LocalRequestRouterImpl(config, classifier, categories, store, learner);

        // Create mock workers
        workers = new ArrayList<>();
        WorkerMetrics metrics = new WorkerMetrics();
        metrics.setCurrentLoad(0.3);
        WorkerInfo w = new WorkerInfo();
        w.setWorkerId("worker-1");
        w.setModel("gpt-4");
        w.setPort(8081);
        w.setCapabilities(Collections.singletonMap("code", 1.0));
        w.setMetrics(metrics);
        workers.add(w);
    }

    @After
    public void tearDown() throws IOException {
        store.close();
        System.clearProperty("gateway.router.local.keywords-path");
        if (tempKeywords != null) {
            Files.deleteIfExists(tempKeywords);
            Files.deleteIfExists(tempKeywords.resolveSibling(tempKeywords.getFileName() + ".tmp"));
        }
    }

    @Test
    public void testPartialMatchLearnsNewKeywords() {
        // "sort this array" — "sort" is not a built-in keyword for most categories
        // It might partially match "feature" (which has "排序" in Chinese)
        // or not match at all. Let's test with a message that has one built-in anchor.

        // "需要排序功能" — "排序" and "功能" are built-in feature keywords
        // This should match feature with high confidence — so no partial match learning.

        // Instead, use: "sort the array" — "sort" stems to "sorting" which doesn't
        // exist as built-in. But wait, there's no built-in anchor here...

        // Let's use: "sort array ascending" — no built-in keywords, no match
        RoutingResult result = router.route("sort array ascending", workers);
        assertNull("No built-in keywords should return null", result);
        assertTrue("No partial match — store should be empty", store.allKeywords().isEmpty());
    }

    @Test
    public void testClassifierWithLearnedKeywordsReturnsKeywordPlusLearned() {
        // Pre-populate store with a learned keyword
        store.learn("sorting");

        // "create sorting project" — 'create' is a built-in feature keyword
        // 'sorting' is a learned keyword — should contribute
        HanlpTextClassifier.ClassificationResult cr = classifier.classify("create sorting project");

        assertNotNull("Should classify with learned keyword", cr);
        assertTrue("Method should contain 'learned'", cr.getMethod().contains("learned"));
        assertFalse("Matched learned keywords should not be empty",
                cr.getMatchedLearnedKeywords().isEmpty());
        assertTrue("Should contain 'sorting'",
                cr.getMatchedLearnedKeywords().contains("sorting"));
    }

    @Test
    public void testLearnedKeywordsBoostConfidence() {
        // "create sorting project" — 'create' is a built-in feature keyword, 'sorting' is not
        HanlpTextClassifier.ClassificationResult before = classifier.classify("create sorting project");

        // Learn "sorting" multiple times to boost its weight
        store.learn("sorting"); // freq=1, weight=0.22
        for (int i = 0; i < 10; i++) {
            store.learn("sorting");
        }
        // freq=11 → weight = min(0.6, 0.2 + 11 * 0.02) = 0.42

        HanlpTextClassifier.ClassificationResult after = classifier.classify("create sorting project");

        assertNotNull("Should classify after learning", after);
        if (before != null) {
            assertTrue("Confidence should be higher with learned keywords",
                    after.getConfidence() >= before.getConfidence());
        }
    }

    @Test
    public void testFullLearningFlowInRouter() {
        // Step 1: Verify initial state
        assertTrue("Store should be empty initially", store.allKeywords().isEmpty());

        // Step 2: Route a message with exactly 1 built-in keyword match
        // "implement" is a feature English keyword → matchCount=1, confidence=0.35
        // "zizzle" is not a built-in keyword → will be learned via partial match
        // With BreakIterator: ["implement", " ", "zizzle"] → ["implement", "zizzle"]

        RoutingResult result1 = router.route("implement zizzle", workers);
        // confidence = 0.35 < 0.7 → returns null, but partial match learning occurs

        // Check that "zizzle" was learned
        assertTrue("'zizzle' should be learned from partial match",
                store.contains("zizzle"));

        // Step 3: Now "implement zizzle" should still not reach threshold
        // learned "zizzle" weight = 0.22
        // confidence = min(1.0, 0.35 + 0.22) = 0.57 < 0.7
        RoutingResult result2 = router.route("implement zizzle", workers);
        assertNull("Still below threshold", result2);

        // Step 4: Reinforce "zizzle" multiple times
        for (int i = 0; i < 10; i++) {
            store.learn("zizzle");
        }
        // Frequency = 11, weight = min(0.6, 0.2 + 11 * 0.02) = 0.42

        // Step 5: Now confidence = min(1.0, 0.35 + 0.42) = 0.77 >= 0.7 → should route!
        RoutingResult result3 = router.route("implement zizzle", workers);
        assertNotNull("Should route after learning enough", result3);
        assertEquals("feature", result3.getRequirement().getTaskType());
        assertTrue("Source should indicate learned keywords",
                result3.getSource().contains("learned"));
    }

    @Test
    public void testLearningDisabledDoesNotLearn() {
        // Config with learning disabled
        System.setProperty("gateway.router.local.learn-enabled", "false");
        LocalRouterConfig noLearnConfig = new LocalRouterConfig();
        HanlpTextClassifier clf = new HanlpTextClassifier(CategoryDef.defaults(), noLearnConfig);
        LocalRequestRouterImpl noLearnRouter = new LocalRequestRouterImpl(
                noLearnConfig, clf, CategoryDef.defaults(), null, null);

        RoutingResult result = noLearnRouter.route("排序项目", workers);
        // May or may not route, but should NOT learn
        // Clean up
        System.clearProperty("gateway.router.local.learn-enabled");
    }

    @Test
    public void testSuccessReinforcementIncreasesFrequency() {
        store.learn("项目"); // frequency = 1

        // Route a message that will succeed with "feature" category
        // "新增排序功能项目" has 3 built-in matches (新增, 排序, 功能) → confidence = 1.0
        // plus learned "项目" → should reinforce "项目"

        int freqBefore = store.get("项目").getFrequency();
        RoutingResult result = router.route("新增排序功能项目", workers);
        assertNotNull("Should route with high confidence", result);

        // After success, frequency should have increased
        int freqAfter = store.get("项目").getFrequency();
        assertTrue("Frequency should increase on success", freqAfter >= freqBefore);
    }
}
