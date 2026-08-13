package com.github.obhen233.quarkus.deployment;

import com.github.obhen233.quarkus.runtime.DiatomQuarkusRuntime;
import com.github.obhen233.quarkus.runtime.components.DiatomComponents;
import com.github.obhen233.quarkus.runtime.components.DiatomRuntimeContext;
import com.github.obhen233.quarkus.runtime.rest.AdapterResource;
import com.github.obhen233.quarkus.runtime.rest.ChildGatewayResource;
import com.github.obhen233.quarkus.runtime.rest.GatewayResource;
import com.github.obhen233.quarkus.runtime.rest.MonitorResource;
import com.github.obhen233.quarkus.runtime.rest.TerminalResource;
import com.github.obhen233.quarkus.runtime.rest.WorkerResource;
import com.github.obhen233.quarkus.runtime.terminal.TerminalSessionManager;
import com.github.obhen233.quarkus.runtime.terminal.TerminalWebSocket;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalIndexedClassesBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import org.eclipse.microprofile.config.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Diatom Quarkus 扩展构建期处理器。
 *
 * <p>注册扩展 feature，把运行时 CDI Bean（组件生产者 / 门面 / 生命周期锚点）注册为
 * <b>不可移除</b> Bean（否则未被直接 @Inject 的 Bean 会被 ArC 裁剪）。
 * Phase 2：按构建期 {@code diatom.mode} 条件注册四模式 HTTP 资源 —— RESTEasy Reactive
 * 需要资源类同时被<b>索引</b>（{@code AdditionalIndexedClassesBuildItem}）且注册为
 * <b>不可移除 Bean</b>（{@code AdditionalBeanBuildItem}），否则扩展内 @Path 类会 404。
 */
public class DiatomExtensionProcessor {

    /** 扩展 feature 名称（展示在 Quarkus 构建输出中）。 */
    private static final String FEATURE = "diatom-quarkus-extension";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * 注册 CDI 运行时 Bean 为不可移除（组件生产者 + 门面 + 生命周期锚点）。
     */
    @BuildStep
    AdditionalBeanBuildItem diatomBeans() {
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClass(DiatomComponents.class)
                .addBeanClass(DiatomRuntimeContext.class)
                .addBeanClass(DiatomQuarkusRuntime.class)
                // TerminalResource 构造注入依赖 TerminalSessionManager，注册为不可移除 Bean
                .addBeanClass(TerminalSessionManager.class)
                .build();
    }

    // ===== Phase 2：四模式 HTTP 资源（按构建期 diatom.mode 条件注册）=====

    @BuildStep
    AdditionalIndexedClassesBuildItem indexRestResources(Config config) {
        return new AdditionalIndexedClassesBuildItem(resourceClasses(config));
    }

    @BuildStep
    AdditionalBeanBuildItem registerRestResources(Config config) {
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClasses(resourceClasses(config))
                .build();
    }

    /**
     * 按 {@code diatom.mode} 返回应注册的资源类名。归一化逻辑与
     * {@code DiatomKernel.normalizeMode} 一致：{@code gateway:child}→child、
     * {@code gateway:nacos|consul|eureka}→gateway、其余按字面。
     *
     * <p>Worker / Adapter / Child 都映射 {@code /worker/v1/chat}，同一进程内不能共存，
     * 因此按模式互斥注册。IDE Terminal REST（{@code /api/ide/terminal}）路径与模式无冲突，
     * 仅由 {@code diatom.terminal.enabled}（默认 false）控制。</p>
     */
    private static String[] resourceClasses(Config config) {
        String mode = normalizeMode(config.getOptionalValue("diatom.mode", String.class).orElse("standard"));
        boolean monitorEnabled = config.getOptionalValue("diatom.monitor.enabled", Boolean.class).orElse(true);
        boolean terminalEnabled = config.getOptionalValue("diatom.terminal.enabled", Boolean.class).orElse(false);
        List<String> classes = new ArrayList<>();
        switch (mode) {
            case "gateway" -> {
                classes.add(GatewayResource.class.getName());
                if (monitorEnabled) {
                    classes.add(MonitorResource.class.getName());
                }
            }
            case "child" -> {
                classes.add(GatewayResource.class.getName());
                classes.add(ChildGatewayResource.class.getName());
                if (monitorEnabled) {
                    classes.add(MonitorResource.class.getName());
                }
            }
            case "worker" -> classes.add(WorkerResource.class.getName());
            case "adapter" -> classes.add(AdapterResource.class.getName());
            default -> {
            }
        }
        if (terminalEnabled) {
            classes.add(TerminalResource.class.getName());
        }
        return classes.toArray(new String[0]);
    }

    /**
     * IDE Terminal WebSocket 端点（{@code /ide/terminal}，quarkus-websockets-next）按
     * {@code diatom.terminal.enabled} 条件索引 —— quarkus-websockets-next 通过 Jandex 索引发现
     * {@code @WebSocket} 类，默认关闭时不要索引，避免意外暴露 WS 端点。
     */
    @BuildStep
    AdditionalIndexedClassesBuildItem indexTerminalEndpoint(Config config) {
        boolean terminalEnabled = config.getOptionalValue("diatom.terminal.enabled", Boolean.class).orElse(false);
        if (!terminalEnabled) {
            return null;
        }
        return new AdditionalIndexedClassesBuildItem(TerminalWebSocket.class.getName());
    }

    /** 归一化模式：{@code gateway:child} → child；{@code gateway:nacos|consul|eureka} → gateway。 */
    private static String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) {
            return "standard";
        }
        String m = mode.trim().toLowerCase();
        if (m.startsWith("gateway:")) {
            String subtype = m.substring("gateway:".length());
            if ("child".equals(subtype)) {
                return "child";
            }
            return "gateway";
        }
        return switch (m) {
            case "worker", "gateway", "child", "adapter", "standard" -> m;
            default -> "standard";
        };
    }
}
