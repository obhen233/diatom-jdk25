package com.github.obhen233.starter;

import com.github.obhen233.core.agent.ReActAgent;
import com.github.obhen233.util.I18n;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * Spring Boot Actuator health indicator for Diatom.
 *
 * Exposes /actuator/health/diatom endpoint when
 * spring-boot-starter-actuator is on the classpath.
 *
 * Shows:
 * - Agent status (ready/unavailable)
 * - Current language
 * - Model type
 */
@ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
public class DiatomHealthIndicator extends AbstractHealthIndicator {

    private final ReActAgent agent;

    public DiatomHealthIndicator(ReActAgent agent) {
        this.agent = agent;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        builder.up()
                .withDetail("agent", agent != null ? "ready" : "unavailable")
                .withDetail("language", I18n.getLanguage())
                .withDetail("model", "configured");
    }
}
