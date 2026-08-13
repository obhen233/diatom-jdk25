package com.github.obhen233.quarkus.runtime;

import com.github.obhen233.quarkus.runtime.components.DiatomComponents;
import com.github.obhen233.quarkus.runtime.kernel.DiatomKernel;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Diatom Quarkus 运行时装配锚点。
 *
 * <p>{@code @Observes StartupEvent} → 启动 {@link DiatomKernel}（按运行模式装配对应
 * Bootstrap）；{@code @Observes ShutdownEvent} → 逆序关停。对应 Spring Boot starter 的
 * {@code DiatomGatewayAutoConfiguration} 等自动装配的启动/销毁生命周期。
 */
@ApplicationScoped
public class DiatomQuarkusRuntime {

    private static final Logger LOGGER = Logger.getLogger(DiatomQuarkusRuntime.class);

    private final DiatomComponents components;

    @Inject
    public DiatomQuarkusRuntime(DiatomComponents components) {
        this.components = components;
    }

    void onStart(@Observes StartupEvent event) {
        DiatomKernel kernel = components.kernel();
        LOGGER.infof("Diatom Quarkus runtime started in mode=%s", kernel.getMode());
    }

    void onStop(@Observes ShutdownEvent event) {
        try {
            components.kernel().stop();
        } finally {
            LOGGER.info("Diatom Quarkus runtime stopped");
        }
    }
}
