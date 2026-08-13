package com.github.obhen233.router.local;

import com.github.obhen233.core.gateway.registry.WorkerInfo;
import com.github.obhen233.core.gateway.routing.TaskRequirement;
import com.github.obhen233.router.config.LocalRouterConfig;
import com.github.obhen233.spi.LocalRequestRouter;
import com.github.obhen233.spi.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SPI implementation of {@link LocalRequestRouter} using keyword matching + TF-IDF.
 * <p>
 * Classifies user requests locally using a two-stage approach:
 * <ol>
 *   <li>Keyword matching via JDK BreakIterator tokenization (fast, no model needed)</li>
 *   <li>TF-IDF + Cosine Similarity (fallback, pure JDK implementation)</li>
 * </ol>
 * If confidence reaches the configured threshold, the LLM call is bypassed.
 * <p>
 * Supports self-learning: keywords are accumulated from partial matches and
 * reinforced on successful routes.
 */
public class LocalRequestRouterImpl implements LocalRequestRouter {

    private static final Logger logger = LoggerFactory.getLogger(LocalRequestRouterImpl.class);

    private final LocalRouterConfig config;
    private List<CategoryDef> categories;
    private HanlpTextClassifier classifier;
    private final KeywordStore keywordStore;
    private final KeywordLearner learner;
    private final TrainingDataStore trainingDataStore;

    // Dynamic category generation (B + C strategy)
    private String categoriesChecksum;        // hash of capabilities when categories were generated
    private int routeCount = 0;
    private Function<String, String> llmCaller;
    private static final int FULL_REFRESH_INTERVAL = 100;  // periodic full refresh every N routes

    public LocalRequestRouterImpl() {
        this.config = new LocalRouterConfig();
        this.categories = new ArrayList<>(CategoryDef.defaults());
        this.keywordStore = config.isLearnEnabled()
                ? new KeywordStore(Paths.get(config.getKeywordsPath()))
                : null;
        this.learner = config.isLearnEnabled()
                ? new KeywordLearner(keywordStore, true)
                : null;
        this.trainingDataStore = config.isLearnEnabled()
                ? new TrainingDataStore(Paths.get(config.getTrainingDataPath()))
                : null;
        this.classifier = new HanlpTextClassifier(categories, config, learner);

        // Try loading cached LLM-generated categories
        if (config.isCategoryGenEnabled()) {
            List<CategoryDef> cached = loadCachedCategories();
            if (cached != null && !cached.isEmpty()) {
                this.categories = new ArrayList<>(cached);
                this.classifier = new HanlpTextClassifier(this.categories, config, learner);
                logger.info("Loaded {} categories from cache", cached.size());
            }
        }

        // Auto-import manually curated training data at startup
        String importPath = config.getTrainingImportPath();
        if (importPath != null && !importPath.isEmpty()) {
            Path importFile = Paths.get(importPath);
            if (Files.exists(importFile)) {
                logger.info("Auto-importing training data from {}", importFile);
                int count = importTrainingData(importFile);
                if (count > 0) {
                    logger.info("Auto-imported {} training samples", count);
                }
            } else {
                logger.debug("Training import file not found: {}", importFile);
            }
        }

        logger.info("LocalRequestRouterImpl initialized: {}", config);
    }

    public LocalRequestRouterImpl(LocalRouterConfig config, HanlpTextClassifier classifier,
                                   List<CategoryDef> categories) {
        this(config, classifier, categories, null, null, null);
    }

    public LocalRequestRouterImpl(LocalRouterConfig config, HanlpTextClassifier classifier,
                                   List<CategoryDef> categories, KeywordStore keywordStore,
                                   KeywordLearner learner) {
        this(config, classifier, categories, keywordStore, learner, null);
    }

    public LocalRequestRouterImpl(LocalRouterConfig config, HanlpTextClassifier classifier,
                                   List<CategoryDef> categories, KeywordStore keywordStore,
                                   KeywordLearner learner, TrainingDataStore trainingDataStore) {
        this.config = config;
        this.classifier = classifier;
        this.categories = categories;
        this.keywordStore = keywordStore;
        this.learner = learner;
        this.trainingDataStore = trainingDataStore;
    }

    // ========================================================================
    // Dynamic category generation via LLM (B + C strategy)
    // ========================================================================

    /**
     * B + C strategy for dynamic category generation:
     * <ul>
     *   <li><b>Checksum lazy check (B)</b> — fast capability hash comparison on every call</li>
     *   <li><b>Incremental merge (C)</b> — only generate categories for NEW capabilities</li>
     *   <li><b>Periodic full refresh</b> — every {@link #FULL_REFRESH_INTERVAL} routes, regenerate all</li>
     * </ul>
     */
    @Override
    public void initialize(List<WorkerInfo> workers, Function<String, String> llmCaller) {
        if (!config.isCategoryGenEnabled() || workers == null || workers.isEmpty()) {
            return;
        }
        this.llmCaller = llmCaller;

        Set<String> allCaps = extractAllCapabilities(workers);
        String newChecksum = computeChecksum(allCaps);

        // B: Fast checksum comparison — skip if nothing changed
        if (newChecksum.equals(this.categoriesChecksum)) {
            return;
        }

        if (this.categoriesChecksum == null) {
            // First initialization: full generation
            logger.info("Generating routing categories from {} capabilities: {}",
                    allCaps.size(), String.join(", ", allCaps));
            List<CategoryDef> generated = generateCategoriesViaLLM(allCaps, true);
            if (generated != null && !generated.isEmpty()) {
                updateCategories(generated);
                this.categoriesChecksum = newChecksum;
                persistCategories(generated);
                logger.info("Generated {} routing categories from capabilities", generated.size());
            }
        } else {
            // C: Capabilities changed — incremental generation for new capabilities only
            Set<String> existingCaps = extractExistingCapabilities();
            Set<String> newCaps = new TreeSet<>(allCaps);
            newCaps.removeAll(existingCaps);
            Set<String> removedCaps = new TreeSet<>(existingCaps);
            removedCaps.removeAll(allCaps);

            if (!newCaps.isEmpty()) {
                logger.info("New capabilities detected: {}, generating incremental categories",
                        String.join(", ", newCaps));
                List<CategoryDef> incremental = generateCategoriesViaLLM(newCaps, false);
                if (incremental != null && !incremental.isEmpty()) {
                    Set<String> existingIds = categories.stream()
                            .map(CategoryDef::getId).collect(Collectors.toSet());
                    for (CategoryDef cat : incremental) {
                        if (!existingIds.contains(cat.getId())) {
                            categories.add(cat);
                            logger.debug("Added incremental category '{}' for capabilities {}",
                                    cat.getId(), cat.getCapabilities());
                        }
                    }
                    // Rebuild classifier with merged categories
                    this.classifier = new HanlpTextClassifier(categories, config, learner);
                    persistCategories(categories);
                }
            } else if (removedCaps.isEmpty()) {
                // No new or removed capabilities, but checksum differs
                // This can happen if the order changed — ignore
                this.categoriesChecksum = newChecksum;
                return;
            }

            this.categoriesChecksum = newChecksum;
        }
    }

    // ========================================================================
    // LLM feedback learning (supervised learning via onClassified)
    // ========================================================================

    /**
     * Called after the LLM processes a request that the local router couldn't handle.
     * <p>
     * Extracts new keywords from the LLM-confirmed classification and accumulates
     * training data for future analysis.
     */
    @Override
    public void onClassified(String message, String actualCategory) {
        if (!config.isLearnEnabled() || learner == null || message == null) {
            return;
        }

        // Find the matching category by ID
        CategoryDef category = findCategoryById(actualCategory);
        if (category == null) {
            logger.debug("onClassified: unknown category '{}', skipping", actualCategory);
            return;
        }

        // Tokenize the message and learn new keywords (LLM-confirmed, higher weight)
        List<String> tokens = classifier.tokenize(message);
        if (tokens.isEmpty()) return;

        learner.learnFromFeedback(message, category, tokens);

        // Accumulate training data
        if (trainingDataStore != null) {
            trainingDataStore.add(message, actualCategory);
            trainingDataStore.save();
        }

        // Flush keyword store periodically
        if (keywordStore != null) {
            keywordStore.save();
        }

        logger.debug("onClassified: learned from '{}' as '{}' ({} tokens)",
                message.length() > 30 ? message.substring(0, 30) + "..." : message,
                actualCategory, tokens.size());
    }

    // ========================================================================
    // Manual training data import
    // ========================================================================

    /**
     * Import a manually curated training file to bootstrap categories and keywords.
     * <p>
     * Training file format: JSON array of objects with "message" and "category" fields:
     * <pre>
     * [{"message":"修复登录bug","category":"bug_fix"}, ...]
     * </pre>
     * <p>
     * For each unique category in the file, this method:
     * <ol>
     *   <li>Creates a new {@link CategoryDef} if the category doesn't exist yet</li>
     *   <li>Tokenizes each message and learns keywords via {@link KeywordLearner#learnFromFeedback}</li>
     *   <li>Accumulates entries in the {@link TrainingDataStore}</li>
     * </ol>
     * <p>
     * Can be called multiple times — new data is merged with existing categories
     * and keywords.
     *
     * @param path path to the training JSON file
     * @return number of training entries imported, or -1 on failure
     */
    public int importTrainingData(Path path) {
        if (!config.isLearnEnabled() || learner == null || path == null) {
            logger.warn("Training import skipped: learning disabled or learner unavailable");
            return -1;
        }
        if (!Files.exists(path)) {
            logger.warn("Training file not found: {}", path);
            return -1;
        }

        try {
            String content = new String(Files.readAllBytes(path), "UTF-8").trim();
            // Parse training entries from JSON array
            List<TrainingEntryRaw> entries = parseTrainingJson(content);
            if (entries == null || entries.isEmpty()) {
                logger.warn("No training entries found in {}", path);
                return 0;
            }

            // Group by category
            Map<String, List<TrainingEntryRaw>> byCategory = entries.stream()
                    .collect(Collectors.groupingBy(e -> e.category));

            int newCategoryCount = 0;
            int totalMessages = 0;

            for (Map.Entry<String, List<TrainingEntryRaw>> group : byCategory.entrySet()) {
                String catId = group.getKey();

                // Ensure category exists — create if not found
                CategoryDef category = findCategoryById(catId);
                if (category == null) {
                    // Infer keywords from training messages
                    Set<String> allTokens = new HashSet<>();
                    for (TrainingEntryRaw entry : group.getValue()) {
                        List<String> tokens = classifier.tokenize(entry.message);
                        for (String tok : tokens) {
                            String norm = tok.toLowerCase(Locale.ROOT).trim();
                            if (norm.length() >= 2 && !KeywordLearner.isStopWord(norm)) {
                                allTokens.add(norm);
                            }
                        }
                    }
                    // Split into likely Chinese vs English keywords
                    List<String> chineseKw = new ArrayList<>();
                    List<String> englishKw = new ArrayList<>();
                    for (String tok : allTokens) {
                        if (KeywordLearner.isChinese(tok)) {
                            chineseKw.add(tok);
                        } else {
                            englishKw.add(tok);
                        }
                    }
                    category = new CategoryDef(catId,
                            chineseKw.isEmpty() ? null : chineseKw,
                            englishKw.isEmpty() ? null : englishKw,
                            Collections.singletonList(catId),
                            "Imported from training data: " + catId);
                    categories.add(category);
                    newCategoryCount++;
                    logger.info("Created new category '{}' from training import ({} keywords)",
                            catId, allTokens.size());
                }

                // Learn keywords from each message
                for (TrainingEntryRaw entry : group.getValue()) {
                    List<String> tokens = classifier.tokenize(entry.message);
                    if (tokens.isEmpty()) continue;
                    learner.learnFromFeedback(entry.message, category, tokens);
                    if (trainingDataStore != null) {
                        trainingDataStore.add(entry.message, catId);
                    }
                    totalMessages++;
                }
            }

            // Persist changes
            if (newCategoryCount > 0) {
                this.classifier = new HanlpTextClassifier(categories, config, learner);
            }
            if (keywordStore != null) keywordStore.save();
            if (trainingDataStore != null) trainingDataStore.save();

            logger.info("Training import complete: {} categories ({} new), {} messages from {}",
                    byCategory.size(), newCategoryCount, totalMessages, path);
            return totalMessages;

        } catch (Exception e) {
            logger.warn("Failed to import training data from {}: {}", path, e.getMessage());
            return -1;
        }
    }

    /**
     * Parse training JSON array into raw entries.
     */
    private List<TrainingEntryRaw> parseTrainingJson(String json) {
        String cleaned = json.trim();
        if (cleaned.startsWith("[")) {
            int end = cleaned.lastIndexOf(']');
            if (end > 0) cleaned = cleaned.substring(0, end + 1);
        } else {
            return null;
        }

        List<TrainingEntryRaw> result = new ArrayList<>();
        int objStart = cleaned.indexOf('{');
        while (objStart >= 0) {
            int objEnd = findMatchingBrace(cleaned, objStart);
            if (objEnd < 0) break;
            String obj = cleaned.substring(objStart, objEnd + 1);
            String msg = extractJsonString(obj, "message");
            String cat = extractJsonString(obj, "category");
            if (msg != null && cat != null && !msg.isEmpty() && !cat.isEmpty()) {
                result.add(new TrainingEntryRaw(msg, cat));
            }
            objStart = cleaned.indexOf('{', objEnd + 1);
        }
        return result.isEmpty() ? null : result;
    }

    /** Simple pair for parsed training entries. */
    private record TrainingEntryRaw(String message, String category) {}

    @Override
    public RoutingResult route(String message, List<WorkerInfo> availableWorkers) {
        if (!config.isEnabled()) {
            logger.debug("Local router is disabled");
            return null;
        }

        if (message == null || message.trim().isEmpty()) {
            return null;
        }

        // Periodic full refresh (B strategy): regenerate categories from scratch
        // to clean up stale entries from incremental merges
        routeCount++;
        if (routeCount % FULL_REFRESH_INTERVAL == 0 && llmCaller != null
                && config.isCategoryGenEnabled() && availableWorkers != null
                && !availableWorkers.isEmpty()) {
            triggerFullRefresh(availableWorkers);
        }

        // Classify the message
        HanlpTextClassifier.ClassificationResult result = classifier.classify(message);
        if (result == null) {
            return null;
        }

        // === Self-learning step ===
        // Even if confidence is below threshold, we can learn from partial matches
        if (result.getConfidence() < config.getThreshold() && learner != null
                && result.getMethod().startsWith("keyword")) {

            // Partial match: learn unmatched tokens for the best category
            List<String> tokens = classifier.tokenize(message);
            learner.learnPartialMatch(message, result.getCategory(), tokens, 0);
            logger.debug("Learned from partial match: category={}, confidence={}",
                    result.getCategory().getId(),
                    String.format("%.2f", result.getConfidence()));

            // Flush periodically (on partial match saves are throttled internally)
            keywordStore.save();

            logger.debug("Local router: confidence {} < threshold {}, skipping",
                    String.format("%.2f", result.getConfidence()),
                    String.format("%.2f", config.getThreshold()));
            return null;
        }

        // Confidence >= threshold: route the request
        if (result.getConfidence() < config.getThreshold()) {
            logger.debug("Local router: confidence {} < threshold {}, skipping",
                    String.format("%.2f", result.getConfidence()),
                    String.format("%.2f", config.getThreshold()));
            return null;
        }

        // Success: reinforce matched learned keywords
        if (learner != null) {
            Set<String> matchedLearned = result.getMatchedLearnedKeywords();
            if (matchedLearned != null && !matchedLearned.isEmpty()) {
                learner.reinforceSuccess(matchedLearned);
                keywordStore.save();
            }
        }

        // Build TaskRequirement from classification
        TaskRequirement requirement = buildRequirement(result, availableWorkers);

        String source = "local-router:" + result.getMethod();
        logger.info("Local router routed as '{}' ({} confidence, method={})",
                result.getCategory().getId(), String.format("%.2f", result.getConfidence()), result.getMethod());

        return new RoutingResult(requirement, result.getConfidence(), source);
    }

    /**
     * Build a {@link TaskRequirement} from the classification result,
     * matching the best available worker if possible.
     */
    private TaskRequirement buildRequirement(HanlpTextClassifier.ClassificationResult result,
                                              List<WorkerInfo> availableWorkers) {
        TaskRequirement req = new TaskRequirement();
        CategoryDef category = result.getCategory();

        req.setTaskType(category.getId());
        req.setRequiredCapabilities(category.getCapabilities());
        req.setComplexity(3); // default moderate complexity for local routing
        req.setSensitivity(2);
        req.setExpectedTokens(2000);
        req.setBudgetPriority("balanced");
        req.setFallbackAllowed(true);
        req.setPipelineRecommended(false);
        req.setReasoning("Local router (" + result.getMethod() + "): matched category '" + category.getId()
                + "' with confidence " + String.format("%.2f", result.getConfidence()));

        // Try to match the best available worker
        if (availableWorkers != null && !availableWorkers.isEmpty()) {
            String bestWorker = findBestWorker(category, availableWorkers);
            if (bestWorker != null) {
                req.setSuggestedWorkerId(bestWorker);
            }
        }

        return req;
    }

    /**
     * Simple worker matching: find the worker with the most capability overlap.
     * When overlap is equal, prefers the worker with lower load.
     */
    static String findBestWorker(CategoryDef category, List<WorkerInfo> workers) {
        WorkerInfo best = null;
        int bestOverlap = 0;

        for (WorkerInfo worker : workers) {
            int overlap = 0;
            if (worker.getCapabilities() != null) {
                for (String cap : category.getCapabilities()) {
                    if (worker.getCapabilities().containsKey(cap)) {
                        overlap++;
                    }
                }
            }
            if (best == null || overlap > bestOverlap
                    || (overlap == bestOverlap && compareLoad(worker, best) < 0)) {
                best = worker;
                bestOverlap = overlap;
            }
        }

        return best != null && bestOverlap > 0 ? best.getWorkerId() : null;
    }

    private static double getWorkerLoad(WorkerInfo w) {
        return w.getMetrics() != null ? w.getMetrics().getCurrentLoad() : 1.0;
    }

    private static int compareLoad(WorkerInfo a, WorkerInfo b) {
        return Double.compare(getWorkerLoad(a), getWorkerLoad(b));
    }

    /**
     * Find a category by its ID. Returns null if not found.
     */
    private CategoryDef findCategoryById(String id) {
        if (id == null || categories == null) return null;
        for (CategoryDef cat : categories) {
            if (id.equals(cat.getId())) return cat;
        }
        return null;
    }

    // ========================================================================
    // Dynamic category generation helpers (B + C strategy)
    // ========================================================================

    /**
     * Compute a deterministic checksum from a sorted set of capabilities.
     */
    private static String computeChecksum(Set<String> capabilities) {
        return String.join("|", capabilities);
    }

    /**
     * Extract all unique capability names from workers, deterministically sorted.
     */
    private static Set<String> extractAllCapabilities(List<WorkerInfo> workers) {
        Set<String> caps = new TreeSet<>();
        for (WorkerInfo w : workers) {
            if (w.getCapabilities() != null) {
                caps.addAll(w.getCapabilities().keySet());
            }
        }
        return caps;
    }

    /**
     * Extract all capability names currently covered by existing categories.
     */
    private Set<String> extractExistingCapabilities() {
        Set<String> caps = new HashSet<>();
        for (CategoryDef cat : categories) {
            caps.addAll(cat.getCapabilities());
        }
        return caps;
    }

    /**
     * Replace categories and rebuild the classifier.
     */
    private void updateCategories(List<CategoryDef> newCategories) {
        this.categories = newCategories;
        this.classifier = new HanlpTextClassifier(newCategories, config, learner);
    }

    /**
     * Periodic full refresh: regenerate all categories from current capabilities.
     * Handles capability removals that incremental merge cannot clean up.
     */
    private void triggerFullRefresh(List<WorkerInfo> workers) {
        Set<String> allCaps = extractAllCapabilities(workers);
        String currentChecksum = computeChecksum(allCaps);
        if (currentChecksum.equals(this.categoriesChecksum)) {
            return; // nothing changed since last check
        }
        logger.info("Periodic full category refresh for {} capabilities", allCaps.size());
        List<CategoryDef> refreshed = generateCategoriesViaLLM(allCaps, true);
        if (refreshed != null && !refreshed.isEmpty()) {
            updateCategories(refreshed);
            this.categoriesChecksum = currentChecksum;
            persistCategories(refreshed);
            logger.info("Full category refresh complete: {} categories", refreshed.size());
        }
    }

    // ========================================================================
    // LLM prompt engineering for category generation
    // ========================================================================

    /**
     * Call the LLM to generate routing categories from capability names.
     *
     * @param capabilities set of capability names to categorize
     * @param isFull       true for full generation, false for incremental
     * @return list of generated categories, or null if LLM call failed
     */
    private List<CategoryDef> generateCategoriesViaLLM(Set<String> capabilities,
                                                        boolean isFull) {
        if (llmCaller == null || capabilities == null || capabilities.isEmpty()) {
            return null;
        }

        String prompt = buildCategoryGenPrompt(capabilities, isFull);
        String response;
        try {
            response = llmCaller.apply(prompt);
        } catch (Exception e) {
            logger.warn("LLM category generation failed: {}", e.getMessage());
            return null;
        }

        if (response == null || response.trim().isEmpty()) {
            logger.warn("LLM returned empty response for category generation");
            return null;
        }

        List<CategoryDef> result = parseCategoryResponse(response);
        if (result == null || result.isEmpty()) {
            logger.warn("Failed to parse any categories from LLM response");
        }
        return result;
    }

    /**
     * Build the LLM prompt for category generation.
     */
    private String buildCategoryGenPrompt(Set<String> capabilities, boolean isFull) {
        var existingIds = categories.stream()
                .map(CategoryDef::getId)
                .collect(Collectors.joining(", "));

        var capsStr = capabilities.stream()
                .collect(Collectors.joining(", ", "[", "]"));

        return """
            You are a routing category designer for a worker-based task routing system.

            %s
            Worker capabilities to categorize: %s

            ONLY return a raw JSON array. No markdown, no code fences, no explanation:
            [
              {
                "id": "unique_id",
                "chineseKeywords": ["中文词1", "中文词2", "中文词3"],
                "englishKeywords": ["english", "keywords", "base", "form"],
                "capabilities": ["matching_capability_names"],
                "description": "Short description under 80 chars"
              }
            ]

            Rules:
            - id: lowercase_with_underscores, must be unique
            - chineseKeywords: 3-8 common Chinese search terms users would type for this category
            - englishKeywords: 3-8 common English search terms (base form, lowercase, single words preferred)
            - capabilities: must be a subset of the provided [capabilities] list
            - description: one-line summary, under 80 characters
            - Generate 1 category per 1-2 related capabilities. Do NOT merge unrelated capabilities.
            %s
            """.formatted(
                isFull
                    ? "Create routing categories that group related worker capabilities into logical units.\n"
                    : "Existing routing categories: " + existingIds
                        + "\n\nCreate NEW categories for these additional capabilities only.\n",
                capsStr,
                isFull
                    ? ""
                    : "- Do NOT repeat existing categories. Only create categories for the NEW capabilities listed above.\n"
            ).stripIndent().trim();
    }

    // ========================================================================
    // JSON response parsing (no external deps)
    // ========================================================================

    /**
     * Parse the LLM JSON response into a list of CategoryDef.
     */
    private List<CategoryDef> parseCategoryResponse(String response) {
        // Strip markdown code fences if present
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            int end = cleaned.indexOf('\n');
            if (end > 0) cleaned = cleaned.substring(end + 1);
            int fenceEnd = cleaned.lastIndexOf("```");
            if (fenceEnd >= 0) cleaned = cleaned.substring(0, fenceEnd);
            cleaned = cleaned.trim();
        }

        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end <= start) {
            logger.warn("No JSON array found in LLM response");
            return null;
        }

        String json = cleaned.substring(start, end + 1);
        List<CategoryDef> result = new ArrayList<>();

        int objStart = json.indexOf('{');
        while (objStart >= 0) {
            int objEnd = findMatchingBrace(json, objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            CategoryDef cat = parseSingleCategory(obj);
            if (cat != null) {
                result.add(cat);
            }

            objStart = json.indexOf('{', objEnd + 1);
        }

        return result.isEmpty() ? null : result;
    }

    private static int findMatchingBrace(String json, int openPos) {
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') depth++;
                if (c == '}') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    private static CategoryDef parseSingleCategory(String json) {
        try {
            String id = extractJsonString(json, "id");
            List<String> chineseKeywords = extractJsonArray(json, "chineseKeywords");
            List<String> englishKeywords = extractJsonArray(json, "englishKeywords");
            List<String> capabilities = extractJsonArray(json, "capabilities");
            String description = extractJsonString(json, "description");

            if (id == null || id.isEmpty()) return null;

            return new CategoryDef(id, chineseKeywords, englishKeywords,
                    capabilities, description);
        } catch (Exception e) {
            logger.warn("Failed to parse category from JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) {
            search = "\"" + key + "\": \"";
            start = json.indexOf(search);
        }
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private static List<String> extractJsonArray(String json, String key) {
        List<String> result = new ArrayList<>();
        String search = "\"" + key + "\":[";
        int start = json.indexOf(search);
        if (start < 0) return result;
        start += search.length();
        int end = json.indexOf("]", start);
        if (end < 0) return result;
        String arrayContent = json.substring(start, end);
        Pattern p = Pattern.compile("\"([^\"]+)\"");
        Matcher m = p.matcher(arrayContent);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }

    // ========================================================================
    // Category cache persistence
    // ========================================================================

    /**
     * Persist generated categories to disk for reuse across restarts.
     */
    private void persistCategories(List<CategoryDef> cats) {
        try {
            Path path = Paths.get(config.getCategoryCachePath());
            Files.createDirectories(path.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < cats.size(); i++) {
                CategoryDef cat = cats.get(i);
                sb.append("  {\n");
                sb.append("    \"id\": \"").append(escape(cat.getId())).append("\",\n");
                sb.append("    \"chineseKeywords\": ").append(toJsonArray(cat.getChineseKeywords())).append(",\n");
                sb.append("    \"englishKeywords\": ").append(toJsonArray(cat.getEnglishKeywords())).append(",\n");
                sb.append("    \"capabilities\": ").append(toJsonArray(cat.getCapabilities())).append(",\n");
                sb.append("    \"description\": \"").append(escape(cat.getDescription())).append("\"\n");
                sb.append("  }");
                if (i < cats.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n");
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(tmp, sb.toString().getBytes("UTF-8"));
            Files.move(tmp, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            logger.debug("Persisted {} categories to {}", cats.size(), path);
        } catch (Exception e) {
            logger.warn("Failed to persist categories: {}", e.getMessage());
        }
    }

    /**
     * Load cached categories from disk.
     */
    private List<CategoryDef> loadCachedCategories() {
        try {
            Path path = Paths.get(config.getCategoryCachePath());
            if (!Files.exists(path)) return null;
            String content = new String(Files.readAllBytes(path), "UTF-8");
            return parseCategoryResponse(content);
        } catch (Exception e) {
            logger.debug("No cached categories found: {}", e.getMessage());
            return null;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        return list.stream()
                .map(s -> "\"" + escape(s) + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
