package com.github.obhen233.core.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit Breaker pattern implementation for API resilience.
 * Prevents cascading failures by temporarily blocking requests when a service is failing.
 *
 * States:
 * - CLOSED: Normal operation, requests pass through
 * - OPEN: Requests are blocked, waiting for reset timeout
 * - HALF_OPEN: Testing if service has recovered, limited requests allowed
 */

/**
 * Functional interface that allows throwing checked exceptions
 */
@FunctionalInterface
interface ThrowingSupplier<T> {
    T get() throws Exception;
}
public class CircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,   // Normal operation
        OPEN,     // Failing, requests blocked
        HALF_OPEN // Testing recovery
    }

    private final String name;
    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final int halfOpenMaxAttempts;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong openTime = new AtomicLong(0);
    private final AtomicInteger halfOpenAttempts = new AtomicInteger(0);

    // Statistics
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger totalFailures = new AtomicInteger(0);
    private final AtomicInteger totalSuccesses = new AtomicInteger(0);

    /**
     * Create a CircuitBreaker with default settings
     */
    public CircuitBreaker(String name) {
        this(name, 5, 30000, 3);
    }

    /**
     * Create a CircuitBreaker with custom settings
     * @param name Name for logging
     * @param failureThreshold Number of failures before opening
     * @param resetTimeoutMs Time to wait before attempting recovery (ms)
     * @param halfOpenMaxAttempts Max attempts in half-open state before deciding
     */
    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMs, int halfOpenMaxAttempts) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
        this.halfOpenMaxAttempts = halfOpenMaxAttempts;
    }

    /**
     * Execute a supplier with circuit breaker protection
     * @param supplier The operation to execute
     * @return The result of the operation
     * @throws CircuitOpenException if the circuit is open
     * @throws Exception if the operation fails
     */
    public <T> T execute(ThrowingSupplier<T> supplier) throws Exception {
        totalRequests.incrementAndGet();

        // Check state and potentially transition
        State currentState = checkAndTransitionState();

        switch (currentState) {
            case OPEN:
                logger.warn("Circuit [{}] is OPEN, rejecting request", name);
                throw new CircuitOpenException("Circuit breaker [" + name + "] is open. Service temporarily unavailable.");

            case HALF_OPEN:
                // In half-open, we allow limited requests to test recovery
                if (halfOpenAttempts.incrementAndGet() > halfOpenMaxAttempts) {
                    logger.warn("Circuit [{}] is HALF_OPEN but max attempts exceeded", name);
                    throw new CircuitOpenException("Circuit breaker [" + name + "] is testing recovery. Please wait.");
                }
                return executeWithMonitoring(supplier);

            case CLOSED:
            default:
                return executeWithMonitoring(supplier);
        }
    }

    /**
     * Execute operation and monitor for success/failure
     */
    private <T> T executeWithMonitoring(ThrowingSupplier<T> supplier) throws Exception {
        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Check current state and perform state transitions
     */
    private State checkAndTransitionState() {
        State currentState = state.get();

        if (currentState == State.OPEN) {
            // Check if reset timeout has passed
            long now = System.currentTimeMillis();
            if (now - openTime.get() >= resetTimeoutMs) {
                // Try to transition to HALF_OPEN
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenAttempts.set(0);
                    successCount.set(0);
                    logger.info("Circuit [{}] transitioned from OPEN to HALF_OPEN", name);
                }
            }
        }

        return state.get();
    }

    /**
     * Record a successful operation
     */
    private void recordSuccess() {
        totalSuccesses.incrementAndGet();
        failureCount.set(0);

        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            if (successes >= halfOpenMaxAttempts) {
                // Enough successes in half-open, transition to closed
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    failureCount.set(0);
                    logger.info("Circuit [{}] transitioned from HALF_OPEN to CLOSED (recovered)", name);
                }
            }
        }
    }

    /**
     * Record a failed operation
     */
    private void recordFailure() {
        totalFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            // Failure in half-open, go back to open
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openTime.set(System.currentTimeMillis());
                logger.warn("Circuit [{}] transitioned from HALF_OPEN to OPEN (still failing)", name);
            }
        } else if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                // Threshold reached, open the circuit
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openTime.set(System.currentTimeMillis());
                    logger.warn("Circuit [{}] transitioned from CLOSED to OPEN (failure threshold reached: {})",
                            name, failures);
                }
            }
        }
    }

    /**
     * Force open the circuit (manual override)
     */
    public void forceOpen() {
        state.set(State.OPEN);
        openTime.set(System.currentTimeMillis());
        logger.info("Circuit [{}] force opened", name);
    }

    /**
     * Force close the circuit (manual override)
     */
    public void forceClose() {
        state.set(State.CLOSED);
        failureCount.set(0);
        logger.info("Circuit [{}] force closed", name);
    }

    // Getters

    public State getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public long getLastFailureTime() {
        return lastFailureTime.get();
    }

    public int getTotalRequests() {
        return totalRequests.get();
    }

    public int getTotalFailures() {
        return totalFailures.get();
    }

    public int getTotalSuccesses() {
        return totalSuccesses.get();
    }

    public String getName() {
        return name;
    }

    /**
     * Exception thrown when circuit is open
     */
    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }

    /**
     * Manager for multiple circuit breakers (one per endpoint)
     */
    public static class Manager {
        private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
        private final int defaultFailureThreshold;
        private final long defaultResetTimeoutMs;
        private final int defaultHalfOpenMaxAttempts;

        public Manager() {
            this(5, 30000, 3);
        }

        public Manager(int defaultFailureThreshold, long defaultResetTimeoutMs, int defaultHalfOpenMaxAttempts) {
            this.defaultFailureThreshold = defaultFailureThreshold;
            this.defaultResetTimeoutMs = defaultResetTimeoutMs;
            this.defaultHalfOpenMaxAttempts = defaultHalfOpenMaxAttempts;
        }

        /**
         * Get or create a circuit breaker for an endpoint
         */
        public CircuitBreaker getForEndpoint(String endpoint) {
            return circuitBreakers.computeIfAbsent(endpoint,
                    k -> new CircuitBreaker(k, defaultFailureThreshold, defaultResetTimeoutMs, defaultHalfOpenMaxAttempts));
        }

        /**
         * Get all circuit breakers
         */
        public ConcurrentHashMap<String, CircuitBreaker> getAll() {
            return circuitBreakers;
        }

        /**
         * Reset all circuit breakers
         */
        public void resetAll() {
            circuitBreakers.values().forEach(CircuitBreaker::forceClose);
        }
    }
}
