package com.github.obhen233.router.local;

import com.github.obhen233.router.config.LocalRouterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.BreakIterator;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Two-stage text classifier for routing user requests.
 * <p>
 * <b>Stage 1: Keyword Matching (fast path)</b><br>
 * Tokenizes input using JDK {@link BreakIterator} with dictionary-based
 * maximal-munch merging of Chinese characters into known words (built-in
 * category keywords plus self-learned keywords), then matches tokens against
 * predefined keyword lists per category. Confidence is computed as the ratio
 * of matched keywords to total unique keywords in the best-matching category.
 * <p>
 * <b>Stage 2: TF-IDF + Cosine Similarity (fallback)</b><br>
 * If keyword matching produces low confidence, a TF-IDF vector is computed
 * from the message and compared against pre-computed category centroids using
 * cosine similarity. Zero external ML dependencies — pure JDK implementation.
 */
public class HanlpTextClassifier {

    private static final Logger logger = LoggerFactory.getLogger(HanlpTextClassifier.class);

    private final List<CategoryDef> categories;
    private final LocalRouterConfig config;
    private final KeywordLearner learner;

    // Pre-computed TF-IDF centroids for each category (Stage 2 fallback)
    private final Map<CategoryDef, Map<String, Double>> centroids;
    private final Map<String, Double> idf;
    private final Set<String> vocabulary;

    public HanlpTextClassifier(List<CategoryDef> categories, LocalRouterConfig config) {
        this(categories, config, null);
    }

    public HanlpTextClassifier(List<CategoryDef> categories, LocalRouterConfig config,
                                KeywordLearner learner) {
        this.categories = categories != null ? categories : CategoryDef.defaults();
        this.config = config;
        this.learner = learner;

        // Pre-compute TF-IDF centroids from category keywords
        this.centroids = new HashMap<>();
        this.idf = new HashMap<>();
        this.vocabulary = new HashSet<>();
        computeTfIdfCentroids();
    }

    /**
     * Classify a message into a routing category.
     *
     * @param message the user input text
     * @return classification result with the best-matching category and confidence,
     *         or {@code null} if no category meets the minimum confidence threshold
     */
    public ClassificationResult classify(String message) {
        if (message == null || message.trim().isEmpty()) {
            return null;
        }

        // Stage 1: Keyword matching (fast path)
        ClassificationResult keywordResult = keywordMatch(message);
        if (keywordResult != null) {
            return keywordResult;
        }

        // Stage 2: TF-IDF + Cosine Similarity (fallback)
        return tfidfClassify(message);
    }

    /**
     * Stage 1: Fast keyword matching using JDK BreakIterator tokenization.
     * <p>
     * Tokenizes the input, then for each category computes a score based on
     * how many of its keywords appear in the token set (or as substrings).
     * The highest-scoring category is returned if its confidence meets the threshold.
     */
    ClassificationResult keywordMatch(String message) {
        // Tokenize with BreakIterator
        List<String> tokens = tokenize(message);
        if (tokens.isEmpty()) {
            return null;
        }

        // Normalize tokens to lowercase for English matching
        Set<String> tokenSet = new HashSet<>();
        Set<String> tokenLowerSet = new HashSet<>();
        for (String token : tokens) {
            tokenSet.add(token);
            tokenLowerSet.add(token.toLowerCase(Locale.ROOT));
        }

        CategoryDef bestCategory = null;
        double bestTotalScore = 0.0;
        double bestBuiltinMatchCount = 0.0;
        double bestLearnedContribution = 0.0;
        Set<String> bestMatchedLearned = new HashSet<>();

        for (CategoryDef cat : categories) {
            double matchCount = 0;

            // Match Chinese keywords (exact match or substring)
            // Note: With BreakIterator's character-level tokenization for Chinese,
            // multi-character keywords won't appear as exact tokens. We give full
            // credit when a 2+ char keyword is clearly present as a substring.
            for (String kw : cat.getChineseKeywords()) {
                if (tokenSet.contains(kw)) {
                    matchCount++;
                } else if (message.contains(kw)) {
                    matchCount += kw.length() >= 2 ? 1.0 : 0.5;
                }
            }

            // Match English keywords (case-insensitive, with stem/prefix matching)
            String messageLower = message.toLowerCase(Locale.ROOT);
            for (String kw : cat.getEnglishKeywords()) {
                String kwLower = kw.toLowerCase(Locale.ROOT);
                boolean matched = false;

                // Check if the keyword appears as a token (exact)
                if (tokenLowerSet.contains(kwLower)) {
                    matchCount++;
                    matched = true;
                    continue;
                }

                // Check multi-word keywords as substring (e.g., "unit test" in "unit tests")
                if (kwLower.contains(" ") && messageLower.contains(kwLower)) {
                    matchCount++;
                    matched = true;
                    continue;
                }

                // Check stem/prefix matching (e.g., "test" matches "tests", "testing")
                for (String token : tokenLowerSet) {
                    if (token.startsWith(kwLower) || kwLower.startsWith(token)) {
                        if (token.length() >= 3 && kwLower.length() >= 3) {
                            matchCount++;
                            matched = true;
                            break;
                        }
                    }
                }

                // Final fallback: substring check in original message
                if (!matched && messageLower.contains(kwLower)) {
                    matchCount += 0.5;
                }
            }

            if (matchCount > 0) {
                // Include learned keyword contribution
                Set<String> matchedLearned = new HashSet<>();
                double learnedContribution = learner != null
                        ? learner.getLearnedContribution(tokenLowerSet, matchedLearned)
                        : 0.0;
                double totalScore = matchCount + learnedContribution;

                if (totalScore > bestTotalScore) {
                    bestTotalScore = totalScore;
                    bestBuiltinMatchCount = matchCount;
                    bestCategory = cat;
                    bestLearnedContribution = learnedContribution;
                    bestMatchedLearned = matchedLearned;
                }
            }
        }

        if (bestCategory != null && bestTotalScore > 0) {
            // Built-in keywords contribute 0.35 each; learned keywords contribute
            // their dynamic weight (0.2 - 0.6)
            double builtinConfidence = Math.min(1.0, bestBuiltinMatchCount * 0.35);
            double confidence = Math.min(1.0, builtinConfidence + bestLearnedContribution);
            String method = bestLearnedContribution > 0 ? "keyword+learned" : "keyword";
            logger.debug("Keyword match: category={}, confidence={}, builtin={}, learned={}",
                    bestCategory.getId(), String.format("%.2f", confidence),
                    String.format("%.2f", builtinConfidence),
                    String.format("%.2f", bestLearnedContribution));
            return new ClassificationResult(bestCategory, confidence, method, bestMatchedLearned);
        }

        return null;
    }

    /**
     * Stage 2: TF-IDF + Cosine Similarity classification (fallback).
     * <p>
     * Computes a TF-IDF vector from the message and compares it against
     * pre-computed category centroids using cosine similarity. The closest
     * category is returned if similarity exceeds the confidence threshold.
     */
    ClassificationResult tfidfClassify(String message) {
        if (vocabulary.isEmpty() || centroids.isEmpty()) {
            return null;
        }

        List<String> tokens = tokenize(message);
        if (tokens.isEmpty()) {
            return null;
        }

        // Compute TF vector for the message
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokens) {
            tf.merge(token, 1, Integer::sum);
        }
        double totalTokens = tokens.size();

        // Build TF-IDF vector for the message (only vocabulary terms)
        Map<String, Double> messageVector = new HashMap<>();
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            String term = e.getKey();
            double termTf = e.getValue() / totalTokens;

            // Try exact match against vocabulary
            if (vocabulary.contains(term)) {
                double tfidf = termTf * idf.getOrDefault(term, 1.0);
                messageVector.merge(term, tfidf, Double::sum);
                continue;
            }

            // For unmatched English tokens, try lowercase
            String termLower = term.toLowerCase(Locale.ROOT);
            if (!termLower.equals(term) && vocabulary.contains(termLower)) {
                double tfidf = termTf * idf.getOrDefault(termLower, 1.0);
                messageVector.merge(termLower, tfidf, Double::sum);
                continue;
            }

            // For Chinese text (BreakIterator produces character-level tokens),
            // check if any vocabulary term is a substring of multi-char token
            // or if the token matches a multi-char vocabulary term as substring
            for (String vocabTerm : vocabulary) {
                if (term.contains(vocabTerm) || vocabTerm.contains(term)) {
                    if (vocabTerm.length() >= 2 || term.length() >= 2) {
                        double tfidf = termTf * idf.getOrDefault(vocabTerm, 1.0);
                        messageVector.merge(vocabTerm, tfidf * 0.5, Double::sum);
                    }
                }
            }
        }

        // Normalize message vector to unit length
        normalizeVector(messageVector);
        if (messageVector.isEmpty()) {
            return null;
        }

        // Find closest category by cosine similarity
        CategoryDef bestCategory = null;
        double bestSimilarity = 0.0;

        for (Map.Entry<CategoryDef, Map<String, Double>> entry : centroids.entrySet()) {
            double similarity = cosineSimilarity(messageVector, entry.getValue());
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity;
                bestCategory = entry.getKey();
            }
        }

        if (bestCategory != null && bestSimilarity > 0) {
            // Scale TF-IDF similarity to a confidence-like score (0.0 - 1.0)
            // TF-IDF cosine similarities tend to be lower, so we scale them
            double confidence = Math.min(1.0, bestSimilarity * 2.5);
            logger.debug("TF-IDF classify: category={}, similarity={}, confidence={}",
                    bestCategory.getId(), String.format("%.4f", bestSimilarity),
                    String.format("%.2f", confidence));
            return new ClassificationResult(bestCategory, confidence, "tfidf");
        }

        return null;
    }

    // ========== TF-IDF helpers ==========

    /**
     * Pre-compute IDF values and category centroids from all categories' keywords.
     * <p>
     * Vocabulary: all unique keywords across all categories.
     * IDF: inverse document frequency = log(1 + N / (1 + df)), where N = category count.
     * Centroid: for each category, map of keyword → IDF weight, normalized to unit vector.
     */
    private void computeTfIdfCentroids() {
        if (categories == null || categories.isEmpty()) return;

        // Step 1: Build vocabulary and document frequency
        Map<String, Integer> docFreq = new HashMap<>();
        for (CategoryDef cat : categories) {
            Set<String> uniqueTerms = new HashSet<>();
            for (String kw : cat.getChineseKeywords()) {
                uniqueTerms.add(kw);
            }
            for (String kw : cat.getEnglishKeywords()) {
                uniqueTerms.add(kw.toLowerCase(Locale.ROOT));
            }
            for (String term : uniqueTerms) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }

        vocabulary.addAll(docFreq.keySet());
        int N = categories.size();

        // Step 2: Compute IDF for each term
        for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
            double idfValue = Math.log(1.0 + (double) N / (1 + e.getValue()));
            idf.put(e.getKey(), idfValue);
        }

        // Step 3: Compute centroids
        for (CategoryDef cat : categories) {
            Map<String, Double> centroid = new HashMap<>();
            for (String kw : cat.getChineseKeywords()) {
                centroid.merge(kw, idf.getOrDefault(kw, 1.0), Double::sum);
            }
            for (String kw : cat.getEnglishKeywords()) {
                String kwLower = kw.toLowerCase(Locale.ROOT);
                centroid.merge(kwLower, idf.getOrDefault(kwLower, 1.0), Double::sum);
            }
            normalizeVector(centroid);
            centroids.put(cat, centroid);
        }
    }

    /**
     * Normalize a vector to unit length (L2 norm).
     */
    private static void normalizeVector(Map<String, Double> vector) {
        double magnitude = 0.0;
        for (double value : vector.values()) {
            magnitude += value * value;
        }
        magnitude = Math.sqrt(magnitude);
        if (magnitude > 0) {
            for (Map.Entry<String, Double> e : vector.entrySet()) {
                e.setValue(e.getValue() / magnitude);
            }
        }
    }

    /**
     * Compute cosine similarity between two vectors.
     */
    private static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
        double dotProduct = 0.0;
        // Iterate over the smaller map for efficiency
        Map<String, Double> smaller = a.size() <= b.size() ? a : b;
        Map<String, Double> larger = a.size() <= b.size() ? b : a;
        for (Map.Entry<String, Double> e : smaller.entrySet()) {
            Double otherValue = larger.get(e.getKey());
            if (otherValue != null) {
                dotProduct += e.getValue() * otherValue;
            }
        }
        return dotProduct; // Both vectors are unit length, so denominator is 1
    }

    // ========== Tokenization ==========

    /**
     * Tokenize input text using JDK built-in {@link BreakIterator} plus
     * dictionary-based Chinese word merging.
     * <p>
     * Zero external dependencies. BreakIterator is used to get raw Unicode
     * word tokens; consecutive Chinese characters are then merged into
     * multi-character words via <em>maximal-munch</em> against a dictionary
     * built from built-in category keywords and self-learned keywords.
     * <p>
     * This keeps known Chinese words (e.g. "单元测试") as single tokens instead
     * of individual characters, which removes the char-level noise that used
     * to pollute self-learning. Falls back to whitespace/punctuation split if
     * tokenization produces no tokens.
     */
    List<String> tokenize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            List<String> raw = breakIteratorTokens(text);
            if (raw.isEmpty()) {
                // Fallback: simple whitespace and punctuation split
                return Arrays.asList(text.split("[\\s,.;:!?，。；：！？、]+"));
            }
            return mergeCjkWords(raw);
        } catch (Exception e) {
            logger.warn("BreakIterator tokenization failed, falling back to whitespace split: {}", e.getMessage());
            // Fallback: simple whitespace and punctuation split
            return Arrays.asList(text.split("[\\s,.;:!?，。；：！？、]+"));
        }
    }

    /**
     * Raw Unicode word segmentation via {@link BreakIterator}.
     */
    private static List<String> breakIteratorTokens(String text) {
        List<String> tokens = new ArrayList<>();
        BreakIterator bi = BreakIterator.getWordInstance(Locale.CHINESE);
        bi.setText(text);
        int start = bi.first();
        for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
            String word = text.substring(start, end).trim();
            if (!word.isEmpty()) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /**
     * Merge runs of consecutive Chinese characters into known multi-char words.
     * <p>
     * BreakIterator produces one token per Chinese character (char-level).
     * We buffer consecutive CJK tokens and re-segment the run with
     * <em>maximal-munch</em> against the dictionary. Latin/number tokens pass
     * through unchanged.
     */
    private List<String> mergeCjkWords(List<String> tokens) {
        Set<String> dict = buildDictionary();
        List<String> merged = new ArrayList<>();
        StringBuilder cjkRun = new StringBuilder();

        for (String token : tokens) {
            if (isAllCjk(token)) {
                cjkRun.append(token);
            } else {
                if (cjkRun.length() > 0) {
                    appendCjkSegments(merged, cjkRun.toString(), dict);
                    cjkRun.setLength(0);
                }
                merged.add(token);
            }
        }
        if (cjkRun.length() > 0) {
            appendCjkSegments(merged, cjkRun.toString(), dict);
        }
        return merged;
    }

    /**
     * Maximal-munch segmentation of a pure-CJK run using the given dictionary.
     * Unknown characters fall back to single-char tokens (which the learner
     * ignores via its length &gt;= 2 filter).
     */
    private static void appendCjkSegments(List<String> out, String cjk, Set<String> dict) {
        int i = 0;
        int n = cjk.length();
        while (i < n) {
            String match = longestDictionaryMatch(cjk, i, dict);
            if (match != null) {
                out.add(match);
                i += match.length();
            } else {
                out.add(String.valueOf(cjk.charAt(i)));
                i++;
            }
        }
    }

    /**
     * Build the Chinese segmentation dictionary.
     * <p>
     * Contains every multi-char Chinese keyword from the built-in categories
     * plus every Chinese keyword learned so far. Single-char entries are
     * excluded — they don't help segmentation and would reintroduce noise.
     */
    private Set<String> buildDictionary() {
        Set<String> dict = new HashSet<>();
        if (categories != null) {
            for (CategoryDef cat : categories) {
                for (String kw : cat.getChineseKeywords()) {
                    if (kw != null && kw.length() >= 2) {
                        dict.add(kw);
                    }
                }
            }
        }
        if (learner != null) {
            for (String kw : learner.allKeywordTexts()) {
                if (kw != null && kw.length() >= 2 && isAllCjk(kw)) {
                    dict.add(kw);
                }
            }
        }
        return dict;
    }

    /**
     * Longest dictionary entry matching at position {@code i} (case-insensitive),
     * or {@code null} if none matches.
     */
    private static String longestDictionaryMatch(String text, int i, Set<String> dict) {
        String best = null;
        int bestLen = 0;
        for (String word : dict) {
            int len = word.length();
            if (len <= bestLen) {
                continue; // only consider longer candidates
            }
            if (i + len <= text.length() && text.regionMatches(true, i, word, 0, len)) {
                best = word;
                bestLen = len;
            }
        }
        return best;
    }

    /**
     * True if every character of {@code s} is a CJK ideograph.
     */
    private static boolean isAllCjk(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            boolean cjk = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
            if (!cjk) {
                return false;
            }
        }
        return true;
    }

    // ========== Classification Result ==========

    /**
     * Result of a classification attempt.
     */
    public static class ClassificationResult {
        private final CategoryDef category;
        private final double confidence;
        private final String method; // "keyword", "keyword+learned", or "tfidf"
        private final Set<String> matchedLearnedKeywords;

        public ClassificationResult(CategoryDef category, double confidence, String method) {
            this(category, confidence, method, Collections.emptySet());
        }

        public ClassificationResult(CategoryDef category, double confidence, String method,
                                     Set<String> matchedLearnedKeywords) {
            this.category = category;
            this.confidence = confidence;
            this.method = method;
            this.matchedLearnedKeywords = matchedLearnedKeywords != null
                    ? Collections.unmodifiableSet(new HashSet<>(matchedLearnedKeywords))
                    : Collections.emptySet();
        }

        public CategoryDef getCategory() {
            return category;
        }

        public double getConfidence() {
            return confidence;
        }

        public String getMethod() {
            return method;
        }

        public Set<String> getMatchedLearnedKeywords() {
            return matchedLearnedKeywords;
        }
    }
}
