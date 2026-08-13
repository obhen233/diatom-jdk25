package com.github.obhen233.starter;

import com.github.obhen233.spi.PluginClassLoader;
import com.github.obhen233.starter.DiatomProperties.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 插件路径自动配置。
 *
 * 在 {@link com.github.obhen233.starter.CoreCommandConfiguration} 调用
 * {@link com.github.obhen233.spi.SpiLoader#loadAll()} 之前，
 * 确保 diatam.jar.dir 系统属性和所有插件来源路径已正确设置。
 *
 * <h3>插件搜索优先级（高 → 低）</h3>
 * <ol>
 *   <li>{@code diatam.plugin.paths} 指定的额外目录</li>
 *   <li>Bundled 插件（从 {@code META-INF/diatom-plugins/*.jar} 提取）</li>
 *   <li>Classpath 条目同级 {@code plugins/} 目录</li>
 *   <li>Fat JAR 同级 {@code plugins/} 目录</li>
 *   <li>{diatom.jar.dir}/plugins/ — jarDir 对等目录</li>
 *   <li>{diatom.jar.dir}/.diatom/plugins/ — 实例级默认目录</li>
 *   <li>~/.diatom/plugins/ — 全局默认目录</li>
 * </ol>
 */
@Configuration
@AutoConfigureBefore(CoreCommandConfiguration.class)
@EnableConfigurationProperties(DiatomProperties.class)
public class PluginPathConfiguration implements DisposableBean {
    private static final Logger logger = LoggerFactory.getLogger(PluginPathConfiguration.class);

    private final DiatomProperties properties;
    private PluginResourceScanner scanner;

    public PluginPathConfiguration(DiatomProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void initPluginPaths() {
        // ============================================================
        // 1. 确保 diatam.jar.dir 系统属性已设置
        // ============================================================
        if (System.getProperty("diatom.jar.dir") == null) {
            String workspaceDir = properties.getApp().getWorkspaceDir();
            if (workspaceDir != null && !workspaceDir.isEmpty()
                    && !workspaceDir.contains("${")) {
                System.setProperty("diatom.jar.dir", workspaceDir);
                logger.debug("Set diatam.jar.dir to configured workspace: {}", workspaceDir);
            } else {
                String userDir = System.getProperty("user.dir", ".");
                System.setProperty("diatom.jar.dir", userDir);
                logger.debug("Set diatam.jar.dir to user.dir: {}", userDir);
            }
        }

        Path jarDir = Paths.get(System.getProperty("diatom.jar.dir"));

        // ============================================================
        // 2. 收集所有插件来源（有序、去重）
        // ============================================================
        Set<Path> allPluginDirs = new LinkedHashSet<>();

        // 2a. diatam.plugin.paths 自定义路径（最高优先级）
        Plugin plugin = properties.getPlugin();
        List<String> customPaths = plugin.getPaths();
        if (customPaths != null) {
            for (String path : customPaths) {
                Path dir = Paths.get(path).toAbsolutePath().normalize();
                if (Files.isDirectory(dir)) {
                    allPluginDirs.add(dir);
                    logger.info("Added custom plugin path: {}", dir);
                } else {
                    logger.warn("Custom plugin path does not exist or is not a directory: {}", path);
                }
            }
        }

        // 2b. 自动发现：bundled / classpath-adjacent / fat JAR / jarDir peer
        this.scanner = new PluginResourceScanner();
        List<Path> discoveredDirs = scanner.discoverPluginDirs(jarDir);
        allPluginDirs.addAll(discoveredDirs);

        // 2c. 默认路径（{jarDir}/.diatom/plugins/ + ~/.diatom/plugins/）
        List<Path> defaultDirs = PluginClassLoader.getDefaultPluginDirs(jarDir);
        allPluginDirs.addAll(defaultDirs);

        // ============================================================
        // 3. 预初始化 PluginClassLoader（包含所有路径）
        //    当 SpiLoader.loadAll() 后续调用 PluginClassLoader.init() 时，
        //    由于 singleton 机制，会直接返回此实例
        // ============================================================
        if (allPluginDirs.isEmpty()) {
            logger.debug("No plugin directories found, skipping PluginClassLoader initialization");
            return;
        }

        PluginClassLoader.init(allPluginDirs.toArray(new Path[0]));
        logger.info("Plugin paths initialized: {} custom + {} discovered + {} default = {} total",
                customPaths != null ? customPaths.size() : 0,
                discoveredDirs.size(),
                defaultDirs.size(),
                allPluginDirs.size());
    }

    @Override
    public void destroy() {
        if (scanner != null) {
            scanner.cleanup();
        }
    }
}
