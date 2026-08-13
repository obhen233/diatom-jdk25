package com.github.obhen233.core.agent.context;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests for CommandCircuitBreaker
 */
public class CommandCircuitBreakerTest {
    
    @Test
    public void testInitialState() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        assertEquals("Initial state should be CLOSED", 
            CommandCircuitBreaker.CircuitState.CLOSED, breaker.getState());
        assertTrue("Should allow execution initially", breaker.shouldAllowExecution());
        assertTrue("Shell environment should be available initially", 
            breaker.isShellEnvironmentAvailable());
    }
    
    @Test
    public void testSingleErrorDoesNotTrip() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Track a single error
        CommandCircuitBreaker.ErrorInfo error = breaker.trackError("test_cmd", 
            "Error: Failed to start process: CreateProcess error=5");
        
        assertNotNull(error);
        assertEquals("Error count should be 1", 1, error.errorCount);
        assertFalse("Circuit should not be tripped after 1 error", error.circuitTripped);
        assertTrue("Should still allow execution", breaker.shouldAllowExecution());
    }
    
    @Test
    public void testCircuitTripsAfterThreshold() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        String errorMessage = "Error: Failed to start process: CreateProcess error=5, 拒绝访问";
        
        // Track errors up to threshold
        for (int i = 0; i < 3; i++) {
            CommandCircuitBreaker.ErrorInfo error = breaker.trackError("test_cmd", errorMessage);
            
            if (i < 2) {
                assertFalse("Circuit should not trip before threshold (iteration " + i + ")", 
                    error.circuitTripped);
            } else {
                assertTrue("Circuit should trip at threshold", error.circuitTripped);
            }
        }
        
        assertEquals("State should be OPEN", 
            CommandCircuitBreaker.CircuitState.OPEN, breaker.getState());
        assertFalse("Should block execution when circuit is open", 
            breaker.shouldAllowExecution());
    }
    
    @Test
    public void testDifferentErrorsDoNotAccumulate() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Track different errors
        breaker.trackError("cmd1", "Error: CreateProcess error=5");
        breaker.trackError("cmd2", "Error: Command timeout");
        breaker.trackError("cmd3", "Error: File not found");
        
        // Circuit should not trip for different errors
        assertEquals("State should still be CLOSED for different errors", 
            CommandCircuitBreaker.CircuitState.CLOSED, breaker.getState());
        assertTrue("Should still allow execution", breaker.shouldAllowExecution());
    }
    
    @Test
    public void testSuccessResetsCounters() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Track some errors
        breaker.trackError("cmd", "Error: CreateProcess error=5");
        breaker.trackError("cmd", "Error: CreateProcess error=5");
        
        // Record success
        breaker.recordSuccess();
        
        // Error counters should be reset
        assertTrue("Error statistics should be empty after success", 
            breaker.getErrorStatistics().isEmpty());
    }
    
    @Test
    public void testCircuitOpenMessage() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Trip the circuit
        for (int i = 0; i < 3; i++) {
            breaker.trackError("cmd", "Error: CreateProcess error=5");
        }
        
        String message = breaker.getCircuitOpenMessage();
        
        assertNotNull("Should have circuit open message", message);
        assertTrue("Message should contain error type", 
            message.contains("ACCESS_DENIED"));
        assertTrue("Message should contain recovery options", 
            message.contains("选项") || message.contains("Option"));
        assertTrue("Message should contain alternative tools", 
            message.contains("search_files") || message.contains("read_file"));
    }
    
    @Test
    public void testManualReset() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Trip the circuit
        for (int i = 0; i < 3; i++) {
            breaker.trackError("cmd", "Error: CreateProcess error=5");
        }
        
        assertEquals("State should be OPEN", 
            CommandCircuitBreaker.CircuitState.OPEN, breaker.getState());
        
        // Manual reset
        breaker.reset();
        
        assertEquals("State should be CLOSED after reset", 
            CommandCircuitBreaker.CircuitState.CLOSED, breaker.getState());
        assertTrue("Should allow execution after reset", breaker.shouldAllowExecution());
    }
    
    @Test
    public void testErrorTypeClassification() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Test ACCESS_DENIED
        CommandCircuitBreaker.ErrorInfo error1 = breaker.trackError("cmd", 
            "Error: CreateProcess error=5, 拒绝访问");
        assertEquals("Should classify as ACCESS_DENIED", 
            CommandCircuitBreaker.ErrorType.ACCESS_DENIED, error1.type);
        
        breaker.reset();
        
        // Test SHELL_MISCONFIGURATION
        CommandCircuitBreaker.ErrorInfo error2 = breaker.trackError("cmd", 
            "Error: C:\\Program Files\\Git 是目录而非可执行文件");
        assertEquals("Should classify as SHELL_MISCONFIGURATION", 
            CommandCircuitBreaker.ErrorType.SHELL_MISCONFIGURATION, error2.type);
        
        breaker.reset();
        
        // Test PATH_NOT_FOUND
        CommandCircuitBreaker.ErrorInfo error3 = breaker.trackError("cmd", 
            "Error: The system cannot find the file specified");
        assertEquals("Should classify as PATH_NOT_FOUND", 
            CommandCircuitBreaker.ErrorType.PATH_NOT_FOUND, error3.type);
    }
    
    @Test
    public void testRecoveryOptions() {
        CommandCircuitBreaker breaker = new CommandCircuitBreaker();
        
        // Track errors to trip circuit
        for (int i = 0; i < 3; i++) {
            breaker.trackError("cmd", "Error: CreateProcess error=5");
        }
        
        CommandCircuitBreaker.ErrorInfo error = breaker.trackError("cmd", 
            "Error: CreateProcess error=5");
        
        assertNotNull("Should have recovery options", error.recoveryOptions);
        assertTrue("Should have at least 2 recovery options", 
            error.recoveryOptions.size() >= 2);
        
        // Check first option (skip command, use alternatives)
        CommandCircuitBreaker.RecoveryOption optionA = error.recoveryOptions.get(0);
        assertNotNull(optionA.alternativeTools);
        assertTrue("Option A should suggest alternative tools", 
            optionA.alternativeTools.contains("read_file") || 
            optionA.alternativeTools.contains("search_files"));
        
        // Check second option (report to user)
        CommandCircuitBreaker.RecoveryOption optionB = error.recoveryOptions.get(1);
        assertTrue("Option B should mention user action", 
            optionB.description.contains("用户") || optionB.description.contains("user"));
    }
}
