package com.github.obhen233.spi.impl;

import com.github.obhen233.spi.UpgradePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Console;

/**
 * Default upgrade policy that prompts the user before upgrading.
 * Respects the core.upgrade.policy config setting:
 * - prompt: ask user (default)
 * - auto: upgrade without asking
 * - disable: skip upgrade
 */
public class PromptUpgradePolicy implements UpgradePolicy {

    private static final Logger logger = LoggerFactory.getLogger(PromptUpgradePolicy.class);
    private final String mode;

    public PromptUpgradePolicy() {
        this("prompt");
    }

    public PromptUpgradePolicy(String mode) {
        this.mode = (mode != null) ? mode : "prompt";
    }

    @Override
    public boolean shouldUpgrade(String currentVersion, String availableVersion) {
        switch (mode) {
            case "auto":
                logger.info("Auto-upgrade enabled, upgrading core from {} to {}", currentVersion, availableVersion);
                return true;
            case "disable":
                logger.info("Core upgrade disabled by policy, keeping version {}", currentVersion);
                return false;
            case "prompt":
            default:
                return promptUser(currentVersion, availableVersion);
        }
    }

    private boolean promptUser(String currentVersion, String availableVersion) {
        Console console = System.console();
        if (console == null) {
            // No console available, skip upgrade
            System.out.println("Core upgrade available: " + currentVersion + " -> " + availableVersion);
            System.out.println("No console available for prompt, skipping upgrade.");
            return false;
        }

        System.out.println();
        System.out.println("=== Core Upgrade Available ===");
        System.out.println("  Current version: " + currentVersion);
        System.out.println("  New version:     " + availableVersion);
        System.out.println("==============================");
        String input = console.readLine("Upgrade core? [Y/n]: ");

        if (input == null || input.trim().isEmpty() || "y".equalsIgnoreCase(input.trim())
                || "yes".equalsIgnoreCase(input.trim())) {
            return true;
        }
        return false;
    }

    @Override
    public void onUpgradeSuccess(String oldVersion, String newVersion) {
        System.out.println("Core upgraded: " + oldVersion + " -> " + newVersion);
        logger.info("Core upgraded successfully: {} -> {}", oldVersion, newVersion);
    }

    @Override
    public void onUpgradeFailed(String oldVersion, String newVersion, String reason) {
        System.out.println("Core upgrade failed: " + reason);
        logger.error("Core upgrade failed: {} -> {}, reason: {}", oldVersion, newVersion, reason);
    }
}
