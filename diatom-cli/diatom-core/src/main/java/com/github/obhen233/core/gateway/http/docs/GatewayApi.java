package com.github.obhen233.core.gateway.http.docs;

import java.lang.annotation.*;

/**
 * Annotation for Gateway HTTP handler methods to generate API documentation.
 * <p>
 * Usage: place on the method reference used in {@code server.createContext()}:
 * <pre>{@code
 * @GatewayApi(path = "/gateway/v1/workers", methods = {"GET", "DELETE"},
 *     summary = "Workers endpoint",
 *     description = "GET: list all workers; DELETE: unregister a worker.",
 *     tags = {"Worker Management"})
 * server.createContext("/gateway/v1/workers", this::handleWorkers);
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GatewayApi {

    /** Full API path including path parameters, e.g. "/gateway/v1/workers/{workerId}" */
    String path();

    /** HTTP methods supported by this endpoint */
    String[] methods() default {"GET"};

    /** Short title */
    String summary() default "";

    /** Detailed description (supports HTML) */
    String description() default "";

    /** Tags for grouping in docs */
    String[] tags() default {};

    /** Example request body (JSON) */
    String requestBody() default "";

    /** Example response body (JSON) */
    String responseBody() default "";

    /** Content type of the response */
    String contentType() default "application/json";

    /** Whether authentication is required (default true) */
    boolean authRequired() default true;
}
