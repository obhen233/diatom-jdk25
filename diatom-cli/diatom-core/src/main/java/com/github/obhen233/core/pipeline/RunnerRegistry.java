package com.github.obhen233.core.pipeline;

import com.github.obhen233.spi.PipelineRunnerRegistrar;
import com.github.obhen233.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry for all PipelineRunner implementations.
 * Runners can be registered manually or discovered via
 * {@link PipelineRunnerRegistrar} SPI.
 */
public class RunnerRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RunnerRegistry.class);

    private final Map<String, PipelineRunner> runners = new HashMap<>();

    public RunnerRegistry() {
    }

    /**
     * Create registry pre-populated with a list of runners.
     */
    public RunnerRegistry(List<PipelineRunner> runnerList) {
        if (runnerList != null) {
            for (PipelineRunner runner : runnerList) {
                register(runner);
            }
        }
    }

    /**
     * Register a single runner. If another runner is already registered for
     * the same action type, the new one replaces it (last registration wins).
     */
    public void register(PipelineRunner runner) {
        String actionType = runner.getActionType();
        if (runners.containsKey(actionType)) {
            logger.warn("Duplicate runner for action type: {}. Overriding with {}", actionType, runner.getClass().getName());
        }
        runners.put(actionType, runner);
        logger.info("Registered pipeline runner: {} -> {}", actionType, runner.getClass().getSimpleName());
    }

    /**
     * Discover and register all runners from {@link PipelineRunnerRegistrar}
     * SPI implementations. Built-in runners from {@code DefaultPipelineRunnerRegistrar}
     * are loaded first; custom registrars can override by registering the same
     * action type.
     *
     * <p>Safe to call multiple times — {@link SpiLoader#loadAll()} is idempotent.</p>
     */
    public void discoverFromSpi() {
        SpiLoader.loadAll();
        List<PipelineRunnerRegistrar> registrars = SpiLoader.getAll(PipelineRunnerRegistrar.class);
        for (PipelineRunnerRegistrar registrar : registrars) {
            logger.info("Discovering pipeline runners from SPI: {}", registrar.getClass().getName());
            registrar.registerRunners(this);
        }
    }

    /**
     * Get a runner for the given action type.
     *
     * @param actionType the action type (e.g., "run_command", "ssh_command")
     * @return the runner, or null if no runner is registered for this action type
     */
    public PipelineRunner getRunner(String actionType) {
        return runners.get(actionType);
    }

    /**
     * Returns a snapshot of all registered runners keyed by action type.
     */
    public Map<String, PipelineRunner> getRunners() {
        return new HashMap<>(runners);
    }
}
