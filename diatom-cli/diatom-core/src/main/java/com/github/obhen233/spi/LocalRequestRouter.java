package com.github.obhen233.spi;

import com.github.obhen233.core.gateway.registry.WorkerInfo;

import java.util.List;
import java.util.function.Function;

/**
 * SPI interface for local (pre-LLM) request routing.
 * <p>
 * Implementations analyze user requests locally using techniques such as
 * keyword matching or lightweight ML models. When confidence exceeds a
 * configurable threshold, the returned {@link RoutingResult} is used directly,
 * bypassing the LLM call entirely.
 * <p>
 * Registration: add the fully qualified class name to
 * {@code META-INF/services/com.github.obhen233.spi.LocalRequestRouter}.
 */
public interface LocalRequestRouter {

    /**
     * Attempt to route a user request without calling an LLM.
     *
     * @param message          the original user input
     * @param availableWorkers list of currently available workers (may be empty)
     * @return a RoutingResult if the request was classified with sufficient
     *         confidence, or {@code null} to fall through to the LLM
     */
    RoutingResult route(String message, List<WorkerInfo> availableWorkers);

    /**
     * Callback invoked after the LLM processes a request that was not
     * handled by {@link #route(String, List)}.
     * <p>
     * Implementations can use this signal to collect training data
     * (the actual category the LLM assigned) or to refine local
     * classification models.
     * <p>
     * Default implementation does nothing.
     *
     * @param message        the original user input
     * @param actualCategory the category or task type that the LLM
     *                       determined for this message
     */
    default void onClassified(String message, String actualCategory) {
        // reserved for supervised learning feedback
    }

    /**
     * Initialize or update the router with current worker information and LLM access.
     * <p>
     * Called before {@link #route(String, List)} when capabilities may have changed.
     * Implementations can use this to dynamically generate routing categories
     * based on actual worker capabilities via the LLM.
     * <p>
     * Default implementation does nothing.
     *
     * @param workers   current available workers (may be empty at startup)
     * @param llmCaller function that sends a prompt to the configured LLM and
     *                  returns the text response; may be null if LLM is unavailable
     */
    default void initialize(List<WorkerInfo> workers, Function<String, String> llmCaller) {
        // reserved for dynamic category generation
    }
}
