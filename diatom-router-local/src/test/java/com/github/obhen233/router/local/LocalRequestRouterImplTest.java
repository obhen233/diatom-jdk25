package com.github.obhen233.router.local;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.router.config.LocalRouterConfig;
import com.github.obhen233.spi.RoutingResult;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for {@link LocalRequestRouterImpl}.
 */
public class LocalRequestRouterImplTest {

    private LocalRequestRouterImpl router;
    private List<WorkerInfo> workers;

    @Before
    public void setUp() {
        LocalRouterConfig config = new LocalRouterConfig();
        List<CategoryDef> categories = CategoryDef.defaults();
        HanlpTextClassifier classifier = new HanlpTextClassifier(categories, config);
        router = new LocalRequestRouterImpl(config, classifier, categories);

        // Create mock workers
        workers = new ArrayList<>();

        WorkerMetrics metrics1 = new WorkerMetrics();
        metrics1.setCurrentLoad(0.3);
        WorkerInfo w1 = new WorkerInfo();
        w1.setWorkerId("worker-1");
        w1.setModel("gpt-4");
        w1.setPort(8081);
        w1.setCapabilities(Collections.singletonMap("code", 1.0));
        w1.setMetrics(metrics1);
        workers.add(w1);

        WorkerMetrics metrics2 = new WorkerMetrics();
        metrics2.setCurrentLoad(0.1);
        WorkerInfo w2 = new WorkerInfo();
        w2.setWorkerId("worker-2");
        w2.setModel("gpt-4");
        w2.setPort(8082);
        Map<String, Double> caps2 = new HashMap<>();
        caps2.put("code", 1.0);
        caps2.put("debugging", 1.0);
        w2.setCapabilities(caps2);
        w2.setMetrics(metrics2);
        workers.add(w2);
    }

    @Test
    public void testRouteReturnsNullWhenDisabled() {
        System.setProperty("gateway.router.local.enabled", "false");
        try {
            LocalRouterConfig disabledConfig = new LocalRouterConfig();
            HanlpTextClassifier cls = new HanlpTextClassifier(CategoryDef.defaults(), disabledConfig);
            LocalRequestRouterImpl disabledRouter = new LocalRequestRouterImpl(disabledConfig, cls, CategoryDef.defaults());
            RoutingResult result = disabledRouter.route("fix the bug", workers);
            assertNull("Disabled router should return null", result);
        } finally {
            System.clearProperty("gateway.router.local.enabled");
        }
    }

    @Test
    public void testRouteReturnsNullForNullMessage() {
        RoutingResult result = router.route(null, workers);
        assertNull("Null message should return null", result);
    }

    @Test
    public void testRouteReturnsNullForEmptyMessage() {
        RoutingResult result = router.route("", workers);
        assertNull("Empty message should return null", result);
    }

    @Test
    public void testRouteReturnsNullForNoMatch() {
        // Message with no recognizable keywords
        RoutingResult result = router.route("xyzzy unknown gibberish qwerty", workers);
        assertNull("Unrecognized message should return null", result);
    }

    @Test
    public void testRouteBugFixInEnglish() {
        RoutingResult result = router.route("fix the login error", workers);
        assertNotNull("Should route bug fix", result);
        assertEquals("bug_fix", result.getRequirement().getTaskType());
        assertTrue("Confidence should be above threshold", result.getConfidence() >= 0.7);
        assertTrue("Source should contain 'keyword'", result.getSource().contains("keyword"));
    }

    @Test
    public void testRouteBugFixInChinese() {
        RoutingResult result = router.route("修复登录页面崩溃问题", workers);
        assertNotNull("Should route Chinese bug fix", result);
        assertEquals("bug_fix", result.getRequirement().getTaskType());
        assertTrue("Confidence should be above threshold", result.getConfidence() >= 0.7);
    }

    @Test
    public void testRouteRefactoring() {
        RoutingResult result = router.route("refactor and optimize the authentication service", workers);
        assertNotNull("Should route refactoring", result);
        assertEquals("refactoring", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteTesting() {
        RoutingResult result = router.route("write unit tests for the payment module", workers);
        assertNotNull("Should route testing", result);
        assertEquals("testing", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteDocumentation() {
        RoutingResult result = router.route("write documentation and readme for the API", workers);
        assertNotNull("Should route documentation", result);
        assertEquals("documentation", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteFeature() {
        RoutingResult result = router.route("implement new feature for user profile", workers);
        assertNotNull("Should route feature", result);
        assertEquals("feature", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteDevops() {
        RoutingResult result = router.route("deploy and release the new version", workers);
        assertNotNull("Should route devops", result);
        assertEquals("devops", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteArchitecture() {
        RoutingResult result = router.route("design architecture for the database", workers);
        assertNotNull("Should route architecture", result);
        assertEquals("architecture", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteDataAnalysis() {
        RoutingResult result = router.route("analyze the data and create charts", workers);
        assertNotNull("Should route data analysis", result);
        assertEquals("data_analysis", result.getRequirement().getTaskType());
    }

    @Test
    public void testRouteCodeReview() {
        RoutingResult result = router.route("review and inspect the pull request", workers);
        assertNotNull("Should route code review", result);
        assertEquals("code_review", result.getRequirement().getTaskType());
    }

    @Test
    public void testTaskRequirementFields() {
        RoutingResult result = router.route("fix the database connection error", workers);
        assertNotNull(result);
        TaskRequirement req = result.getRequirement();

        assertEquals("bug_fix", req.getTaskType());
        assertTrue("Should have code capability", req.getRequiredCapabilities().contains("code"));
        assertTrue("Should have debugging capability", req.getRequiredCapabilities().contains("debugging"));
        assertEquals(3, req.getComplexity());
        assertEquals(2, req.getSensitivity());
        assertEquals(2000, req.getExpectedTokens());
        assertEquals("balanced", req.getBudgetPriority());
        assertTrue("Fallback should be allowed", req.isFallbackAllowed());
        assertFalse("Pipeline should not be recommended", req.isPipelineRecommended());
        assertNotNull("Reasoning should be set", req.getReasoning());
    }

    @Test
    public void testSuggestedWorkerWithMatchingCapabilities() {
        RoutingResult result = router.route("fix the database connection error", workers);
        assertNotNull(result);
        TaskRequirement req = result.getRequirement();

        // worker-2 has both "code" and "debugging" capabilities, should be preferred
        assertEquals("worker-2", req.getSuggestedWorkerId());
    }

    @Test
    public void testNoAvailableWorkers() {
        RoutingResult result = router.route("fix the bug", Collections.<WorkerInfo>emptyList());
        assertNotNull(result);
        assertNull("No suggested worker when no workers available",
                result.getRequirement().getSuggestedWorkerId());
    }

    @Test
    public void testRoutingResultFields() {
        RoutingResult result = router.route("fix the login error", workers);
        assertNotNull(result);
        assertNotNull("Requirement should not be null", result.getRequirement());
        assertTrue("Confidence should be between 0 and 1",
                result.getConfidence() >= 0 && result.getConfidence() <= 1.0);
        assertNotNull("Source should not be null", result.getSource());
    }

    @Test
    public void testFindBestWorkerWithNoCapabilities() {
        WorkerMetrics metrics = new WorkerMetrics();
        metrics.setCurrentLoad(0.5);
        WorkerInfo worker = new WorkerInfo();
        worker.setWorkerId("worker-1");
        worker.setCapabilities(Collections.<String, Double>emptyMap());
        worker.setMetrics(metrics);

        CategoryDef category = new CategoryDef("test", null, null,
                Arrays.asList("code", "debugging"), "test");
        String result = LocalRequestRouterImpl.findBestWorker(category, Collections.singletonList(worker));
        assertNull("No overlap should return null", result);
    }

    @Test
    public void testFindBestWorkerPrefersLowerLoad() {
        WorkerMetrics metricsHigh = new WorkerMetrics();
        metricsHigh.setCurrentLoad(0.9);
        WorkerInfo wHigh = new WorkerInfo();
        wHigh.setWorkerId("worker-high");
        wHigh.setCapabilities(Collections.singletonMap("code", 1.0));
        wHigh.setMetrics(metricsHigh);

        WorkerMetrics metricsLow = new WorkerMetrics();
        metricsLow.setCurrentLoad(0.1);
        WorkerInfo wLow = new WorkerInfo();
        wLow.setWorkerId("worker-low");
        wLow.setCapabilities(Collections.singletonMap("code", 1.0));
        wLow.setMetrics(metricsLow);

        CategoryDef category = new CategoryDef("test", null, null,
                Collections.singletonList("code"), "test");
        String result = LocalRequestRouterImpl.findBestWorker(category, Arrays.asList(wHigh, wLow));
        assertEquals("Should prefer lower load worker", "worker-low", result);
    }

    @Test
    public void testImportTrainingDataWithExistingCategory() throws IOException {
        // Use a router with learning enabled
        LocalRouterConfig config = new LocalRouterConfig();
        System.setProperty("gateway.router.local.learn-enabled", "true");
        System.setProperty("gateway.router.local.keywords-path",
                Files.createTempFile("test-kw-", ".json").toString());
        System.setProperty("gateway.router.local.training-data-path",
                Files.createTempFile("test-td-", ".json").toString());
        try {
            LocalRouterConfig learnConfig = new LocalRouterConfig();
            List<CategoryDef> cats = CategoryDef.defaults();
            KeywordStore ks = new KeywordStore(Paths.get(learnConfig.getKeywordsPath()));
            KeywordLearner kl = new KeywordLearner(ks, true);
            TrainingDataStore tds = new TrainingDataStore(Paths.get(learnConfig.getTrainingDataPath()));
            HanlpTextClassifier cls = new HanlpTextClassifier(cats, learnConfig, kl);
            LocalRequestRouterImpl r = new LocalRequestRouterImpl(learnConfig, cls, cats, ks, kl, tds);

            // Create a temp training file with messages for known category "feature"
            Path trainingFile = Files.createTempFile("training-import-", ".json");
            Files.write(trainingFile, Arrays.asList(
                    "[",
                    "  {\"message\": \"implement user authentication\", \"category\": \"feature\"},",
                    "  {\"message\": \"add sorting functionality\", \"category\": \"feature\"}",
                    "]"
            ));

            int count = r.importTrainingData(trainingFile);
            assertEquals("Should import 2 messages", 2, count);

            // Router should still route for existing category after import
            RoutingResult result = r.route("implement new feature module", workers);
            assertNotNull("Router should route after training import", result);
            assertEquals("feature", result.getRequirement().getTaskType());

            Files.deleteIfExists(trainingFile);
        } finally {
            System.clearProperty("gateway.router.local.learn-enabled");
            System.clearProperty("gateway.router.local.keywords-path");
            System.clearProperty("gateway.router.local.training-data-path");
        }
    }

    @Test
    public void testImportTrainingDataCreatesNewCategory() throws IOException {
        LocalRouterConfig config = new LocalRouterConfig();
        System.setProperty("gateway.router.local.learn-enabled", "true");
        System.setProperty("gateway.router.local.keywords-path",
                Files.createTempFile("test-kw-", ".json").toString());
        System.setProperty("gateway.router.local.training-data-path",
                Files.createTempFile("test-td-", ".json").toString());
        try {
            LocalRouterConfig learnConfig = new LocalRouterConfig();
            List<CategoryDef> cats = new ArrayList<>(CategoryDef.defaults());
            KeywordStore ks = new KeywordStore(Paths.get(learnConfig.getKeywordsPath()));
            KeywordLearner kl = new KeywordLearner(ks, true);
            TrainingDataStore tds = new TrainingDataStore(Paths.get(learnConfig.getTrainingDataPath()));
            HanlpTextClassifier cls = new HanlpTextClassifier(cats, learnConfig, kl);
            LocalRequestRouterImpl r = new LocalRequestRouterImpl(learnConfig, cls, cats, ks, kl, tds);

            Path trainingFile = Files.createTempFile("training-new-cat-", ".json");
            Files.write(trainingFile, Arrays.asList(
                    "[",
                    "  {\"message\": \"prepare financial quarterly report\", \"category\": \"finance\"},",
                    "  {\"message\": \"analyze profit and loss statement\", \"category\": \"finance\"}",
                    "]"
            ));

            int count = r.importTrainingData(trainingFile);
            assertEquals("Should import 2 messages", 2, count);

            // Verify the new category was created
            TaskRequirement req = r.route("prepare financial report", workers).getRequirement();
            assertNotNull("Should route to finance after import", req);
            assertEquals("finance", req.getTaskType());

            Files.deleteIfExists(trainingFile);
        } finally {
            System.clearProperty("gateway.router.local.learn-enabled");
            System.clearProperty("gateway.router.local.keywords-path");
            System.clearProperty("gateway.router.local.training-data-path");
        }
    }

    @Test
    public void testImportTrainingDataFileNotFound() {
        LocalRouterConfig config = new LocalRouterConfig();
        System.setProperty("gateway.router.local.learn-enabled", "true");
        try {
            LocalRouterConfig learnConfig = new LocalRouterConfig();
            HanlpTextClassifier cls = new HanlpTextClassifier(CategoryDef.defaults(), learnConfig);
            LocalRequestRouterImpl r = new LocalRequestRouterImpl(learnConfig, cls, CategoryDef.defaults());

            int result = r.importTrainingData(Paths.get("nonexistent-file.json"));
            assertEquals("Non-existent file should return -1", -1, result);
        } finally {
            System.clearProperty("gateway.router.local.learn-enabled");
        }
    }

    @Test
    public void testImportTrainingDataWithLearningDisabled() throws IOException {
        LocalRouterConfig config = new LocalRouterConfig();
        System.setProperty("gateway.router.local.learn-enabled", "false");
        try {
            LocalRouterConfig disabledConfig = new LocalRouterConfig();
            HanlpTextClassifier cls = new HanlpTextClassifier(CategoryDef.defaults(), disabledConfig);
            LocalRequestRouterImpl r = new LocalRequestRouterImpl(disabledConfig, cls, CategoryDef.defaults());

            Path trainingFile = Files.createTempFile("training-disabled-", ".json");
            Files.write(trainingFile, Arrays.asList(
                    "[{\"message\": \"test\", \"category\": \"feature\"}]"
            ));

            int result = r.importTrainingData(trainingFile);
            assertEquals("Disabled learning should return -1", -1, result);

            Files.deleteIfExists(trainingFile);
        } finally {
            System.clearProperty("gateway.router.local.learn-enabled");
        }
    }

    @Test
    public void testImportTrainingDataWithInvalidJson() throws IOException {
        LocalRouterConfig config = new LocalRouterConfig();
        System.setProperty("gateway.router.local.learn-enabled", "true");
        try {
            LocalRouterConfig learnConfig = new LocalRouterConfig();
            HanlpTextClassifier cls = new HanlpTextClassifier(CategoryDef.defaults(), learnConfig);
            LocalRequestRouterImpl r = new LocalRequestRouterImpl(learnConfig, cls, CategoryDef.defaults());

            Path trainingFile = Files.createTempFile("training-invalid-", ".json");
            Files.write(trainingFile, Arrays.asList("not valid json at all"));

            int result = r.importTrainingData(trainingFile);
            assertEquals("Invalid JSON should return -1", -1, result);

            Files.deleteIfExists(trainingFile);
        } finally {
            System.clearProperty("gateway.router.local.learn-enabled");
        }
    }
}
