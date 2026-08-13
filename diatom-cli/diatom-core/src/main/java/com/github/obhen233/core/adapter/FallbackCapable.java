package com.github.obhen233.core.adapter;

import java.io.IOException;

/**
 * Optional capability for {@link ModelAdapter}s that can degrade to an
 * alternative wire format at runtime.
 *
 * <p>When the configured API rejects a request (e.g. the Responses API
 * returns {@code model_not_supported} for a model that only supports chat
 * completions), the adapter activates a fallback. The agent loop detects this
 * interface via {@code instanceof} and re-sends the same request to the
 * fallback endpoint. Other adapters are completely unaffected.</p>
 */
public interface FallbackCapable {

    /**
     * Attempt to activate the fallback based on the error from the initial request.
     *
     * @param e the {@link IOException} thrown by the HTTP client for a non-2xx response
     * @return {@code true} if the fallback was activated and the request should be
     *         re-sent to the fallback endpoint, {@code false} to keep the original behavior
     */
    boolean tryActivateFallback(IOException e);

    /**
     * Resolve the effective endpoint for a request.
     *
     * @param requestedEndpoint the originally configured API endpoint
     * @return the fallback endpoint when the fallback is active, otherwise the requested endpoint
     */
    String effectiveEndpoint(String requestedEndpoint);
}
