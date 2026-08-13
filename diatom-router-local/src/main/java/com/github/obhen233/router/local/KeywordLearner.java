package com.github.obhen233.router.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Self-supervised keyword learning engine.
 * <p>
 * Learns new keywords from partial matches and reinforces existing learned keywords
 * on successful routes. Uses stop word filtering to avoid polluting the keyword set
 * with common words.
 */
public class KeywordLearner {

    private static final Logger logger = LoggerFactory.getLogger(KeywordLearner.class);

    private final KeywordStore store;
    private final boolean enabled;

    // Stop words — common English and Chinese words unlikely to be meaningful keywords
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            // English stop words
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "could",
            "should", "may", "might", "can", "shall", "dare", "ought",
            "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "as", "into", "through", "during", "before", "after", "above", "below",
            "between", "out", "off", "over", "under", "again", "further", "then",
            "once", "here", "there", "when", "where", "why", "how", "all", "each",
            "every", "both", "few", "more", "most", "other", "some", "such", "no",
            "nor", "not", "only", "own", "same", "so", "than", "too", "very",
            "just", "because", "but", "and", "or", "if", "while", "although",
            "this", "that", "these", "those", "it", "its", "i", "you", "he", "she",
            "we", "they", "me", "him", "her", "us", "them", "my", "your", "his",
            "its", "our", "their", "mine", "yours", "hers", "ours", "theirs",
            "please", "help", "want", "like", "make", "get", "use",
            "tell", "ask", "try", "leave", "come", "go", "give", "take",
            "put", "set", "let", "keep", "find", "show", "know", "see",
            "think", "say", "about", "up", "down", "also", "well",
            // Chinese stop words
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一",
            "个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那",
            "什么", "怎么", "如何", "哪些", "谁",
            "把", "被", "让", "给", "对", "从", "向", "于", "与",
            "但", "而", "或", "因为", "所以", "如果", "虽然", "不过",
            "可以", "能够", "应该", "必须", "需要", "可能",
            "已经", "正在", "将要", "曾经", "刚刚", "马上",
            "还", "再", "又", "才", "就", "便", "都", "只",
            "做", "成为", "作为", "当",
            "大", "小", "多", "少", "高", "低", "长", "短",
            "新", "旧", "好", "坏", "快", "慢",
            // Very short tokens unlikely to be meaningful
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
            "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z"
    ));

    public KeywordLearner(KeywordStore store, boolean enabled) {
        this.store = store;
        this.enabled = enabled;
    }

    /**
     * Learn from a partial keyword match.
     * <p>
     * When {@code keywordMatch} finds some affinity (score > 0) but confidence
     * is below threshold, the unmatched tokens are potential keywords for the
     * best-matching category.
     *
     * @param message     the original user message
     * @param bestCategory the best-matching category
     * @param tokens      the tokenized message (from HanLP)
     * @param builtinMatchCount number of built-in keyword matches
     */
    public void learnPartialMatch(String message, CategoryDef bestCategory,
                                   List<String> tokens, double builtinMatchCount) {
        if (!enabled) return;
        if (tokens == null || tokens.isEmpty()) return;

        Set<String> builtinKeywords = collectBuiltinKeywords(bestCategory);
        Set<String> learnedTexts = new HashSet<>();

        for (String token : tokens) {
            String normalized = token.toLowerCase(Locale.ROOT).trim();

            // Skip stop words
            if (isStopWord(normalized)) continue;

            // Skip very short tokens — single characters (Chinese or Latin) are
            // never meaningful keywords. This is the key guard against char-level
            // learning noise: the tokenizer may still emit single CJK chars when
            // a word is unknown to its dictionary, and we must not learn them.
            if (normalized.length() < 2) continue;

            // Skip tokens that are already built-in keywords for this category
            if (builtinKeywords.contains(normalized)) continue;

            // Skip tokens that already exist in the store
            if (store.contains(normalized)) continue;

            // Avoid duplicates within the same message
            if (!learnedTexts.add(normalized)) continue;

            store.learn(normalized);
            logger.debug("Learned new keyword '{}' for category '{}'", normalized, bestCategory.getId());
        }
    }

    /**
     * Learn keywords from LLM-confirmed classification feedback.
     * <p>
     * Unlike {@link #learnPartialMatch}, this is called when the LLM has
     * already confirmed the correct category. New keywords get a higher
     * initial frequency (3 vs 1) reflecting the higher confidence of the signal.
     * Also reinforces existing learned keywords found in the message.
     *
     * @param message  the original user message
     * @param category the category the LLM assigned to this message
     * @param tokens   the tokenized message (from HanLP)
     */
    public void learnFromFeedback(String message, CategoryDef category, List<String> tokens) {
        if (!enabled) return;
        if (tokens == null || tokens.isEmpty()) return;

        Set<String> builtinKeywords = collectBuiltinKeywords(category);
        Set<String> learnedTexts = new HashSet<>();

        for (String token : tokens) {
            String normalized = token.toLowerCase(Locale.ROOT).trim();

            // Skip stop words
            if (isStopWord(normalized)) continue;

            // Skip very short tokens — single characters are never meaningful keywords
            if (normalized.length() < 2) continue;

            // Skip tokens that are already built-in keywords for this category
            if (builtinKeywords.contains(normalized)) continue;

            // Avoid duplicates within the same message
            if (!learnedTexts.add(normalized)) continue;

            // LLM feedback: initial frequency = 3 (higher confidence than partial match)
            // If keyword already exists, reinforce by 3
            store.learn(normalized, 3);
            logger.debug("Learned keyword '{}' from LLM feedback for category '{}'",
                    normalized, category.getId());
        }
    }

    /**
     * Reinforce learned keywords that matched in a successful route.
     *
     * @param matchedLearnedKeywords set of learned keyword texts that matched
     */
    public void reinforceSuccess(Set<String> matchedLearnedKeywords) {
        if (!enabled) return;
        if (matchedLearnedKeywords == null || matchedLearnedKeywords.isEmpty()) return;

        for (String text : matchedLearnedKeywords) {
            KeywordEntry entry = store.get(text);
            if (entry != null) {
                entry.reinforce();
                logger.debug("Reinforced keyword '{}' (frequency now {})", text, entry.getFrequency());
            }
        }
    }

    /**
     * Get the total learned keyword contribution to confidence for a set of tokens.
     *
     * @param tokenLowerSet lowercased token set
     * @param matchedLearnedKeywords output set to populate with matched learned keyword texts
     * @return total learned weight contribution
     */
    public double getLearnedContribution(Set<String> tokenLowerSet,
                                          Set<String> matchedLearnedKeywords) {
        if (!enabled) return 0.0;
        double contribution = 0.0;
        for (KeywordEntry entry : store.allKeywords()) {
            if (tokenLowerSet.contains(entry.getText())) {
                contribution += entry.getWeight();
                if (matchedLearnedKeywords != null) {
                    matchedLearnedKeywords.add(entry.getText());
                }
            }
        }
        return contribution;
    }

    /**
     * All learned keyword texts (normalized) currently in the store.
     * <p>
     * Exposed so the classifier can build its Chinese segmentation dictionary
     * from learned keywords — this lets the tokenizer keep learned multi-char
     * Chinese words (e.g. "冒泡") as single tokens, which in turn lets
     * {@link #getLearnedContribution} match them against the token set.
     *
     * @return set of normalized learned keyword texts (never null)
     */
    public Set<String> allKeywordTexts() {
        if (store == null) {
            return Collections.emptySet();
        }
        Set<String> texts = new HashSet<>();
        for (KeywordEntry entry : store.allKeywords()) {
            texts.add(entry.getText());
        }
        return texts;
    }

    /**
     * Flush the keyword store to disk.
     */
    public void flush() {
        store.flush();
    }

    // ========== Internal ==========

    private Set<String> collectBuiltinKeywords(CategoryDef category) {
        Set<String> keywords = new HashSet<>();
        for (String kw : category.getChineseKeywords()) {
            keywords.add(kw.toLowerCase(Locale.ROOT));
        }
        for (String kw : category.getEnglishKeywords()) {
            keywords.add(kw.toLowerCase(Locale.ROOT));
        }
        return keywords;
    }

    static boolean isStopWord(String token) {
        return STOP_WORDS.contains(token);
    }

    static boolean isChinese(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) {
                return true;
            }
        }
        return false;
    }
}
