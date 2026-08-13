package com.github.obhen233.router.demo;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.registry.WorkerMetrics;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.router.config.LocalRouterConfig;
import com.github.obhen233.router.local.CategoryDef;
import com.github.obhen233.router.local.HanlpTextClassifier;
import com.github.obhen233.router.local.LocalRequestRouterImpl;
import com.github.obhen233.spi.RoutingResult;

import java.util.*;

/**
 * 本地路由模拟演示
 *
 * 模拟场景：
 *   worker01 (D:\work01) — 擅长数学计算 (capability: mathematics)
 *   worker02 (D:\work02) — 擅长代码编程 (capability: code)
 *
 * 测试请求：
 *   1. 数学题 → 期望路由到 worker01
 *   2. 编程题 → 期望路由到 worker02
 */
public class RouterDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║       Diatom Local ML Router — 模拟演示            ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ========== 1. 创建配置 ==========
        LocalRouterConfig config = new LocalRouterConfig();
        System.out.println("配置: " + config);
        System.out.println();

        // ========== 2. 创建分类器和路由器 ==========
        List<CategoryDef> categories = CategoryDef.defaults();
        printCategories(categories);
        System.out.println();

        HanlpTextClassifier classifier = new HanlpTextClassifier(categories, config);
        LocalRequestRouterImpl router = new LocalRequestRouterImpl(config, classifier, categories);

        // ========== 3. 创建两个 Worker ==========
        List<WorkerInfo> workers = createWorkers();
        printWorkers(workers);
        System.out.println();

        // ========== 4. 测试请求 ==========
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println("测试请求");
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println();

        // 请求 1: 数学题
        String mathQuery = "有两个未知数 设为x,y 我知道x+y=3 x-y=1 请问 x 是多少 y 是多少";
        testRequest(router, "【数学题】", mathQuery, workers);

        // 请求 2: 编程题
        String codeQuery = "请帮我写一个冒泡排序的简单示例代码 使用python " +
                "我现在有一个序列，[1,32,56,78,25] 从小到大排列";
        testRequest(router, "【编程题】", codeQuery, workers);

        // 额外: 一些其他测试
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println("额外测试（其他常见请求）");
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println();

        testRequest(router, "Bug修复", "修复登录页面崩溃问题", workers);
        testRequest(router, "重构", "重构用户认证模块的代码", workers);
        testRequest(router, "测试", "添加单元测试覆盖支付模块", workers);
        testRequest(router, "文档", "更新API接口文档和注释", workers);

        // ========== 5. 总结 ==========
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println("说明");
        System.out.println("──────────────────────────────────────────────────────");
        System.out.println("- 置信度 >= 0.70 : 直接返回路由结果，跳过 LLM");
        System.out.println("- 置信度 < 0.70  : 返回 null，回退到 LLM");
        System.out.println("- 可通过系统属性调整: -Dgateway.router.local.threshold=0.5");
    }

    private static void testRequest(LocalRequestRouterImpl router, String label,
                                     String query, List<WorkerInfo> workers) {
        System.out.println("▸ " + label);
        System.out.println("  请求: " + truncate(query, 80));

        // 先看 keyword matching 的结果
        // (我们通过 router 的 classify 间接看，但 route 方法已经封装了)
        RoutingResult result = router.route(query, workers);

        if (result == null) {
            System.out.println("  ⚠  本地路由无法处理 → 回退到 LLM");
        } else {
            String workerLabel = result.getRequirement().getSuggestedWorkerId();
            if (workerLabel == null) workerLabel = "(未指定)";
            System.out.println("  ✓  路由成功!");
            System.out.println("     分类: " + result.getRequirement().getTaskType()
                    + " | 置信度: " + String.format("%.2f", result.getConfidence())
                    + " | 来源: " + result.getSource());
            System.out.println("     能力需求: " + result.getRequirement().getRequiredCapabilities());
            System.out.println("     建议 Worker: " + workerLabel);
        }
        System.out.println();
    }

    private static List<WorkerInfo> createWorkers() {
        List<WorkerInfo> workers = new ArrayList<>();

        // worker01: 擅长数学计算
        WorkerMetrics metrics1 = new WorkerMetrics();
        metrics1.setCurrentLoad(0.2);
        WorkerInfo w1 = new WorkerInfo();
        w1.setWorkerId("worker01");
        w1.setModel("gpt-4");
        w1.setHost("localhost");
        w1.setPort(9001);
        Map<String, Double> caps1 = new HashMap<>();
        caps1.put("mathematics", 1.0);
        w1.setCapabilities(caps1);
        w1.setMetrics(metrics1);
        w1.setWorkspace("D:\\work01");
        workers.add(w1);

        // worker02: 擅长代码编程
        WorkerMetrics metrics2 = new WorkerMetrics();
        metrics2.setCurrentLoad(0.3);
        WorkerInfo w2 = new WorkerInfo();
        w2.setWorkerId("worker02");
        w2.setModel("gpt-4");
        w2.setHost("localhost");
        w2.setPort(9002);
        Map<String, Double> caps2 = new HashMap<>();
        caps2.put("code", 1.0);
        w2.setCapabilities(caps2);
        w2.setMetrics(metrics2);
        w2.setWorkspace("D:\\work02");
        workers.add(w2);

        return workers;
    }

    private static void printCategories(List<CategoryDef> categories) {
        System.out.println("内置分类 (" + categories.size() + " 个):");
        for (CategoryDef cat : categories) {
            System.out.println("  - " + cat.getId() + " (" + cat.getDescription() + ")");
            System.out.println("    中文关键词: " + cat.getChineseKeywords());
            System.out.println("    能力需求: " + cat.getCapabilities());
        }
    }

    private static void printWorkers(List<WorkerInfo> workers) {
        System.out.println("注册 Worker:");
        for (WorkerInfo w : workers) {
            System.out.println("  - " + w.getWorkerId()
                    + " [workspace=" + w.getWorkspace() + "]"
                    + " capabilities=" + w.getCapabilities().keySet());
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
