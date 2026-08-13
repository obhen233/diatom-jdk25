package com.github.obhen233.core.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gateway 地址配置管理
 * 支持多地址主备切换
 *
 * 配置项: gateway.url
 * 格式: http://127.0.0.1:8080 或逗号分隔的多地址
 */
public class GatewayAddressConfig {
    private static final Logger logger = LoggerFactory.getLogger(GatewayAddressConfig.class);
    private static final String DEFAULT_GATEWAY_URL = "http://127.0.0.1:8080";
    private static final long HEALTH_CHECK_INTERVAL_SECONDS = 30;

    private final List<String> gatewayUrls = new ArrayList<>();
    private volatile int currentIndex = 0;
    private ScheduledExecutorService healthCheckScheduler;

    public GatewayAddressConfig() {
        String urls = System.getProperty("gateway.url",
                System.getenv("GATEWAY_URL"));
        if (urls != null && !urls.isEmpty()) {
            parseUrls(urls);
        } else {
            gatewayUrls.add(DEFAULT_GATEWAY_URL);
        }
    }

    public GatewayAddressConfig(String urlConfig) {
        if (urlConfig != null && !urlConfig.isEmpty()) {
            parseUrls(urlConfig);
        } else {
            gatewayUrls.add(DEFAULT_GATEWAY_URL);
        }
    }

    private void parseUrls(String urls) {
        // 支持 gateway.url[0]=http://... gateway.url[1]=http://... 索引格式
        if (urls.contains("[") && urls.contains("]=")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "gateway\\.url\\[(\\d+)\\]=(\\S+)").matcher(urls);
            while (matcher.find()) {
                int idx = Integer.parseInt(matcher.group(1));
                String url = matcher.group(2).trim();
                if (!url.isEmpty()) {
                    // 确保列表长度足够
                    while (gatewayUrls.size() <= idx) {
                        gatewayUrls.add(null);
                    }
                    gatewayUrls.set(idx, url);
                }
            }
            // 移除空位
            gatewayUrls.removeIf(s -> s == null);
        } else {
            for (String url : urls.split(",")) {
                url = url.trim();
                if (!url.isEmpty()) {
                    gatewayUrls.add(url);
                }
            }
        }
        if (gatewayUrls.isEmpty()) {
            gatewayUrls.add(DEFAULT_GATEWAY_URL);
        }
        logger.info("Gateway addresses configured: {} (primary: {})", gatewayUrls, gatewayUrls.get(0));
    }

    /**
     * 获取当前主 Gateway 地址
     */
    public String getPrimaryUrl() {
        return gatewayUrls.isEmpty() ? DEFAULT_GATEWAY_URL : gatewayUrls.get(0);
    }

    /**
     * 获取所有 Gateway 地址
     */
    public List<String> getAllUrls() {
        return Collections.unmodifiableList(gatewayUrls);
    }

    /**
     * 切换到下一个可用 Gateway 地址（主备切换）
     */
    public String switchToNext() {
        if (gatewayUrls.size() <= 1) return getPrimaryUrl();
        currentIndex = (currentIndex + 1) % gatewayUrls.size();
        logger.info("Switched to next gateway: {} (index={})", gatewayUrls.get(currentIndex), currentIndex);
        return gatewayUrls.get(currentIndex);
    }

    /**
     * 重置到主地址
     */
    public void resetToPrimary() {
        currentIndex = 0;
    }

    /**
     * 获取当前正在使用的地址
     */
    public String getCurrentUrl() {
        return gatewayUrls.get(currentIndex);
    }

    /**
     * 更新指定索引的地址
     */
    public void updateUrlAtIndex(int index, String url) {
        if (index < 0 || index >= gatewayUrls.size()) {
            gatewayUrls.add(url);
        } else {
            gatewayUrls.set(index, url);
        }
        logger.info("Gateway address updated at index {}: {}", index, url);
    }

    /**
     * 更新配置（通过 config set 修改时调用）
     * 支持逗号分隔格式和 gateway.url[0]=... 索引格式
     */
    public void updateConfig(String urlConfig) {
        gatewayUrls.clear();
        currentIndex = 0;
        parseUrls(urlConfig);
    }

    /**
     * 保存到系统属性（供子进程继承）
     */
    public void saveToSystemProperty() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gatewayUrls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(gatewayUrls.get(i));
        }
        System.setProperty("gateway.url", sb.toString());
    }

    /**
     * 启动主备周期性检测
     * 当使用备用地址时，周期性检测主 Gateway 是否恢复
     */
    public synchronized void startHealthCheck() {
        if (healthCheckScheduler != null) return;
        healthCheckScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gateway-health-check");
            t.setDaemon(true);
            return t;
        });
        healthCheckScheduler.scheduleAtFixedRate(this::checkPrimaryHealth,
                HEALTH_CHECK_INTERVAL_SECONDS, HEALTH_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.info("Gateway health check started (interval={}s)", HEALTH_CHECK_INTERVAL_SECONDS);
    }

    /**
     * 停止健康检测
     */
    public synchronized void stopHealthCheck() {
        if (healthCheckScheduler != null) {
            healthCheckScheduler.shutdown();
            healthCheckScheduler = null;
        }
    }

    /**
     * 检测主 Gateway 是否恢复
     * 仅在当前使用备用地址时执行
     */
    private void checkPrimaryHealth() {
        if (currentIndex == 0) return; // 已在主地址，无需检测
        String primaryUrl = gatewayUrls.get(0);
        try {
            java.net.URL url = new java.net.URL(primaryUrl + "/gateway/v1/health");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200) {
                logger.info("Primary gateway recovered: {}, resetting to primary", primaryUrl);
                resetToPrimary();
            }
        } catch (Exception e) {
            logger.debug("Primary gateway still unavailable: {}", e.getMessage());
        }
    }
}
