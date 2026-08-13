package com.github.obhen233.router.local;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Defines a routing category with associated keywords and capabilities.
 * <p>
 * Each category represents a type of user request (e.g., bug_fix, refactoring)
 * and maps to the worker capabilities required to handle it.
 */
public class CategoryDef {

    private final String id;
    private final List<String> chineseKeywords;
    private final List<String> englishKeywords;
    private final List<String> capabilities;
    private final String description;

    public CategoryDef(String id, List<String> chineseKeywords,
                       List<String> englishKeywords, List<String> capabilities,
                       String description) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.chineseKeywords = chineseKeywords != null
                ? Collections.unmodifiableList(new ArrayList<>(chineseKeywords))
                : Collections.emptyList();
        this.englishKeywords = englishKeywords != null
                ? Collections.unmodifiableList(new ArrayList<>(englishKeywords))
                : Collections.emptyList();
        this.capabilities = capabilities != null
                ? Collections.unmodifiableList(new ArrayList<>(capabilities))
                : Collections.emptyList();
        this.description = description != null ? description : "";
    }

    public String getId() {
        return id;
    }

    public List<String> getChineseKeywords() {
        return chineseKeywords;
    }

    public List<String> getEnglishKeywords() {
        return englishKeywords;
    }

    public List<String> getCapabilities() {
        return capabilities;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Returns all keywords (Chinese + English) combined.
     */
    public List<String> allKeywords() {
        List<String> all = new ArrayList<>(chineseKeywords.size() + englishKeywords.size());
        all.addAll(chineseKeywords);
        all.addAll(englishKeywords);
        return all;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CategoryDef)) return false;
        CategoryDef that = (CategoryDef) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "CategoryDef{id='" + id + "', keywords=" + allKeywords().size() + "}";
    }

    // ========== Built-in defaults ==========

    /**
     * Default routing categories covering six architectural dimensions:
     * <ol>
     *   <li><b>Cognitive tier</b> — reasoning, execution (thinking/acting separated)</li>
     *   <li><b>Software engineering</b> — bug_fix, refactoring, testing, code_review, documentation</li>
     *   <li><b>System & infrastructure</b> — architecture, devops, security, communication</li>
     *   <li><b>Data & pipeline</b> — data_analysis, bi_analytics, pipeline, mathematics</li>
     *   <li><b>Special processing</b> — file_processing, vision</li>
     *   <li><b>Embedded & hardware</b> — sensor, motion_control, embedded, coordination</li>
     * </ol>
     */
    public static List<CategoryDef> defaults() {
        return Arrays.asList(

                // ======== 1. Cognitive tier ========
                new CategoryDef("refactoring",
                        Arrays.asList("重构", "优化", "清理", "重组", "重写"),
                        Arrays.asList("refactor", "optimize", "cleanup", "rewrite", "restructure"),
                        Arrays.asList("code", "refactoring"),
                        "Code refactoring and optimization"),

                new CategoryDef("bug_fix",
                        Arrays.asList("bug", "修复", "错误", "异常", "崩溃", "故障", "问题"),
                        Arrays.asList("bug", "fix", "error", "crash", "fault", "issue", "defect"),
                        Arrays.asList("code", "debugging"),
                        "Bug fixing and debugging"),

                new CategoryDef("testing",
                        Arrays.asList("测试", "单元测试", "集成测试", "自动化测试", "用例"),
                        Arrays.asList("test", "unit test", "integration test", "coverage"),
                        Arrays.asList("testing", "code"),
                        "Writing and running tests"),

                new CategoryDef("documentation",
                        Arrays.asList("文档", "注释", "说明", "手册", "readme"),
                        Arrays.asList("document", "readme", "javadoc", "wiki"),
                        Arrays.asList("documentation"),
                        "Writing documentation and comments"),

                new CategoryDef("code_review",
                        Arrays.asList("审查", "检查", "评审", "复核"),
                        Arrays.asList("review", "inspect", "audit"),
                        Arrays.asList("code review"),
                        "Code review and inspection"),

                new CategoryDef("architecture",
                        Arrays.asList("架构", "设计", "模式", "方案", "顶层"),
                        Arrays.asList("architecture", "design", "pattern", "overview"),
                        Arrays.asList("architecture design"),
                        "Architecture and design planning"),

                new CategoryDef("devops",
                        Arrays.asList("部署", "发布", "docker", "容器", "流水线", "上线"),
                        Arrays.asList("deploy", "ci/cd", "pipeline", "release", "devops"),
                        Arrays.asList("devops"),
                        "DevOps, deployment and CI/CD operations"),

                new CategoryDef("feature",
                        Arrays.asList("新增", "功能", "实现", "开发", "添加", "编程", "代码", "排序", "写", "编写", "算法"),
                        Arrays.asList("feature", "implement", "add", "create", "new"),
                        Arrays.asList("code"),
                        "New feature implementation"),

                new CategoryDef("data_analysis",
                        Arrays.asList("数据", "分析", "图表", "统计", "可视化"),
                        Arrays.asList("data", "analysis", "chart", "statistics", "visualization"),
                        Arrays.asList("data analysis"),
                        "Data analysis and visualization"),

                new CategoryDef("mathematics",
                        Arrays.asList("数学", "方程", "未知数", "计算", "求解", "加减乘除", "等于", "多少", "求"),
                        Arrays.asList("math", "calculate", "equation", "solve", "compute"),
                        Arrays.asList("mathematics"),
                        "Mathematical computation and problem solving"),

                // ======== New categories ========
                new CategoryDef("reasoning",
                        Arrays.asList("推理", "思考", "规划", "决策", "策略", "推演", "评估", "权衡"),
                        Arrays.asList("reasoning", "planning", "strategy", "analyze", "evaluate"),
                        Arrays.asList("reasoning"),
                        "Deep reasoning, planning and strategic analysis"),

                new CategoryDef("execution",
                        Arrays.asList("执行", "生成", "构建", "产出"),
                        Arrays.asList("execute", "generate", "produce"),
                        Arrays.asList("code"),
                        "Task execution and code generation"),

                new CategoryDef("bug_fix",
                        Arrays.asList("bug", "修复", "错误", "异常", "崩溃", "故障", "问题", "缺陷", "报错", "排查", "调试"),
                        Arrays.asList("bug", "fix", "error", "crash", "fault", "issue", "defect", "debug", "exception"),
                        Arrays.asList("code", "debugging"),
                        "Bug fixing and debugging"),

                new CategoryDef("refactoring",
                        Arrays.asList("重构", "优化", "清理", "重组", "重写", "改造", "简化", "重构", "迁移"),
                        Arrays.asList("refactor", "optimize", "cleanup", "rewrite", "restructure", "migrate", "simplify"),
                        Arrays.asList("code", "refactoring"),
                        "Code refactoring and optimization"),

                new CategoryDef("testing",
                        Arrays.asList("测试", "单元测试", "集成测试", "自动化测试", "用例", "覆盖率", "断言", "mock", "benchmark"),
                        Arrays.asList("test", "unit test", "integration test", "coverage", "benchmark", "assert", "mock"),
                        Arrays.asList("testing", "code"),
                        "Writing and running tests"),

                new CategoryDef("code_review",
                        Arrays.asList("审查", "检查", "评审", "复核", "审计", "质量"),
                        Arrays.asList("review", "inspect", "audit", "quality"),
                        Arrays.asList("code review"),
                        "Code review and quality inspection"),

                new CategoryDef("documentation",
                        Arrays.asList("文档", "注释", "说明", "手册", "readme", "教程", "规范", "wiki"),
                        Arrays.asList("document", "readme", "javadoc", "wiki", "tutorial", "spec"),
                        Arrays.asList("documentation"),
                        "Writing documentation and specifications"),

                // ======== 3. System & infrastructure ========
                new CategoryDef("architecture",
                        Arrays.asList("架构", "设计", "模式", "方案", "顶层", "系统设计", "技术选型", "模块划分"),
                        Arrays.asList("architecture", "design", "pattern", "overview", "system design", "tech stack"),
                        Arrays.asList("architecture design"),
                        "Architecture and system design"),

                new CategoryDef("devops",
                        Arrays.asList("部署", "发布", "docker", "容器", "流水线", "上线", "运维", "监控", "k8s", "kubernetes", "ci/cd"),
                        Arrays.asList("deploy", "ci/cd", "pipeline", "release", "devops", "monitor", "kubernetes", "docker"),
                        Arrays.asList("devops"),
                        "DevOps, deployment and CI/CD operations"),

                new CategoryDef("security",
                        Arrays.asList("安全", "权限", "加密", "认证", "授权", "防火墙", "漏洞", "风险", "合规", "审计"),
                        Arrays.asList("security", "permission", "encrypt", "auth", "vulnerability", "compliance", "audit"),
                        Arrays.asList("security"),
                        "Security, authentication and compliance"),

                new CategoryDef("communication",
                        Arrays.asList("通信", "网络", "消息", "队列", "rpc", "grpc", "网关", "路由", "协议", "同步", "异步"),
                        Arrays.asList("communication", "network", "message", "queue", "rpc", "grpc", "gateway", "protocol"),
                        Arrays.asList("communication"),
                        "Inter-node communication and networking"),

                // ======== 4. Data & pipeline ========
                new CategoryDef("data_analysis",
                        Arrays.asList("数据", "分析", "图表", "统计", "可视化", "报表", "挖掘", "etl"),
                        Arrays.asList("data", "analysis", "chart", "statistics", "visualization", "etl", "dashboard"),
                        Arrays.asList("data analysis"),
                        "Data analysis and visualization"),

                new CategoryDef("bi_analytics",
                        Arrays.asList("bi", "商业智能", "经营分析", "指标", "kpi", "大屏", "数据仓库", "数仓", "olap"),
                        Arrays.asList("bi", "business intelligence", "analytics", "kpi", "metric", "dashboard", "olap"),
                        Arrays.asList("bi analytics"),
                        "Business intelligence and KPI analytics"),

                new CategoryDef("pipeline",
                        Arrays.asList("流水线", "编排", "工作流", "mapreduce", "批处理", "流处理", "调度", "任务链", "dag"),
                        Arrays.asList("pipeline", "workflow", "mapreduce", "batch", "stream", "scheduler", "dag", "orchestrate"),
                        Arrays.asList("pipeline", "processing"),
                        "Workflow orchestration and pipeline processing"),

                new CategoryDef("mathematics",
                        Arrays.asList("数学", "方程", "未知数", "计算", "求解", "加减乘除", "等于", "多少", "求", "微积分", "矩阵"),
                        Arrays.asList("math", "calculate", "equation", "solve", "compute", "calculus", "matrix"),
                        Arrays.asList("mathematics"),
                        "Mathematical computation and problem solving"),

                // ======== 5. Special processing ========
                new CategoryDef("file_processing",
                        Arrays.asList("文件", "附件", "上传", "下载", "解析", "转换", "压缩", "解压", "导入", "导出"),
                        Arrays.asList("file", "attachment", "upload", "download", "parse", "convert", "compress", "import", "export"),
                        Arrays.asList("file processing"),
                        "File and attachment processing"),

                new CategoryDef("vision",
                        Arrays.asList("图像", "图片", "视觉", "识别", "检测", "ocr", "人脸", "视频", "截图", "渲染"),
                        Arrays.asList("image", "vision", "detect", "recognize", "ocr", "video", "render", "screenshot"),
                        Arrays.asList("vision"),
                        "Image and visual processing"),

                // ======== 6. Embedded & hardware ========
                new CategoryDef("sensor",
                        Arrays.asList("传感器", "采集", "信号", "温度", "湿度", "压力", "gps", "定位", "感知", "监测"),
                        Arrays.asList("sensor", "signal", "collect", "temperature", "pressure", "gps", "monitor"),
                        Arrays.asList("sensor"),
                        "Sensor data acquisition and processing"),

                new CategoryDef("motion_control",
                        Arrays.asList("运动", "控制", "电机", "舵机", "机械臂", "机器人", "导航", "轨迹", "pid"),
                        Arrays.asList("motion", "control", "motor", "robot", "navigation", "trajectory", "pid"),
                        Arrays.asList("motion control"),
                        "Motion control and robotics"),

                new CategoryDef("embedded",
                        Arrays.asList("嵌入式", "单片机", "firmware", "rtos", "gpio", "spi", "i2c", "物联网", "iot"),
                        Arrays.asList("embedded", "firmware", "rtos", "gpio", "spi", "i2c", "iot"),
                        Arrays.asList("embedded"),
                        "Embedded systems and firmware"),

                new CategoryDef("coordination",
                        Arrays.asList("协调", "编排", "调度", "分发", "负载均衡", "共识", "选举", "同步", "协同", "集群"),
                        Arrays.asList("coordinate", "orchestrate", "schedule", "distribute", "consensus", "sync", "cluster", "load balance"),
                        Arrays.asList("coordination"),
                        "Multi-agent coordination and task distribution")
        );
    }
}
