package com.github.obhen233.starter.mode;

/**
 * Mode 解析工具类。
 *
 * <p>处理 {@code diatam.mode} 配置值的解析，支持 {@code mode:subtype} 格式。
 * 例如 {@code gateway:nacos} → base mode = {@link DiatomMode#GATEWAY}，subtype = {@code "nacos"}。</p>
 */
public class ModeUtils {

    /** mode 配置前缀 */
    public static final String MODE_PROPERTY_PREFIX = "diatom";

    /** mode 配置项名 */
    public static final String MODE_PROPERTY_NAME = "diatom.mode";

    private ModeUtils() {}

    /**
     * 解析 base mode（忽略子类型）。
     */
    public static DiatomMode parseMode(String modeStr) {
        return DiatomMode.fromValue(modeStr);
    }

    /**
     * 解析子类型。
     * "gateway:nacos" → "nacos"
     * "standard" → null
     */
    public static String parseSubType(String modeStr) {
        if (modeStr == null || !modeStr.contains(":")) return null;
        return modeStr.split(":", 2)[1].trim();
    }

    /**
     * 判断 mode 值是否匹配指定模式（忽略子类型）。
     * "gateway:nacos" 匹配 GATEWAY → true
     */
    public static boolean isMode(String modeStr, DiatomMode mode) {
        return parseMode(modeStr) == mode;
    }

    /**
     * 生成 {@code @ConditionalOnProperty} 的 {@code havingValue} 参数。
     * 注意：Spring 的 {@code @ConditionalOnProperty} 不支持 {@code mode:subtype} 格式，
     * 因此条件判断应只匹配 base mode。
     */
    public static String conditionalValue(DiatomMode mode) {
        return mode.getValue();
    }
}
