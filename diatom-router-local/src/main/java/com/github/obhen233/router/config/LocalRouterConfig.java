package com.github.obhen233.router.config;

/**
 * Configuration holder for the local ML router.
 * <p>
 * Reads settings from system properties with sensible defaults.
 * All properties are prefixed with {@code gateway.router.local.}.
 */
public class LocalRouterConfig {

    private static final String PREFIX = "gateway.router.local.";

    private final boolean enabled;
    private final double threshold;
    private final boolean learnEnabled;
    private final String keywordsPath;
    private final boolean categoryGenEnabled;
    private final String categoryCachePath;
    private final String trainingDataPath;
    private final String trainingImportPath;

    public LocalRouterConfig() {
        this.enabled = Boolean.parseBoolean(System.getProperty(PREFIX + "enabled", "true"));
        this.threshold = Double.parseDouble(System.getProperty(PREFIX + "threshold", "0.7"));
        this.learnEnabled = Boolean.parseBoolean(System.getProperty(PREFIX + "learn-enabled", "true"));
        this.keywordsPath = System.getProperty(PREFIX + "keywords-path",
                ".diatom/router-keywords.json");
        this.categoryGenEnabled = Boolean.parseBoolean(
                System.getProperty(PREFIX + "category-gen-enabled", "true"));
        this.categoryCachePath = System.getProperty(PREFIX + "category-cache-path",
                ".diatom/router-categories.json");
        this.trainingDataPath = System.getProperty(PREFIX + "training-data-path",
                ".diatom/router-training.json");
        this.trainingImportPath = System.getProperty(PREFIX + "training-import-path", "");
    }

    /**
     * Whether the local router is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Minimum confidence score (0.0 - 1.0) for a local routing result
     * to be accepted without falling back to the LLM.
     */
    public double getThreshold() {
        return threshold;
    }

    /**
     * Whether keyword self-learning is enabled.
     */
    public boolean isLearnEnabled() {
        return learnEnabled;
    }

    /**
     * Path for persisting learned keywords JSON.
     */
    public String getKeywordsPath() {
        return keywordsPath;
    }

    /**
     * Whether LLM-based dynamic category generation is enabled.
     * When enabled, the router uses the LLM to generate routing categories
     * from actual worker capabilities at startup and when capabilities change.
     */
    public boolean isCategoryGenEnabled() {
        return categoryGenEnabled;
    }

    /**
     * Path for caching LLM-generated routing categories.
     * Persisted to avoid redundant LLM calls on restart.
     */
    public String getCategoryCachePath() {
        return categoryCachePath;
    }

    /**
     * Path for persisting LLM feedback training data.
     * Accumulated (message, category) pairs used for future analysis.
     */
    public String getTrainingDataPath() {
        return trainingDataPath;
    }

    /**
     * Path to a manually curated training JSON file for importing.
     * If set and the file exists, the router imports it at startup to
     * bootstrap categories and keywords.
     * <p>
     * Format: {@code [{"message":"...","category":"..."}, ...]}
     */
    public String getTrainingImportPath() {
        return trainingImportPath;
    }

    @Override
    public String toString() {
        return "LocalRouterConfig{" +
                "enabled=" + enabled +
                ", threshold=" + threshold +
                ", learnEnabled=" + learnEnabled +
                ", categoryGenEnabled=" + categoryGenEnabled +
                ", keywordsPath='" + keywordsPath + '\'' +
                ", categoryCachePath='" + categoryCachePath + '\'' +
                ", trainingDataPath='" + trainingDataPath + '\'' +
                ", trainingImportPath='" + trainingImportPath + '\'' +
                '}';
    }
}
