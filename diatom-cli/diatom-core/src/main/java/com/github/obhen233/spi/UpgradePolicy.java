package com.github.obhen233.spi;

/**
 * Policy for handling core version upgrades.
 * Determines whether and how to upgrade when a newer core version is found.
 */
public interface UpgradePolicy {

    /**
     * Decide whether to upgrade to the available version.
     * @param currentVersion the currently installed core version
     * @param availableVersion the latest available version from Maven Central
     * @return true to proceed with upgrade, false to skip
     */
    boolean shouldUpgrade(String currentVersion, String availableVersion);

    /**
     * Called after a successful upgrade.
     * @param oldVersion the previous version
     * @param newVersion the new version
     */
    default void onUpgradeSuccess(String oldVersion, String newVersion) {}

    /**
     * Called when an upgrade fails (e.g., download failure, compile check failure).
     * @param oldVersion the previous version
     * @param newVersion the target version that failed
     * @param reason the failure reason
     */
    default void onUpgradeFailed(String oldVersion, String newVersion, String reason) {}
}
