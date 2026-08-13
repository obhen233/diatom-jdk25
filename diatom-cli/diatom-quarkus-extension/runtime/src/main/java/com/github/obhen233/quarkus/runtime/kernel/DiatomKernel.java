package com.github.obhen233.quarkus.runtime.kernel;

import com.github.obhen233.adapter.spi.AgentAdapter;
import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.core.gateway.agent.GatewayAgent;
import com.github.obhen233.core.gateway.collaboration.ResourceLockManager;
import com.github.obhen233.core.gateway.http.GatewayHttpServer;
import com.github.obhen233.core.gateway.http.WorkerHttpServer;
import com.github.obhen233.core.gateway.registry.WorkerRegistry;
import com.github.obhen233.core.gateway.routing.CapabilityRouter;
import com.github.obhen233.core.gateway.task.TaskManager;
import com.github.obhen233.quarkus.runtime.config.DiatomRuntimeConfig;
import org.jboss.logging.Logger;

/**
 * Diatom Quarkus 纯 Java 内核。
 *
 * <p>持有共享组件图，并按运行模式装配对应 Bootstrap：
 * <ul>
 *   <li>{@code standard} → {@link BareBootstrap}（纯引擎，不起 HTTP）</li>
 *   <li>{@code worker} → {@link WorkerBootstrap}</li>
 *   <li>{@code gateway} / {@code gateway:nacos|consul|eureka} → {@link GatewayBootstrap}</li>
 *   <li>{@code child} / {@code gateway:child} → {@link ChildBootstrap}</li>
 *   <li>{@code adapter} → {@link AdapterBootstrap}</li>
 * </ul>
 *
 * <p>可独立于 Quarkus 上下文进行纯 JUnit 测试。{@link #start()} / {@link #stop()} 幂等。
 */
public class DiatomKernel {

    private static final Logger LOGGER = Logger.getLogger(DiatomKernel.class);

    private final DiatomRuntimeConfig config;
    private final SharedComponents shared;
    private final ModeBootstrap bootstrap;
    private final String mode;
    private volatile boolean started;
    private volatile boolean stopped;

    public DiatomKernel(DiatomRuntimeConfig config) {
        this.config = config;
        this.mode = normalizeMode(config.mode());
        this.shared = new SharedComponents(config);
        this.bootstrap = createBootstrap();
    }

    /** 按运行模式启动对应 Bootstrap。幂等。 */
    public synchronized void start() {
        if (started || stopped) {
            return;
        }
        LOGGER.infof("Diatom kernel starting in mode=%s", mode);
        try {
            bootstrap.start();
        } catch (Exception e) {
            LOGGER.errorf("Diatom kernel bootstrap start failed in mode=%s: %s",
                    mode, e.getMessage());
        }
        started = true;
        LOGGER.infof("Diatom kernel started in mode=%s", mode);
    }

    /** 逆序关停。幂等。 */
    public synchronized void stop() {
        if (!started || stopped) {
            return;
        }
        LOGGER.infof("Diatom kernel stopping in mode=%s", mode);
        try {
            bootstrap.stop();
        } catch (Exception e) {
            LOGGER.warnf("Diatom kernel bootstrap stop failed: %s", e.getMessage());
        } finally {
            shared.close();
        }
        stopped = true;
        LOGGER.infof("Diatom kernel stopped in mode=%s", mode);
    }

    public String getMode() {
        return mode;
    }

    public DiatomRuntimeConfig config() {
        return config;
    }

    public SharedComponents getShared() {
        return shared;
    }

    // ===== 组件访问（Phase 2 REST 资源使用）=====

    public ReActAgent reActAgent() {
        return bootstrap.reActAgent();
    }

    public WorkerRegistry workerRegistry() {
        return bootstrap.workerRegistry();
    }

    public GatewayAgent gatewayAgent() {
        return bootstrap.gatewayAgent();
    }

    public CapabilityRouter capabilityRouter() {
        return bootstrap.capabilityRouter();
    }

    public TaskManager taskManager() {
        return bootstrap.taskManager();
    }

    public ResourceLockManager lockManager() {
        return bootstrap.lockManager();
    }

    public GatewayHttpServer gatewayHttpServer() {
        return bootstrap.gatewayHttpServer();
    }

    public WorkerHttpServer workerHttpServer() {
        return bootstrap.workerHttpServer();
    }

    public WorkerLoadState loadState() {
        return bootstrap.loadState();
    }

    public AgentAdapter agentAdapter() {
        return bootstrap.agentAdapter();
    }

    /** Gateway 直连注册服务（worker / adapter / child 模式）。 */
    public com.github.obhen233.quarkus.runtime.rest.QuarkusRegistrationService registrationService() {
        return bootstrap.registrationService();
    }

    /** 初始化 Gateway 队列模式（gateway/child 模式，202 异步）。 */
    public void initGatewayQueue() {
        if (bootstrap instanceof GatewayBootstrap gb) {
            gb.initGatewayQueue();
        }
    }

    public com.github.obhen233.spi.TaskQueueProvider taskQueueProvider() {
        return bootstrap.taskQueueProvider();
    }

    public com.github.obhen233.quarkus.runtime.queue.TaskResultStore taskResultStore() {
        return bootstrap.taskResultStore();
    }

    public com.github.obhen233.quarkus.runtime.queue.TaskQueueProcessor taskQueueProcessor() {
        return bootstrap.taskQueueProcessor();
    }

    /** 独立端口模式下实际监听端口（供测试）。 */
    public int getServerPort() {
        return bootstrap.serverPort();
    }

    // ===================== 内部 =====================

    private ModeBootstrap createBootstrap() {
        return switch (mode) {
            case "worker" -> new WorkerBootstrap(this);
            case "gateway" -> new GatewayBootstrap(this);
            case "child" -> new ChildBootstrap(this);
            case "adapter" -> new AdapterBootstrap(this);
            default -> new BareBootstrap(this);
        };
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
