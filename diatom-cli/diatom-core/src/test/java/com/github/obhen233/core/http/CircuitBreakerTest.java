package com.github.obhen233.core.http;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for CircuitBreaker
 */
public class CircuitBreakerTest {

    /**
     * Test initial state is CLOSED
     */
    @Test
    public void testInitialStateIsClosed() {
        CircuitBreaker breaker = new CircuitBreaker("test");
        assertEquals("Initial state should be CLOSED",
            CircuitBreaker.State.CLOSED, breaker.getState());
    }

    /**
     * Test successful execution keeps circuit CLOSED
     */
    @Test
    public void testSuccessKeepsCircuitClosed() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, 30000, 3);

        String result = breaker.execute(() -> "success");
        assertEquals("success", result);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
    }

    /**
     * Test failures below threshold keep circuit CLOSED
     */
    @Test
    public void testFailuresBelowThresholdKeepCircuitClosed() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 5, 30000, 3);

        // Record 4 failures (threshold is 5)
        for (int i = 0; i < 4; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("error");
                });
            } catch (Exception expected) {
                // Expected
            }
        }

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(4, breaker.getFailureCount());
    }

    /**
     * Test reaching failure threshold opens circuit
     */
    @Test
    public void testFailureThresholdOpensCircuit() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 3, 30000, 3);

        // Record 3 failures (threshold is 3)
        for (int i = 0; i < 3; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("error");
                });
            } catch (Exception expected) {
                // Expected
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    /**
     * Test circuit open blocks requests and throws exception
     */
    @Test
    public void testOpenCircuitBlocksRequests() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 2, 30000, 3);

        // Trip the circuit
        for (int i = 0; i < 2; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("error");
                });
            } catch (Exception expected) {
                // Expected
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Next request should throw CircuitOpenException
        try {
            breaker.execute(() -> "should not execute");
            fail("Should have thrown CircuitOpenException");
        } catch (CircuitBreaker.CircuitOpenException e) {
            assertTrue(e.getMessage().contains("open"));
        }
    }

    /**
     * Test success after failures resets failure count
     */
    @Test
    public void testSuccessResetsFailureCount() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 5, 30000, 3);

        // Record some failures (below threshold)
        for (int i = 0; i < 2; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("error");
                });
            } catch (Exception expected) {
                // Expected
            }
        }

        assertEquals(2, breaker.getFailureCount());

        // Record success
        breaker.execute(() -> "success");

        // Failure count should reset
        assertEquals(0, breaker.getFailureCount());
    }

    /**
     * Test forceOpen manually opens circuit
     */
    @Test
    public void testForceOpen() {
        CircuitBreaker breaker = new CircuitBreaker("test");

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());

        breaker.forceOpen();

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());
    }

    /**
     * Test forceClose manually closes circuit
     */
    @Test
    public void testForceClose() {
        CircuitBreaker breaker = new CircuitBreaker("test", 2, 30000, 3);

        // Trip the circuit
        for (int i = 0; i < 2; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("error");
                });
            } catch (Exception expected) {
                // Expected
            }
        }

        assertEquals(CircuitBreaker.State.OPEN, breaker.getState());

        // Force close
        breaker.forceClose();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.getState());
        assertEquals(0, breaker.getFailureCount());
    }

    /**
     * Test statistics tracking
     */
    @Test
    public void testStatisticsTracking() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 5, 30000, 3);

        assertEquals(0, breaker.getTotalRequests());
        assertEquals(0, breaker.getTotalSuccesses());
        assertEquals(0, breaker.getTotalFailures());

        // Successful requests
        breaker.execute(() -> "success");
        breaker.execute(() -> "success");

        assertEquals(2, breaker.getTotalRequests());
        assertEquals(2, breaker.getTotalSuccesses());

        // Failed request
        try {
            breaker.execute(() -> {
                throw new RuntimeException("error");
            });
        } catch (Exception expected) {
            // Expected
        }

        assertEquals(3, breaker.getTotalRequests());
        assertEquals(1, breaker.getTotalFailures());
    }

    /**
     * Test CircuitBreaker.Manager creates circuit breakers per endpoint
     */
    @Test
    public void testManagerCreatesPerEndpoint() {
        CircuitBreaker.Manager manager = new CircuitBreaker.Manager();

        CircuitBreaker cb1 = manager.getForEndpoint("endpoint1");
        CircuitBreaker cb2 = manager.getForEndpoint("endpoint2");

        assertNotSame(cb1, cb2);
        assertEquals("endpoint1", cb1.getName());
        assertEquals("endpoint2", cb2.getName());
    }

    /**
     * Test CircuitBreaker.Manager returns same instance for same endpoint
     */
    @Test
    public void testManagerReturnsSameInstance() {
        CircuitBreaker.Manager manager = new CircuitBreaker.Manager();

        CircuitBreaker cb1 = manager.getForEndpoint("same-endpoint");
        CircuitBreaker cb2 = manager.getForEndpoint("same-endpoint");

        assertSame(cb1, cb2);
    }

    /**
     * Test CircuitBreaker.Manager resetAll closes all circuits
     */
    @Test
    public void testManagerResetAll() {
        CircuitBreaker.Manager manager = new CircuitBreaker.Manager();

        CircuitBreaker cb1 = manager.getForEndpoint("endpoint1");
        CircuitBreaker cb2 = manager.getForEndpoint("endpoint2");

        // Force open both
        cb1.forceOpen();
        cb2.forceOpen();

        assertEquals(CircuitBreaker.State.OPEN, cb1.getState());
        assertEquals(CircuitBreaker.State.OPEN, cb2.getState());

        // Reset all
        manager.resetAll();

        assertEquals(CircuitBreaker.State.CLOSED, cb1.getState());
        assertEquals(CircuitBreaker.State.CLOSED, cb2.getState());
    }

    /**
     * Test getFailureCount returns correct value
     */
    @Test
    public void testGetFailureCount() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 5, 30000, 3);

        assertEquals(0, breaker.getFailureCount());

        try {
            breaker.execute(() -> {
                throw new RuntimeException("error");
            });
        } catch (Exception expected) {
            // Expected
        }

        assertEquals(1, breaker.getFailureCount());
    }

    /**
     * Test lastFailureTime is updated on failure
     */
    @Test
    public void testLastFailureTimeUpdated() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker("test", 5, 30000, 3);

        long beforeFailure = System.currentTimeMillis();

        try {
            breaker.execute(() -> {
                throw new RuntimeException("error");
            });
        } catch (Exception expected) {
            // Expected
        }

        long afterFailure = System.currentTimeMillis();
        assertTrue(breaker.getLastFailureTime() >= beforeFailure);
        assertTrue(breaker.getLastFailureTime() <= afterFailure);
    }

    /**
     * Test that CircuitOpenException is now a RuntimeException
     */
    @Test
    public void testCircuitOpenExceptionIsRuntime() {
        CircuitBreaker.CircuitOpenException ex =
            new CircuitBreaker.CircuitOpenException("test");

        // Should be able to throw without declaration
        assertTrue(ex instanceof RuntimeException);
    }
}
