package com.lumensteward.clawbot.common.enums;

import java.util.Locale;

/**
 * 个人状态库条目类型（迭代 4 W6 / §2.19）。
 *
 * <p>枚举名即落库值（{@code biz_memory_item.kind}）。取值刻意收窄为「人 / 地 / 物 / 偏好 / 惯例 /
 * 事实」六类，对应 §2.19 的个人知识分类；<b>不含</b>承诺（COMMITMENT）——其闭环依赖主动触达通道
 * （W7/W8 阻塞项），只存不动会形成空头承诺，故随 W8 一并引入。
 */
public enum MemoryKind {

    /** 人物：家庭成员、朋友、同事等及其关系。 */
    PERSON,

    /** 地点：常去地、住址、公司及其语义。 */
    PLACE,

    /** 物品：宠物、设备、钥匙等具体物件。 */
    THING,

    /** 偏好：饮食、作息、沟通方式等倾向。 */
    PREFERENCE,

    /** 惯例：周期性行为与习惯。 */
    HABIT,

    /** 事实：不属于上述五类的离散事实。 */
    FACT;

    /** 入库值（列宽须容纳最长枚举名）。 */
    public String value() {
        return name();
    }

    /**
     * 宽松解析模型输出的类型串（大小写 / 空格 / 中文别名容错）。
     *
     * @param raw 原始串
     * @return 匹配的类型；无法识别时返回 {@code null}，由调用方回落 {@link #FACT}
     */
    public static MemoryKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim().toUpperCase(Locale.ROOT);
        for (MemoryKind kind : values()) {
            if (kind.name().equals(key)) {
                return kind;
            }
        }
        return switch (key) {
            case "PEOPLE", "PERSONS", "HUMAN" -> PERSON;
            case "PREF", "LIKES", "DISLIKES" -> PREFERENCE;
            case "ROUTINE", "HABITS" -> HABIT;
            case "LOCATION", "ADDR", "ADDRESSES" -> PLACE;
            default -> null;
        };
    }
}
