package com.lumensteward.clawbot.application.gray;

import com.lumensteward.clawbot.application.config.ConfigKeys;

/**
 * 受灰度开关治理的功能清单（FR-22 / 迭代 4 W2）。
 *
 * <p>用枚举而非运行时注册表：灰度功能是<b>需要评审才能新增</b>的运维面（新增即意味着
 * 熔断回滚会覆盖它），编译期常量比插件式注册更合适。
 *
 * <p>灰度<b>叠加</b>在功能自身的主开关之上：主开关关闭时灰度不参与判断，
 * 因此新增灰度项不会改变既有配置的行为（比例默认 100 = 与灰度上线前完全一致）。
 *
 * @param code          灰度代号（同时是哈希盐值，改动即改变全部用户的分流结果）
 * @param label         中文名称（后台展示）
 * @param percentKey    比例配置键（0~100）
 * @param whitelistKey  白名单配置键（逗号分隔 openid）
 */
public enum GrayFeature {

    /** 个人状态库自动生长（W6；每轮多一次模型调用，适合按用户小步放量）。 */
    MEMORY_GROWTH("memory_growth", "个人状态库生长",
            ConfigKeys.GRAY_MEMORY_GROWTH_PERCENT, ConfigKeys.GRAY_MEMORY_GROWTH_WHITELIST);

    private final String code;
    private final String label;
    private final String percentKey;
    private final String whitelistKey;

    GrayFeature(String code, String label, String percentKey, String whitelistKey) {
        this.code = code;
        this.label = label;
        this.percentKey = percentKey;
        this.whitelistKey = whitelistKey;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public String percentKey() {
        return percentKey;
    }

    public String whitelistKey() {
        return whitelistKey;
    }

    /**
     * 按代号取灰度功能。
     *
     * @param code 代号（忽略大小写与首尾空白）
     * @return 功能；不存在返回 null（由调用方给可读错误，不抛裸异常）
     */
    public static GrayFeature fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (GrayFeature feature : values()) {
            if (feature.code.equalsIgnoreCase(normalized)) {
                return feature;
            }
        }
        return null;
    }
}
