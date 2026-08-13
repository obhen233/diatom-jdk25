package com.github.obhen233.starter;

import com.github.obhen233.spi.AppLifecycleHook;
import com.github.obhen233.spi.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEvent;

/**
 * Spring ApplicationEvent publisher for Diatom lifecycle events.
 *
 * Integrates diatom-core's SPI lifecycle hooks with Spring's event system,
 * allowing other Spring beans to listen for Diatom events.
 *
 * Events published:
 * - DiatomInitializedEvent  → After all diatom beans are initialized
 * - DiatomShutdownEvent     → Before diatom shuts down
 *
 * Usage in other beans:
 * <pre>
 * &#64;EventListener
 * public void onDiatomReady(DiatomEventPublisher.DiatomInitializedEvent event) {
 *     // Diatom is ready to use
 * }
 * </pre>
 *
 * Requires spring-boot-starter on the classpath.
 */
@ConditionalOnClass(name = "org.springframework.context.ApplicationEventPublisher")
public class DiatomEventPublisher implements AppLifecycleHook, ConfigProvider {

    private static final Logger logger = LoggerFactory.getLogger(DiatomEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public DiatomEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void onAfterInit() {
        logger.info("Publishing DiatomInitializedEvent");
        eventPublisher.publishEvent(new DiatomInitializedEvent(this));
    }

    @Override
    public void onShutdown() {
        logger.info("Publishing DiatomShutdownEvent");
        eventPublisher.publishEvent(new DiatomShutdownEvent(this));
    }

    // ========== Event Classes ==========

    /**
     * Event fired when Diatom has been fully initialized.
     * All beans are available at this point.
     */
    public static class DiatomInitializedEvent extends ApplicationEvent {
        public DiatomInitializedEvent(Object source) {
            super(source);
        }
    }

    /**
     * Event fired when Diatom is shutting down.
     */
    public static class DiatomShutdownEvent extends ApplicationEvent {
        public DiatomShutdownEvent(Object source) {
            super(source);
        }
    }
}
