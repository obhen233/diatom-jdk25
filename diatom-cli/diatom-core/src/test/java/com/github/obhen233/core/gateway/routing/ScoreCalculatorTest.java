package com.github.obhen233.core.gateway.routing;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * ScoreCalculator 测试
 * 测试 normal scoring + 3 种 penalty checks
 */
public class ScoreCalculatorTest {

    private final ScoreCalculator calculator = new ScoreCalculator();

    private WorkerInfo createWorker(String id, String group, int maxTokens, boolean supportsToolCalls,
                                    List<String> boundaries, Map<String, Double> capabilities) {
        WorkerInfo w = new WorkerInfo(id, "localhost", 8080);
        w.setModel("gpt-4");
        w.setGroup(group);
        w.setMaxTokens(maxTokens);
        w.setSupportsToolCalls(supportsToolCalls);
        if (boundaries != null) w.setBoundaries(boundaries);
        if (capabilities != null) w.setCapabilities(capabilities);
        WorkerMetrics metrics = new WorkerMetrics();
        metrics.setCurrentLoad(0.3);
        metrics.setSuccessRate(0.95);
        metrics.setAvgLatencyMs(500);
        w.setMetrics(metrics);
        w.setCostPer1kTokens(0.01);
        return w;
    }

    private TaskRequirement createRequirement(List<String> requiredCapabilities, int expectedTokens) {
        TaskRequirement req = new TaskRequirement();
        req.setRequiredCapabilities(requiredCapabilities != null ? requiredCapabilities : new ArrayList<String>());
        req.setExpectedTokens(expectedTokens);
        req.setTaskType("general");
        req.setComplexity(5);
        return req;
    }

    // ========== Normal scoring ==========

    @Test
    public void testNormalScoring_noPenalties() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        caps.put("java", 0.8);
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, null, caps);

        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("coding");
        reqCaps.add("java");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("Score should be positive", score > 0);
        assertTrue("Score should be reasonable", score < 5);
    }

    @Test
    public void testNormalScoring_emptyCapabilities() {
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, null, new HashMap<String, Double>());

        TaskRequirement req = createRequirement(new ArrayList<String>(), 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("Score should be positive with empty caps", score > 0);
    }

    // ========== Boundary penalty ==========

    @Test
    public void testBoundaryPenalty_internetConflict() {
        List<String> boundaries = new ArrayList<>();
        boundaries.add("No internet access (cannot fetch external APIs)");
        boundaries.add("File operations limited to workspace directory");

        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, boundaries, caps);

        // Task requires web-related capability
        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("web_scraping");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double scoreWithoutBoundary = calculator.calculate(worker, createRequirement(Arrays.asList("coding"), 4000));
        double scoreWithBoundary = calculator.calculate(worker, req);

        // Score should be significantly lower when boundary conflicts
        assertTrue("Boundary conflict should reduce score",
                scoreWithBoundary < scoreWithoutBoundary);
        // With 0.3 multiplier, the reduction should be substantial
        assertTrue("Boundary penalty should be significant",
                scoreWithBoundary < scoreWithoutBoundary * 0.5);
    }

    @Test
    public void testBoundaryPenalty_noConflict() {
        List<String> boundaries = new ArrayList<>();
        boundaries.add("No internet access");

        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, boundaries, caps);

        // Task requires capability unrelated to boundaries
        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("coding");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("Score should not be penalized for unrelated capabilities", score > 0);
    }

    @Test
    public void testBoundaryPenalty_noBoundariesDeclared() {
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, null, new HashMap<String, Double>());

        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("web_scraping");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("No penalty when worker has no boundaries declared", score >= 0);
    }

    // ========== Token budget penalty ==========

    @Test
    public void testTokenPenalty_exceedsBudget() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 64000, true, null, caps);

        // Task expects more tokens than worker allows
        TaskRequirement req = createRequirement(Arrays.asList("coding"), 128000);

        double scoreWithoutPenalty = calculator.calculate(worker, createRequirement(Arrays.asList("coding"), 4000));
        double scoreWithPenalty = calculator.calculate(worker, req);

        assertTrue("Token overrun should reduce score", scoreWithPenalty < scoreWithoutPenalty);
        // 0.5 multiplier
        assertTrue("Token penalty should halve the score",
                scoreWithPenalty <= scoreWithoutPenalty * 0.6);
    }

    @Test
    public void testTokenPenalty_withinBudget() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, null, caps);

        TaskRequirement req = createRequirement(Arrays.asList("coding"), 64000);

        double score = calculator.calculate(worker, req);
        assertTrue("No penalty when tokens within budget", score > 0);
    }

    @Test
    public void testTokenPenalty_noMaxTokensDeclared() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 0, true, null, caps);

        TaskRequirement req = createRequirement(Arrays.asList("coding"), 999999);

        double score = calculator.calculate(worker, req);
        assertTrue("No penalty when worker has no maxTokens declared", score > 0);
    }

    // ========== Tool support penalty ==========

    @Test
    public void testToolPenalty_needsToolButNoSupport() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        // Worker does NOT support tool calls
        WorkerInfo worker = createWorker("w1", "dev", 128000, false, null, caps);

        // Task requires tool execution
        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("tool_use");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double scoreWithoutTool = calculator.calculate(worker, createRequirement(Arrays.asList("coding"), 4000));
        double scoreWithTool = calculator.calculate(worker, req);

        assertTrue("Tool penalty should reduce score", scoreWithTool < scoreWithoutTool);
        // 0.3 multiplier
        assertTrue("Tool penalty should be substantial",
                scoreWithTool < scoreWithoutTool * 0.5);
    }

    @Test
    public void testToolPenalty_needsToolAndHasSupport() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("tool_use", 0.9);
        // Worker DOES support tool calls
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, null, caps);

        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("tool_use");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("No penalty when worker supports tool calls", score > 0);
    }

    @Test
    public void testToolPenalty_taskDoesNotNeedTools() {
        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 128000, false, null, caps);

        // Task does NOT require tool capabilities
        TaskRequirement req = createRequirement(Arrays.asList("coding"), 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("No tool penalty when task doesn't need tools", score > 0);
    }

    // ========== Multiple penalties stacking ==========

    @Test
    public void testMultiplePenalties_stackMultiplicatively() {
        // Worker with: limited tokens, no tool support, restricted boundaries
        List<String> boundaries = new ArrayList<>();
        boundaries.add("No internet access");

        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 32000, false, boundaries, caps);

        // Task needs: tool use + web access + high token budget
        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("tool_use");
        reqCaps.add("web_scraping");
        TaskRequirement req = createRequirement(reqCaps, 64000);

        double score = calculator.calculate(worker, req);

        // All three penalties should apply: 0.3 (boundary) * 0.5 (token) * 0.3 (tool) = 0.045
        // Compared to same worker with no conflicts
        double scoreNoPenalty = calculator.calculate(worker, createRequirement(Arrays.asList("coding"), 1000));

        assertTrue("Multiple penalties should compound", score < scoreNoPenalty * 0.1);
    }

    @Test
    public void testNoPenalties_allConditionsMet() {
        // Perfect match: no boundary conflicts, tokens within budget, supports tools
        List<String> boundaries = new ArrayList<>();
        boundaries.add("No internet access");

        Map<String, Double> caps = new HashMap<>();
        caps.put("coding", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, boundaries, caps);

        TaskRequirement req = createRequirement(Arrays.asList("coding"), 4000);

        double score = calculator.calculate(worker, req);
        assertTrue("Score should be positive with all conditions met", score > 0);
    }

    // ========== Chinese boundary detection ==========

    @Test
    public void testBoundaryPenalty_chineseBoundary() {
        List<String> boundaries = new ArrayList<>();
        boundaries.add("无互联网访问能力（不能调用外部 API）");

        Map<String, Double> caps = new HashMap<>();
        caps.put("编程", 0.9);
        WorkerInfo worker = createWorker("w1", "dev", 128000, true, boundaries, caps);

        // Chinese capability that conflicts
        List<String> reqCaps = new ArrayList<>();
        reqCaps.add("网络访问");
        TaskRequirement req = createRequirement(reqCaps, 4000);

        double scoreNoConflict = calculator.calculate(worker, createRequirement(Arrays.asList("编程"), 4000));
        double scoreConflict = calculator.calculate(worker, req);

        assertTrue("Chinese boundary conflict should reduce score", scoreConflict < scoreNoConflict);
    }
}
