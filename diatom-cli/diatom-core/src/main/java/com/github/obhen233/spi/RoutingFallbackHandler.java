package com.github.obhen233.spi;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.TaskRequirement;

import java.util.List;

/**
 * SPI interface for Gateway routing fallback handling.
 * <p>
 * When the Gateway's routing LLM call fails, the default behavior is to
 * throw an exception and report "Gateway routing model unavailable".
 * Implement this interface to provide custom fallback routing logic
 * (e.g., keyword-based routing, random worker selection, etc.).
 * <p>
 * Registration: add the fully qualified class name to
 * {@code META-INF/services/com.github.obhen233.spi.RoutingFallbackHandler}.
 */
public interface RoutingFallbackHandler {

    /**
     * Handle a routing LLM failure and produce a fallback TaskRequirement.
     *
     * @param message          the original user input
     * @param cause            the exception that caused the LLM failure
     * @param availableWorkers list of currently available workers (may be empty)
     * @return a TaskRequirement for routing, or {@code null} to fall through to
     *         the default "model unavailable" behavior
     */
    TaskRequirement handle(String message, Exception cause, List<WorkerInfo> availableWorkers);
}
