package com.github.obhen233.compiler.debug;

import com.github.obhen233.compiler.debug.model.*;
import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Service
public class DebugService {

    private static final Logger log = LoggerFactory.getLogger(DebugService.class);

    // Debug session state
    private volatile DebugSessionState state = DebugSessionState.DISCONNECTED;
    private VirtualMachine vm;
    private Process targetProcess;
    private Thread eventThread;
    private final Map<String, DebugBreakpoint> breakpoints = new ConcurrentHashMap<>();
    private final Map<Long, ThreadReference> suspendedThreads = new ConcurrentHashMap<>();
    private volatile SseEventCallback eventCallback;
    private volatile boolean running = false;
    private int jdwpPort;
    private final Object stepLock = new Object();          // Dedicated lock for step/resume
    private ClassPrepareRequest classPrepareRequest;        // Stored for dynamic filter updates
    private volatile EventSet pendingEventSet;               // EventSet not yet resumed (breakpoint/step)
    private volatile boolean stepPending = false;            // Guards against rapid step clicks
    private final ExecutorService sseExecutor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("sse-event-sender").factory());

    // Callback interface for SSE events
    public interface SseEventCallback {
        void onEvent(String event, String data);
    }

    public DebugSessionState getState() { return state; }
    public boolean isRunning() { return running; }

    /**
     * Replace the SSE event callback (for reconnection).
     */
    public void setEventCallback(SseEventCallback callback) {
        this.eventCallback = callback;
    }

    /**
     * Get all current breakpoints (for reconnection).
     */
    public Collection<DebugBreakpoint> getBreakpoints() {
        return new ArrayList<>(breakpoints.values());
    }

    /**
     * Start a debug session: launch target JVM with JDWP, attach JDI, start event loop
     */
    public synchronized void start(File projectDir, String mainClass, String classpath,
                                    String jvmArgs, String programArgs,
                                    String javaHome, boolean suspend, SseEventCallback callback) throws Exception {
        start(projectDir, mainClass, classpath, jvmArgs, programArgs, javaHome, suspend ? "y" : "n", callback);
    }

    /**
     * Start a debug session: launch target JVM with JDWP, attach JDI, start event loop
     */
    public synchronized void start(File projectDir, String mainClass, String classpath,
                                    String jvmArgs, String programArgs,
                                    String javaHome, String suspendMode, SseEventCallback callback) throws Exception {
        if (running) {
            throw new IllegalStateException("Debug session already running");
        }

        this.eventCallback = callback;
        this.suspendedThreads.clear();
        state = DebugSessionState.INITIALIZING;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String javaCmd = javaHome + File.separator + "bin" + File.separator + (isWin ? "java.exe" : "java");
        if (!new File(javaCmd).exists()) javaCmd = isWin ? "java.exe" : "java";

        // Build command with JDWP agent
        List<String> command = new ArrayList<>();
        command.add(javaCmd);
        // Add user JVM args
        if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
            for (String arg : jvmArgs.trim().split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }
        // JDWP agent - use dynamic port on localhost
        // Java 8: address=PORT (localhost only, no host prefix)
        // Java 9+: use *:PORT for all interfaces
        String suspendParam = "y".equalsIgnoreCase(suspendMode) ? "suspend=y" : "suspend=n";
        command.add("-agentlib:jdwp=transport=dt_socket,server=y," + suspendParam + ",address=0");
        command.add("-cp");
        command.add(classpath);
        command.add(mainClass);
        if (programArgs != null && !programArgs.trim().isEmpty()) {
            for (String arg : programArgs.trim().split("\\s+")) {
                if (!arg.isEmpty()) command.add(arg);
            }
        }

        log.info("Starting debug: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        pb.environment().put("JAVA_HOME", javaHome);
        pb.redirectErrorStream(true); // merge stdout+stderr to avoid data loss between separate pipes

        targetProcess = pb.start();
        running = true;

        attachToTargetJvm(targetProcess);
    }

    /**
     * Start debug session via Maven exec:java with JDWP agent.
     * Launches {@code mvn exec:java -Dexec.mainClass=<mainClass>} with JDWP in MAVEN_OPTS.
     */
    public synchronized void startWithMaven(File projectDir, String mainClass,
                                             String jvmArgs, String programArgs,
                                             String javaHome, boolean suspend, SseEventCallback callback) throws Exception {
        if (running) {
            throw new IllegalStateException("Debug session already running");
        }

        this.eventCallback = callback;
        this.suspendedThreads.clear();
        state = DebugSessionState.INITIALIZING;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String mvnCmd = isWin ? "mvn.cmd" : "mvn";
        String suspendParam = suspend ? "suspend=y" : "suspend=n";

        // Build MAVEN_OPTS with JDWP agent, user JVM args, and encoding
        StringBuilder mavenOpts = new StringBuilder();
        if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
            mavenOpts.append(jvmArgs.trim()).append(" ");
        }
        mavenOpts.append("-agentlib:jdwp=transport=dt_socket,server=y,").append(suspendParam).append(",address=0");
        mavenOpts.append(" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");

        List<String> command = new ArrayList<>();
        command.add(mvnCmd);
        command.add("exec:java");
        command.add("-Dexec.mainClass=" + mainClass);
        command.add("-Dexec.classpathScope=runtime");
        command.add("-B");
        if (programArgs != null && !programArgs.trim().isEmpty()) {
            command.add("-Dexec.args=" + programArgs.trim());
        }

        log.info("Starting Maven debug: {}", String.join(" ", command));
        log.info("MAVEN_OPTS={}", mavenOpts);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.environment().put("MAVEN_OPTS", mavenOpts.toString());
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        pb.redirectErrorStream(true);

        targetProcess = pb.start();
        running = true;

        attachToTargetJvm(targetProcess);
    }

    /**
     * Start debug session via Gradle run with JDWP agent.
     * Launches {@code gradle run} with JDWP in JAVA_TOOL_OPTIONS.
     */
    public synchronized void startWithGradle(File projectDir, String mainClass,
                                              String jvmArgs, String programArgs,
                                              String javaHome, boolean suspend, SseEventCallback callback) throws Exception {
        if (running) {
            throw new IllegalStateException("Debug session already running");
        }

        this.eventCallback = callback;
        this.suspendedThreads.clear();
        state = DebugSessionState.INITIALIZING;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String gradleCmd = isWin ? "gradle.bat" : "gradle";
        String suspendParam = suspend ? "suspend=y" : "suspend=n";

        // Build JAVA_TOOL_OPTIONS with JDWP agent and user JVM args
        StringBuilder javaToolOpts = new StringBuilder();
        javaToolOpts.append("-agentlib:jdwp=transport=dt_socket,server=y,").append(suspendParam).append(",address=0");
        javaToolOpts.append(" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
            javaToolOpts.append(" ").append(jvmArgs.trim());
        }

        List<String> command = new ArrayList<>();
        command.add(gradleCmd);
        command.add("run");
        command.add("--no-daemon");
        if (programArgs != null && !programArgs.trim().isEmpty()) {
            command.add("--args=" + programArgs.trim());
        }

        log.info("Starting Gradle debug: {}", String.join(" ", command));
        log.info("JAVA_TOOL_OPTIONS={}", javaToolOpts);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.environment().put("JAVA_TOOL_OPTIONS", javaToolOpts.toString());
        pb.environment().put("JAVA_HOME", javaHome);
        pb.redirectErrorStream(true);

        targetProcess = pb.start();
        running = true;

        attachToTargetJvm(targetProcess);
    }

    /**
     * Start debug session via Maven spring-boot:run with JDWP agent.
     * Launches {@code mvn spring-boot:run} with JDWP in spring-boot.run.jvmArguments.
     */
    public synchronized void startWithMavenSpringBoot(File projectDir, String mainClass,
                                                       String jvmArgs, String programArgs,
                                                       String javaHome, boolean suspend, SseEventCallback callback) throws Exception {
        if (running) {
            throw new IllegalStateException("Debug session already running");
        }

        this.eventCallback = callback;
        this.suspendedThreads.clear();
        state = DebugSessionState.INITIALIZING;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String mvnCmd = isWin ? "mvn.cmd" : "mvn";
        String suspendParam = suspend ? "suspend=y" : "suspend=n";

        // Build JVM arguments string for spring-boot.run.jvmArguments
        StringBuilder springJvmArgs = new StringBuilder();
        if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
            springJvmArgs.append(jvmArgs.trim()).append(" ");
        }
        springJvmArgs.append("-agentlib:jdwp=transport=dt_socket,server=y,").append(suspendParam).append(",address=0");
        springJvmArgs.append(" -Dfile.encoding=UTF-8");

        List<String> command = new ArrayList<>();
        command.add(mvnCmd);
        command.add("spring-boot:run");
        command.add("-Dspring-boot.run.jvmArguments=" + springJvmArgs.toString());
        command.add("-B");
        if (programArgs != null && !programArgs.trim().isEmpty()) {
            command.add("-Dspring-boot.run.arguments=" + programArgs.trim());
        }

        log.info("Starting Maven Spring Boot debug: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        pb.redirectErrorStream(true);

        targetProcess = pb.start();
        running = true;

        attachToTargetJvm(targetProcess);
    }

    /**
     * Start debug session via Gradle bootRun with JDWP agent.
     * Launches {@code gradle bootRun} with JDWP in spring-boot.run.jvmArguments.
     */
    public synchronized void startWithGradleBoot(File projectDir, String mainClass,
                                                  String jvmArgs, String programArgs,
                                                  String javaHome, boolean suspend, SseEventCallback callback) throws Exception {
        if (running) {
            throw new IllegalStateException("Debug session already running");
        }

        this.eventCallback = callback;
        this.suspendedThreads.clear();
        state = DebugSessionState.INITIALIZING;

        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String gradleCmd = isWin ? "gradle.bat" : "gradle";
        String suspendParam = suspend ? "suspend=y" : "suspend=n";

        // Build JVM arguments string for spring-boot.run.jvmArguments
        StringBuilder springJvmArgs = new StringBuilder();
        if (jvmArgs != null && !jvmArgs.trim().isEmpty()) {
            springJvmArgs.append(jvmArgs.trim()).append(" ");
        }
        springJvmArgs.append("-agentlib:jdwp=transport=dt_socket,server=y,").append(suspendParam).append(",address=0");
        springJvmArgs.append(" -Dfile.encoding=UTF-8");

        List<String> command = new ArrayList<>();
        command.add(gradleCmd);
        command.add("bootRun");
        command.add("--no-daemon");
        command.add("-Dspring-boot.run.jvmArguments=" + springJvmArgs.toString());
        if (programArgs != null && !programArgs.trim().isEmpty()) {
            command.add("-Dspring-boot.run.arguments=" + programArgs.trim());
        }

        log.info("Starting Gradle Spring Boot debug: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectDir);
        pb.environment().put("JAVA_HOME", javaHome);
        pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        pb.redirectErrorStream(true);

        targetProcess = pb.start();
        running = true;

        attachToTargetJvm(targetProcess);
    }

    /**
     * Stop the debug session
     */
    public synchronized void stop() {
        running = false;
        state = DebugSessionState.DISCONNECTED;
        suspendedThreads.clear();

        // Resume VM if suspended, so EventQueue can be unblocked
        // Then dispose to clean up
        if (vm != null) {
            try {
                // Try to resume any pending EventSet to unblock the event queue
                EventSet pending = pendingEventSet;
                if (pending != null) {
                    try {
                        pending.resume();
                        pendingEventSet = null;
                    } catch (Exception ignored) {}
                }
                vm.resume();  // Resume VM to unblock event queue
            } catch (Exception ignored) {}
            try { vm.dispose(); } catch (Exception ignored) {}
            vm = null;
        }
        classPrepareRequest = null;
        pendingEventSet = null;
        // Clear stale request IDs from disposed VM
        for (DebugBreakpoint bp : breakpoints.values()) {
            bp.setRequestId(null);
        }
        if (eventThread != null) {
            // Interrupt won't unblock queue.remove(), but helps with other blocking calls
            eventThread.interrupt();
            try {
                // Wait a short time for event thread to exit on its own
                eventThread.join(1000);
            } catch (InterruptedException ignored) {}
            if (eventThread.isAlive()) {
                log.warn("Event thread did not exit after 1s, leaving daemon thread to die");
            }
            eventThread = null;
        }
        if (targetProcess != null) {
            try { targetProcess.destroyForcibly(); } catch (Exception ignored) {}
            targetProcess = null;
        }
        // Send sessionEnd first, then clean up callback
        try {
            sendEvent("sessionEnd", "{\"exitCode\":0}");
        } catch (Exception ignored) {}
        eventCallback = null;
    }

    // ==================== Breakpoints ====================

    public void setBreakpoint(DebugBreakpoint bp) {
        // Deduplicate: remove any existing breakpoint at the same class + line
        String key = bp.getClassName() + ":" + bp.getLineNumber();
        for (Map.Entry<String, DebugBreakpoint> entry : breakpoints.entrySet()) {
            DebugBreakpoint existing = entry.getValue();
            String existingKey = existing.getClassName() + ":" + existing.getLineNumber();
            if (key.equals(existingKey)) {
                removeBreakpoint(entry.getKey());
                break;
            }
        }
        bp.setId(UUID.randomUUID().toString());
        breakpoints.put(bp.getId(), bp);
        if (vm != null) {
            setBreakpointInternal(bp);
            // If class not yet loaded, add filter so ClassPrepareEvent fires when it loads
            if (classPrepareRequest != null && bp.getRequestId() == null) {
                try {
                    classPrepareRequest.addClassFilter(bp.getClassName() + "*");
                } catch (Exception ignored) {}
            }
        }
    }

    public void removeBreakpoint(String id) {
        DebugBreakpoint bp = breakpoints.remove(id);
        if (bp != null && bp.getRequestId() != null && vm != null) {
            try {
                ((EventRequest) bp.getRequestId()).disable();
                vm.eventRequestManager().deleteEventRequest((EventRequest) bp.getRequestId());
            } catch (Exception ignored) {}
        }
        // Rebuild ClassPrepareRequest filters for remaining pending breakpoints
        // (JDI doesn't support removing individual class filters)
        if (vm != null && classPrepareRequest != null) {
            rebuildClassPrepareFilters();
        }
    }

    private void setBreakpointInternal(DebugBreakpoint bp) {
        if (vm == null) return;
        try {
            List<ReferenceType> classes = vm.classesByName(bp.getClassName());
            if (classes.isEmpty()) {
                // Class not loaded yet, will be set via ClassPrepareRequest
                return;
            }
            ReferenceType refType = classes.get(0);
            List<Location> locations = refType.locationsOfLine(bp.getLineNumber());
            if (!locations.isEmpty()) {
                Location loc = locations.get(0);
                BreakpointRequest bpReq = vm.eventRequestManager().createBreakpointRequest(loc);
                bpReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                bpReq.enable();
                bp.setRequestId(bpReq);
                sendEvent("breakpointSet", "{\"className\":\"" + bp.getClassName() + "\",\"lineNumber\":" + bp.getLineNumber() + ",\"filePath\":\"" + nvl(bp.getFilePath()) + "\",\"fileName\":\"" + nvl(bp.getFileName()) + "\"}");
            } else {
                // Line has no executable code (blank, comment, or non-bytecode line)
                log.warn("Breakpoint not set: line {} in {} has no executable code", bp.getLineNumber(), bp.getClassName());
                sendEvent("breakpointFailed", "{\"className\":\"" + bp.getClassName() + "\",\"lineNumber\":" + bp.getLineNumber() + ",\"filePath\":\"" + nvl(bp.getFilePath()) + "\",\"fileName\":\"" + nvl(bp.getFileName()) + "\",\"reason\":\"Line has no executable code\"}");
                breakpoints.remove(bp.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to set breakpoint: {}", e.getMessage());
            sendEvent("breakpointFailed", "{\"className\":\"" + bp.getClassName() + "\",\"lineNumber\":" + bp.getLineNumber() + ",\"filePath\":\"" + nvl(bp.getFilePath()) + "\",\"fileName\":\"" + nvl(bp.getFileName()) + "\",\"reason\":\"" + e.getMessage() + "\"}");
            breakpoints.remove(bp.getId());
        }
    }

    // ==================== Stepping ====================

    public void stepOver(long threadId) {
        step(threadId, StepRequest.STEP_LINE, StepRequest.STEP_OVER);
    }

    public void stepInto(long threadId) {
        step(threadId, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
    }

    public void stepOut(long threadId) {
        step(threadId, StepRequest.STEP_LINE, StepRequest.STEP_OUT);
    }

    private void step(long threadId, int depth, int size) {
        if (vm == null) { log.warn("Step: vm is null"); return; }
        synchronized (stepLock) {
            if (stepPending) {
                log.warn("Step: rejected - stepPending still true");
                return;
            }
            if (state != DebugSessionState.SUSPENDED) {
                log.warn("Step: rejected - state is {}, expected SUSPENDED", state);
                return;
            }
            ThreadReference thread = suspendedThreads.get(threadId);
            if (thread == null) {
                log.warn("Step: thread not found in suspendedThreads (threadId={}), map={}",
                         threadId, suspendedThreads.keySet());
                return;
            }
            try {
                // Clear existing step requests for this thread
                List<StepRequest> existing = vm.eventRequestManager().stepRequests().stream()
                    .filter(sr -> sr.thread().equals(thread))
                    .collect(Collectors.toList());
                log.info("Step: deleting {} existing StepRequests, creating new one (depth={}, size={})", existing.size(), depth, size);
                vm.eventRequestManager().deleteEventRequests(existing);
                // Create the step request BEFORE resuming the pending event set,
                // so the step is intercepted when the VM resumes.
                StepRequest stepReq = vm.eventRequestManager().createStepRequest(thread, depth, size);
                stepReq.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                stepReq.enable();
                stepPending = true;
                state = DebugSessionState.RUNNING;
                // Resume the pending event set to clear its JDI-level suspension.
                // This is required because JDI EventSet suspension is independent
                // of vm.resume() — only eventSet.resume() can release it.
                EventSet pending = pendingEventSet;
                pendingEventSet = null;
                if (pending != null) {
                    log.debug("Step: calling pending.resume()");
                    try { pending.resume(); } catch (Exception ignored) {}
                } else {
                    log.warn("Step: pendingEventSet is null — VM may not resume properly");
                }
                log.info("Step: VM resumed, waiting for StepEvent");
            } catch (Exception e) {
                stepPending = false;
                log.warn("Step failed: {}", e.getMessage(), e);
                sendEvent("error", "Step failed: " + e.getMessage());
            }
        }
    }

    public void resume() {
        if (vm == null) {
            log.warn("Resume: vm is null");
            return;
        }
        synchronized (stepLock) {
            log.info("Resume: state={}, stepPending={}, pendingEventSet={}", state, stepPending, pendingEventSet != null);
            state = DebugSessionState.RUNNING;
            stepPending = false;  // Clear step guard on resume/continue
            // Resume the pending event set to clear its JDI-level suspension
            EventSet pending = pendingEventSet;
            pendingEventSet = null;
            if (pending != null) {
                try {
                    pending.resume();
                    log.info("Resume: pending.resume() called successfully");
                } catch (Exception e) {
                    log.warn("Resume: pending.resume() failed: {}", e.getMessage());
                }
            } else {
                log.warn("Resume: pendingEventSet is null");
            }
        }
    }

    // ==================== Stack Frames & Variables ====================

    public List<DebugStackFrame> getStackFrames(long threadId) {
        List<DebugStackFrame> frames = new ArrayList<>();
        if (vm == null) return frames;
        ThreadReference thread = suspendedThreads.get(threadId);
        if (thread == null) return frames;
        try {
            List<StackFrame> threadFrames = thread.frames();
            if (threadFrames == null) return frames;
            int frameId = 0;
            for (StackFrame frame : threadFrames) {
                Location loc = frame.location();
                if (loc == null) continue;
                DebugStackFrame dsf = new DebugStackFrame();
                dsf.setThreadId(threadId);
                dsf.setFrameId(frameId++);
                dsf.setClassName(loc.declaringType().name());
                dsf.setMethodName(loc.method().name());
                dsf.setFileName(loc.sourceName());
                dsf.setLineNumber(loc.lineNumber());

                // Get local variables
                List<DebugVariable> variables = new ArrayList<>();
                try {
                    for (LocalVariable var : frame.visibleVariables()) {
                        Value val = frame.getValue(var);
                        DebugVariable dv = new DebugVariable();
                        dv.setName(var.name());
                        dv.setType(var.typeName());
                        dv.setPrimitive(var.typeName() != null && isPrimitiveType(var.typeName()));
                        if (val == null) {
                            dv.setNul(true);
                            dv.setValue("null");
                        } else {
                            dv.setValue(val.toString());
                            // For object types, get fields (1 level)
                            if (val instanceof ObjectReference && !dv.isPrimitive()) {
                                ObjectReference objRef = (ObjectReference) val;
                                List<DebugVariable> fieldVars = new ArrayList<>();
                                for (Field field : objRef.referenceType().visibleFields()) {
                                    Value fieldVal = objRef.getValue(field);
                                    DebugVariable fv = new DebugVariable();
                                    fv.setName(field.name());
                                    fv.setType(field.typeName());
                                    fv.setPrimitive(field.typeName() != null && isPrimitiveType(field.typeName()));
                                    if (fieldVal == null) {
                                        fv.setNul(true);
                                        fv.setValue("null");
                                    } else {
                                        fv.setValue(fieldVal.toString());
                                    }
                                    fieldVars.add(fv);
                                }
                                if (!fieldVars.isEmpty()) {
                                    dv.setChildren(fieldVars);
                                }
                            }
                        }
                        variables.add(dv);
                    }
                } catch (Exception ve) {
                    // AbsentInformationException means no debug info was compiled.
                    // This can happen if javac was run without -g, or if Maven/Gradle
                    // compiler configuration strips debug information.
                    log.warn("No local variable info for {}.{}() - class may have been compiled without -g flag",
                            loc.declaringType().name(), loc.method().name());
                }
                dsf.setVariables(variables);
                frames.add(dsf);
            }
        } catch (Exception e) {
            log.warn("Failed to get stack frames: {}", e.getMessage());
        }
        return frames;
    }

    public List<DebugVariable> getVariables(long threadId, int frameId) {
        List<DebugStackFrame> frames = getStackFrames(threadId);
        if (frameId < frames.size()) {
            return frames.get(frameId).getVariables();
        }
        return Collections.emptyList();
    }

    public long getSuspendedThreadId() {
        if (suspendedThreads.isEmpty()) return -1;
        return suspendedThreads.keySet().iterator().next();
    }

    // ==================== Internal ====================

    private static final long EVENT_POLL_TIMEOUT_MS = 2000;  // Poll every 2s to check running flag

    private void eventLoop(EventQueue queue) {
        log.info("EventLoop started");
        while (running && vm != null) {
            EventSet eventSet = null;
            try {
                // Use timed wait to allow periodic checking of running flag.
                // This ensures we don't block indefinitely when stop() is called.
                log.debug("EventLoop: waiting for event (timeout={}ms)...", EVENT_POLL_TIMEOUT_MS);
                eventSet = queue.remove(EVENT_POLL_TIMEOUT_MS);
                if (eventSet == null) {
                    // Timeout - just loop back to check running flag
                    log.debug("EventLoop: poll timeout, continuing...");
                    continue;
                }
                log.debug("EventLoop: received eventSet, size={}", eventSet.size());
                // Assign immediately so step()/resume() HTTP threads
                // can see the EventSet even while this thread processes events.
                pendingEventSet = eventSet;

                boolean suspendVm = false;

                for (Event event : eventSet) {
                    if (!running) {
                        log.debug("EventLoop: running=false, breaking");
                        break;
                    }

                    try {
                        log.info("EventLoop: handling event {}", event.getClass().getSimpleName());
                        // 使用 pattern matching switch 分发事件类型（JDK 21+）
                        switch (event) {
                            case BreakpointEvent be -> {
                                handleBreakpointEvent(be);
                                suspendVm = true;
                            }
                            case ClassPrepareEvent cpe -> handleClassPrepareEvent(cpe);
                            case StepEvent se -> {
                                handleStepEvent(se);
                                suspendVm = true;
                            }
                            case VMDeathEvent vmd -> {
                                pendingEventSet = null;
                                sendEventAsync("sessionEnd", "{\"exitCode\":0}");
                                running = false;
                                state = DebugSessionState.DISCONNECTED;
                                log.info("EventLoop: VMDeath/VMDisconnect, exiting");
                                return;
                            }
                            case VMDisconnectEvent vmd -> {
                                pendingEventSet = null;
                                sendEventAsync("sessionEnd", "{\"exitCode\":0}");
                                running = false;
                                state = DebugSessionState.DISCONNECTED;
                                log.info("EventLoop: VMDeath/VMDisconnect, exiting");
                                return;
                            }
                            default -> { }
                        }
                    } catch (Exception ex) {
                        log.warn("Error handling event {}: {}", event.getClass().getSimpleName(), ex.getMessage(), ex);
                    }
                }

                // Only resume if we didn't hit a breakpoint or step (we want to stay suspended)
                if (!suspendVm && running && vm != null) {
                    pendingEventSet = null;
                    eventSet.resume();
                    log.debug("EventLoop: resumed eventSet (no suspend needed)");
                } else if (suspendVm) {
                    log.debug("EventLoop: keeping VM suspended, pendingEventSet retained");
                }
            } catch (InterruptedException e) {
                log.info("EventLoop: interrupted, exiting");
                break;
            } catch (Exception e) {
                pendingEventSet = null;
                if (!running || vm == null) {
                    log.info("EventLoop: exiting (running={}, vm={})", running, vm);
                    break;
                }
                log.warn("EventLoop error: {}", e.getMessage(), e);
                // If VM is disposed or disconnected, exit gracefully
                String msg = e.getMessage();
                if (msg != null && (msg.contains("disposed") || msg.contains("disconnected") || msg.contains("dead"))) {
                    log.info("EventLoop: VM disposed/disconnected, exiting");
                    running = false;
                    state = DebugSessionState.DISCONNECTED;
                    break;
                }
                // For other errors, continue trying (could be transient)
            }
        }
        pendingEventSet = null;
        // Cleanup if event loop exits
        log.info("EventLoop exited, running={}, vm={}", running, vm);
        if (running) {
            sendEventAsync("sessionEnd", "{\"exitCode\":-1}");
            running = false;
            state = DebugSessionState.DISCONNECTED;
        }
    }

    private void handleBreakpointEvent(BreakpointEvent event) {
        ThreadReference thread = event.thread();
        if (thread == null) return;
        Location loc = event.location();
        if (loc == null) return;
        suspendedThreads.put(thread.uniqueID(), thread);
        state = DebugSessionState.SUSPENDED;

        try {
            String json = String.format(
                "{\"className\":\"%s\",\"fileName\":\"%s\",\"lineNumber\":%d,\"threadId\":%d}",
                loc.declaringType().name(),
                loc.sourceName(),
                loc.lineNumber(),
                thread.uniqueID()
            );
            sendEventAsync("breakpointHit", json);
        } catch (Exception e) {
            sendEventAsync("breakpointHit", "{\"lineNumber\":" + loc.lineNumber() + ",\"threadId\":" + thread.uniqueID() + "}");
        }
    }

    private void handleClassPrepareEvent(ClassPrepareEvent event) {
        ReferenceType refType = event.referenceType();
        if (refType == null) return;
        String className = refType.name();

        // Re-apply pending breakpoints for this class
        for (DebugBreakpoint bp : breakpoints.values()) {
            if (bp.isEnabled() && bp.getClassName().equals(className) && bp.getRequestId() == null) {
                setBreakpointInternal(bp);
            }
        }
    }

    private void handleStepEvent(StepEvent event) {
        ThreadReference thread = event.thread();
        if (thread == null) return;
        Location loc = event.location();
        if (loc == null) return;
        suspendedThreads.put(thread.uniqueID(), thread);
        state = DebugSessionState.SUSPENDED;
        stepPending = false;  // Allow next step after StepEvent processed

        try {
            String json = String.format(
                "{\"className\":\"%s\",\"fileName\":\"%s\",\"lineNumber\":%d,\"threadId\":%d}",
                loc.declaringType().name(),
                loc.sourceName(),
                loc.lineNumber(),
                thread.uniqueID()
            );
            sendEventAsync("breakpointHit", json);
        } catch (Exception e) {
            sendEventAsync("breakpointHit", "{\"lineNumber\":" + loc.lineNumber() + ",\"threadId\":" + thread.uniqueID() + "}");
        }
    }

    private com.sun.jdi.connect.AttachingConnector getSocketConnector() {
        VirtualMachineManager vmm = Bootstrap.virtualMachineManager();
        for (com.sun.jdi.connect.Connector connector : vmm.attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(connector.name()) ||
                "dt_socket".equals(connector.transport().name())) {
                return (com.sun.jdi.connect.AttachingConnector) connector;
            }
        }
        throw new RuntimeException("No socket attaching connector found");
    }

    private boolean isPrimitiveType(String typeName) {
        return "byte".equals(typeName) || "short".equals(typeName) || "int".equals(typeName) ||
               "long".equals(typeName) || "float".equals(typeName) || "double".equals(typeName) ||
               "boolean".equals(typeName) || "char".equals(typeName) ||
               "java.lang.Byte".equals(typeName) || "java.lang.Short".equals(typeName) ||
               "java.lang.Integer".equals(typeName) || "java.lang.Long".equals(typeName) ||
               "java.lang.Float".equals(typeName) || "java.lang.Double".equals(typeName) ||
               "java.lang.Boolean".equals(typeName) || "java.lang.Character".equals(typeName) ||
               "java.lang.String".equals(typeName);
    }

    // ==================== Attach to Running JVM ====================

    /**
     * Attach to a running JVM process that has JDWP debugging enabled.
     */
    public synchronized void attach(int port, String classpath, SseEventCallback callback) throws Exception {
        if (running) {
            throw new IllegalStateException("Debug session already running");
        }

        this.eventCallback = callback;
        this.suspendedThreads.clear();
        this.jdwpPort = port;
        state = DebugSessionState.INITIALIZING;

        // Attach JDI using socket connector
        com.sun.jdi.connect.AttachingConnector connector = getSocketConnector();
        Map<String, com.sun.jdi.connect.Connector.Argument> args_map = connector.defaultArguments();
        com.sun.jdi.connect.Connector.Argument portArg = args_map.get("port");
        if (portArg != null) {
            portArg.setValue(String.valueOf(port));
        }
        com.sun.jdi.connect.Connector.Argument hostArg = args_map.get("hostname");
        if (hostArg != null) {
            hostArg.setValue("localhost");
        }
        com.sun.jdi.connect.Connector.Argument timeoutArg = args_map.get("timeout");
        if (timeoutArg != null) {
            timeoutArg.setValue("15000");
        }

        vm = connector.attach(args_map);
        running = true;
        log.info("JDI attached to running JVM on port {}", port);

        // Create EventQueue in this thread before setting up event requests
        final EventQueue eventQueue = vm.eventQueue();
        eventThread = new Thread(() -> eventLoop(eventQueue), "debug-event-loop");
        eventThread.setDaemon(true);
        eventThread.start();

        // Apply breakpoints
        for (DebugBreakpoint bp : breakpoints.values()) {
            if (bp.isEnabled()) {
                setBreakpointInternal(bp);
            }
        }

        // Set up ClassPrepareRequest for deferred breakpoints with dynamic class filters
        classPrepareRequest = vm.eventRequestManager().createClassPrepareRequest();
        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        // Add filters for all current breakpoint classes
        for (DebugBreakpoint bp : breakpoints.values()) {
            if (bp.isEnabled()) {
                try {
                    classPrepareRequest.addClassFilter(bp.getClassName() + "*");
                } catch (Exception ignored) {}
            }
        }
        classPrepareRequest.enable();

        state = DebugSessionState.RUNNING;
        sendEvent("debugStarted", "{\"port\":" + port + "}");
    }

    /**
     * List running JVM processes that can be attached to.
     * Returns a list of process info maps with pid, name, and port.
     */
    public List<Map<String, Object>> listRunningJvms() {
        List<Map<String, Object>> processes = new ArrayList<>();
        // Note: Listing running JVMs via JDI requires special permissions
        // and is implementation-dependent. For now, return empty list.
        // The user can manually specify the JDWP port when attaching.
        log.debug("listRunningJvms called - returning empty list (use attach with manual port)");
        return processes;
    }

    // ==================== Project Compilation ====================

    /**
     * Compile the project before debugging.
     * Returns true if compilation succeeded, false otherwise.
     */
    public boolean compileProject(File projectDir, String projectType, SseEventCallback callback) {
        this.eventCallback = callback;
        try {
            ProcessBuilder pb;
            String javaHome = getConfiguredJavaHome();
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");

            if ("maven".equals(projectType)) {
                String mvnCmd = isWin ? "mvn.cmd" : "mvn";
                pb = new ProcessBuilder(mvnCmd, "compile", "-DskipTests", "-Dmaven.compiler.debug=true");
                pb.directory(projectDir);
                pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
            } else if ("gradle".equals(projectType)) {
                String gradleCmd = isWin ? "gradle.bat" : "gradle";
                pb = new ProcessBuilder(gradleCmd, "compileJava");
                pb.directory(projectDir);
                pb.environment().put("JAVA_TOOL_OPTIONS", "-Dfile.encoding=UTF-8");
            } else {
                // Plain project: compile with javac -g for debug info (line numbers + variables)
                File javacFile = new File(javaHome + File.separator + "bin" + File.separator + (isWin ? "javac.exe" : "javac"));
                if (javacFile.exists()) {
                    File outputDir = new File(projectDir, "target" + File.separator + "classes");
                    outputDir.mkdirs();
                    List<String> cmd = new ArrayList<>();
                    cmd.add(javacFile.getAbsolutePath());
                    cmd.add("-g");              // Full debug info: source, lines, vars
                    cmd.add("-encoding"); cmd.add("UTF-8");
                    cmd.add("-proc:none");      // Skip annotation processing for plain projects
                    // Determine source directories: prefer src/main/java, fallback to src
                    File srcMainJava = new File(projectDir, "src" + File.separator + "main" + File.separator + "java");
                    File srcDir = srcMainJava.exists() ? srcMainJava : new File(projectDir, "src");
                    if (srcDir.exists()) {
                        cmd.add("-sourcepath"); cmd.add(srcDir.getAbsolutePath());
                    }
                    // Add classpath from lib/*.jar if present
                    File libDir = new File(projectDir, "lib");
                    if (libDir.exists() && libDir.isDirectory()) {
                        File[] jars = libDir.listFiles((d, n) -> n.endsWith(".jar"));
                        if (jars != null && jars.length > 0) {
                            StringBuilder cp = new StringBuilder();
                            for (File jar : jars) {
                                if (cp.length() > 0) cp.append(File.pathSeparator);
                                cp.append(jar.getAbsolutePath());
                            }
                            cmd.add("-classpath"); cmd.add(cp.toString());
                        }
                    }
                    cmd.add("-d"); cmd.add(outputDir.getAbsolutePath());
                    // Find all .java files under the source directory
                    if (srcDir.exists()) {
                        java.nio.file.Files.walk(srcDir.toPath())
                            .filter(p -> p.toString().endsWith(".java"))
                            .forEach(p -> cmd.add(p.toString()));
                    }
                    log.info("Compiling plain project: {}", String.join(" ", cmd));
                    pb = new ProcessBuilder(cmd);
                    pb.directory(projectDir);
                } else {
                    log.warn("javac not found at {}, skipping compile for plain project", javacFile.getAbsolutePath());
                    return true;
                }
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read merged output (stdout + stderr)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sendEvent("line", line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.error("Compilation failed: {}", e.getMessage());
            sendEvent("error", "Compilation failed: " + e.getMessage());
            return false;
        }
    }

    private String getConfiguredJavaHome() {
        // Priority: JAVA_HOME env > IDE's own JDK (java.home)
        // This mirrors ClasspathBuilder.getConfiguredJavaHome() but uses a simpler fallback
        String envHome = System.getenv("JAVA_HOME");
        if (envHome != null && !envHome.isEmpty()) {
            File javac = new File(envHome + File.separator + "bin" + File.separator + "javac");
            if (javac.exists() || new File(envHome + File.separator + "bin" + File.separator + "javac.exe").exists()) {
                return envHome;
            }
        }
        return System.getProperty("java.home");
    }

    /**
     * Shared helper: detect JDWP port from process output, attach JDI, start event loop.
     * Called after the target process has been started by start() or startWith*().
     */
    private void attachToTargetJvm(Process process) throws Exception {
        // Read JDWP port from merged output (format: "Listening for transport dt_socket at address: PORT")
        final int[] portHolder = new int[1];
        final StringBuilder allOutput = new StringBuilder();
        final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>();
        final AtomicBoolean readerDone = new AtomicBoolean(false);

        Thread outputReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"))) {
                String l;
                while ((l = r.readLine()) != null) {
                    outputQueue.offer(l);
                }
            } catch (IOException ignored) {
            } finally {
                readerDone.set(true);
            }
        }, "debug-output-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        long deadline = System.currentTimeMillis() + 30000; // 30s timeout (build tools are slower)
        try {
            while (System.currentTimeMillis() < deadline && portHolder[0] == 0) {
                String line = outputQueue.poll(500, TimeUnit.MILLISECONDS);
                if (line != null) {
                    log.debug("Target output: {}", line);
                    allOutput.append(line).append("\n");
                    sendEvent("line", line);
                    if (line.contains("Listening for transport") || line.contains("Listening for address")) {
                        int idx = line.lastIndexOf(':');
                        if (idx >= 0) {
                            String afterLastColon = line.substring(idx + 1).trim();
                            String[] parts = afterLastColon.split("\\s+");
                            String portStr = parts[parts.length - 1].trim();
                            if (portStr.contains(":")) {
                                portStr = portStr.substring(portStr.lastIndexOf(':') + 1);
                            }
                            try {
                                portHolder[0] = Integer.parseInt(portStr);
                                break;
                            } catch (NumberFormatException e) {
                                log.warn("Failed to parse JDWP port from: {}", line);
                            }
                        }
                    }
                }
                // If process died, drain and break
                if (!process.isAlive() && readerDone.get()) {
                    String remaining;
                    while ((remaining = outputQueue.poll()) != null) {
                        allOutput.append(remaining).append("\n");
                    }
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (portHolder[0] == 0) {
            // Capture exit code and all output from the failed process
            int exitCode = -1;
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException e) {
                process.destroyForcibly();
                exitCode = process.exitValue();
            }
            String detail = allOutput.length() > 0 ? allOutput.toString().trim() : "No output from target JVM";
            stop();
            throw new RuntimeException("Failed to determine JDWP port from target JVM output (exit code: "
                    + exitCode + "). Target JVM output: " + detail);
        }
        this.jdwpPort = portHolder[0];

        // Start drain thread to forward remaining output to SSE
        Thread outputDrainThread = new Thread(() -> {
            try {
                while (running) {
                    String l = outputQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (l != null) {
                        sendEvent("line", l);
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "debug-output-drain");
        outputDrainThread.setDaemon(true);
        outputDrainThread.start();

        // Attach JDI using connector's default arguments
        com.sun.jdi.connect.AttachingConnector connector = getSocketConnector();
        Map<String, com.sun.jdi.connect.Connector.Argument> args_map = connector.defaultArguments();
        com.sun.jdi.connect.Connector.Argument portArg = args_map.get("port");
        if (portArg != null) {
            portArg.setValue(String.valueOf(portHolder[0]));
        }
        com.sun.jdi.connect.Connector.Argument hostArg = args_map.get("hostname");
        if (hostArg != null) {
            hostArg.setValue("localhost");
        }
        com.sun.jdi.connect.Connector.Argument timeoutArg = args_map.get("timeout");
        if (timeoutArg != null) {
            timeoutArg.setValue("15000");
        }

        vm = connector.attach(args_map);
        log.info("JDI attached to target JVM on port {}", portHolder[0]);

        // Clear stale request IDs from previous VM before applying breakpoints
        for (DebugBreakpoint bp : breakpoints.values()) {
            bp.setRequestId(null);
        }

        // First pass: apply breakpoints.
        for (DebugBreakpoint bp : breakpoints.values()) {
            if (bp.isEnabled()) {
                setBreakpointInternal(bp);
            }
        }

        // Set up ClassPrepareRequest for deferred breakpoints.
        // SUSPEND_EVENT_THREAD pauses only the class-loading thread so the
        // event loop has time to install breakpoints before main() runs.
        // With blocking queue.remove() the pause is microseconds, so even
        // JDK class loads during stepping cause negligible delay.
        classPrepareRequest = vm.eventRequestManager().createClassPrepareRequest();
        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        // Add filters for all current breakpoint classes
        for (DebugBreakpoint bp : breakpoints.values()) {
            if (bp.isEnabled()) {
                try {
                    classPrepareRequest.addClassFilter(bp.getClassName() + "*");
                } catch (Exception ignored) {}
            }
        }
        classPrepareRequest.enable();

        // Create EventQueue in this thread BEFORE starting event loop thread,
        // then resume. This guarantees the queue is capturing events from the
        // moment the VM resumes, regardless of thread scheduling.
        final EventQueue eventQueue = vm.eventQueue();
        eventThread = new Thread(() -> eventLoop(eventQueue), "debug-event-loop");
        eventThread.setDaemon(true);
        eventThread.start();

        // Now resume the VM — EventQueue is already created and collecting events
        try {
            vm.resume();
            log.info("Target JVM resumed");
        } catch (Exception e) {
            log.warn("Failed to resume VM (may already be running): {}", e.getMessage());
        }

        state = DebugSessionState.RUNNING;
        sendEvent("debugStarted", "{\"port\":" + portHolder[0] + "}");
    }

    /**
     * Rebuild ClassPrepareRequest with filters for all breakpoints whose classes
     * have not yet been loaded (requestId is null). JDI does not support removing
     * individual class filters, so we recreate the request from scratch.
     */
    private void rebuildClassPrepareFilters() {
        if (vm == null) return;
        try {
            if (classPrepareRequest != null) {
                vm.eventRequestManager().deleteEventRequest(classPrepareRequest);
            }
        } catch (Exception ignored) {}
        classPrepareRequest = vm.eventRequestManager().createClassPrepareRequest();
        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        for (DebugBreakpoint bp : breakpoints.values()) {
            if (bp.isEnabled() && bp.getRequestId() == null) {
                try {
                    classPrepareRequest.addClassFilter(bp.getClassName() + "*");
                } catch (Exception ignored) {}
            }
        }
        classPrepareRequest.enable();
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    private void sendEvent(String event, String data) {
        if (eventCallback != null) {
            try {
                eventCallback.onEvent(event, data);
            } catch (Exception e) {
                log.warn("Failed to send SSE event {}: {}", event, e.getMessage());
            }
        }
    }

    /**
     * Async variant for use on the event loop thread only — prevents
     * {@link org.springframework.web.servlet.mvc.method.annotation.SseEmitter#send}
     * back-pressure from stalling event processing.  Non-event-loop callers
     * (HTTP threads, output-drain) use {@link #sendEvent} synchronously.
     */
    private void sendEventAsync(String event, String data) {
        if (eventCallback != null) {
            SseEventCallback cb = eventCallback;
            log.info("sendEventAsync: submitting {}", event);
            sseExecutor.submit(() -> {
                try {
                    cb.onEvent(event, data);
                    log.info("sendEventAsync: {} delivered", event);
                } catch (Exception e) {
                    log.warn("sendEventAsync: {} failed — {}", event, e.getMessage());
                }
            });
        } else {
            log.warn("sendEventAsync: eventCallback is null, dropping {}", event);
        }
    }
}
