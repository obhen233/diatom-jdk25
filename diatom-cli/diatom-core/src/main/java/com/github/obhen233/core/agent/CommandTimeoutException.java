package com.github.obhen233.core.agent;

/**
 * Exception thrown when a command execution times out and needs user confirmation to continue.
 * The process is still running and waiting for user decision.
 */
public class CommandTimeoutException extends RuntimeException {
    private final String command;
    private final int elapsedSeconds;
    private final int timeoutSeconds;
    private final Process process;
    private final Thread outputThread;
    private volatile boolean cancelled = false;
    private volatile boolean continued = false;

    public CommandTimeoutException(String command, int elapsedSeconds, int timeoutSeconds, 
                                   Process process, Thread outputThread) {
        super("Command timeout after " + elapsedSeconds + " seconds: " + command);
        this.command = command;
        this.elapsedSeconds = elapsedSeconds;
        this.timeoutSeconds = timeoutSeconds;
        this.process = process;
        this.outputThread = outputThread;
    }

    public String getCommand() { return command; }
    public int getElapsedSeconds() { return elapsedSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public Process getProcess() { return process; }
    
    /**
     * Cancel the command - kill the process
     */
    public void cancel() {
        this.cancelled = true;
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        if (outputThread != null) {
            outputThread.interrupt();
        }
    }
    
    /**
     * Continue waiting for the command
     */
    public void doContinue() {
        this.continued = true;
    }
    
    public boolean isCancelled() { return cancelled; }
    public boolean isContinued() { return continued; }
}
