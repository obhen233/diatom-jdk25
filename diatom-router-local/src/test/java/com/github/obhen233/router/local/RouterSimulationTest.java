package com.github.obhen233.router.local;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.router.config.LocalRouterConfig;
import com.github.obhen233.spi.RoutingResult;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * 本地路由模拟测试
 *
 * 模拟场景：
 *   worker01 — 擅长数学计算 (capability: mathematics)
 *   worker02 — 擅长代码编程 (capability: code)
 *
 * 验证：
 *   1. 数学题 → 路由到 worker01
 *   2. 编程题 → 路由到 worker02
 *   3. 无关请求 → 返回 null (回退 LLM)
 */
public class RouterSimulationTest {

    private static LocalRequestRouterImpl router;
    private static List<WorkerInfo> workers;

    @BeforeClass
    public static void setup() {
        LocalRouterConfig config = new LocalRouterConfig();
        List<CategoryDef> categories = CategoryDef.defaults();
        HanlpTextClassifier classifier = new HanlpTextClassifier(categories, config);
        router = new LocalRequestRouterImpl(config, classifier, categories);

        // worker01: 擅长数学计算
        WorkerInfo w1 = new WorkerInfo();
        w1.setWorkerId("worker01");
        w1.setModel("gpt-4");
        w1.setPort(9001);
        w1.setWorkspace("D:\\work01");
        Map<String, Double> caps1 = new HashMap<>();
        caps1.put("mathematics", 1.0);
        w1.setCapabilities(caps1);

        // worker02: 擅长代码编程
        WorkerInfo w2 = new WorkerInfo();
        w2.setWorkerId("worker02");
        w2.setModel("gpt-4");
        w2.setPort(9002);
        w2.setWorkspace("D:\\work02");
        Map<String, Double> caps2 = new HashMap<>();
        caps2.put("code", 1.0);
        w2.setCapabilities(caps2);

        workers = Arrays.asList(w1, w2);
    }

    @Test
    public void testMathQuestionRoutesToWorker01() {
        // 请求: 有两个未知数 设为x,y 我知道x+y=3 x-y=1 请问 x 是多少 y 是多少
        // 期望: 匹配 mathematics 分类 → worker01
        String query = "有两个未知数 设为x,y 我知道x+y=3 x-y=1 请问 x 是多少 y 是多少";

        RoutingResult result = router.route(query, workers);

        assertNotNull("数学题应被本地路由处理", result);
        assertEquals("应分类为 mathematics", "mathematics", result.getRequirement().getTaskType());
        assertTrue("置信度应 >= 0.7", result.getConfidence() >= 0.7);
        assertEquals("应路由到 worker01", "worker01", result.getRequirement().getSuggestedWorkerId());
        System.out.println("[PASS] 数学题 → " + result.getRequirement().getTaskType()
                + " (conf=" + String.format("%.2f", result.getConfidence())
                + ") → " + result.getRequirement().getSuggestedWorkerId());
    }

    @Test
    public void testCodingQuestionRoutesToWorker02() {
        // 请求: 请帮我写一个冒泡排序的简单示例代码 使用python
        // 期望: 匹配 feature(code) 分类 → worker02
        String query = "请帮我写一个冒泡排序的简单示例代码 使用python " +
                "我现在有一个序列，[1,32,56,78,25] 从小到大排列";

        RoutingResult result = router.route(query, workers);

        assertNotNull("编程题应被本地路由处理", result);
        assertEquals("应分类为 feature", "feature", result.getRequirement().getTaskType());
        assertTrue("置信度应 >= 0.7", result.getConfidence() >= 0.7);
        assertEquals("应路由到 worker02", "worker02", result.getRequirement().getSuggestedWorkerId());
        System.out.println("[PASS] 编程题 → " + result.getRequirement().getTaskType()
                + " (conf=" + String.format("%.2f", result.getConfidence())
                + ") → " + result.getRequirement().getSuggestedWorkerId());
    }

    @Test
    public void testBugFixRoutesToExistingWorker() {
        // 请求: 修复登录页面崩溃问题
        // 期望: bug_fix 分类 (capabilities: code, debugging)
        // worker01 没有 code/debugging, worker02 有 code → 路由到 worker02
        RoutingResult result = router.route("修复登录页面崩溃问题", workers);

        assertNotNull(result);
        assertEquals("bug_fix", result.getRequirement().getTaskType());
        assertEquals("worker02", result.getRequirement().getSuggestedWorkerId());
        System.out.println("[PASS] Bug修复 → " + result.getRequirement().getTaskType()
                + " → " + result.getRequirement().getSuggestedWorkerId());
    }

    @Test
    public void testUnrecognizedRequestReturnsNull() {
        // 请求: "今天天气怎么样" — 无匹配关键词 → 回退 LLM
        RoutingResult result = router.route("今天天气怎么样", workers);
        assertNull("无关请求应回退到 LLM", result);
        System.out.println("[PASS] 无关请求 → null (回退 LLM)");
    }

    @Test
    public void testClassificationDetails() {
        // 详细展示匹配过程
        System.out.println();
        System.out.println("=== 路由详情 ===");

        // 数学题
        String mathQuery = "有两个未知数 设为x,y 我知道x+y=3 x-y=1 请问 x 是多少 y 是多少";
        RoutingResult mathResult = router.route(mathQuery, workers);
        System.out.println("  数学题: " + (mathResult != null
                ? "taskType=" + mathResult.getRequirement().getTaskType()
                + " | confidence=" + String.format("%.2f", mathResult.getConfidence())
                + " | suggestedWorker=" + mathResult.getRequirement().getSuggestedWorkerId()
                + " | source=" + mathResult.getSource()
                : "null (回退 LLM)"));

        // 编程题
        String codeQuery = "请帮我写一个冒泡排序的简单示例代码 使用python";
        RoutingResult codeResult = router.route(codeQuery, workers);
        System.out.println("  编程题: " + (codeResult != null
                ? "taskType=" + codeResult.getRequirement().getTaskType()
                + " | confidence=" + String.format("%.2f", codeResult.getConfidence())
                + " | suggestedWorker=" + codeResult.getRequirement().getSuggestedWorkerId()
                + " | source=" + codeResult.getSource()
                : "null (回退 LLM)"));

        assertNotNull("数学题应被路由", mathResult);
        assertNotNull("编程题应被路由", codeResult);
    }
}
