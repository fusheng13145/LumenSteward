package com.lumensteward.clawbot.application.task;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于规则的槽位填充器（SRS FR-24）。
 *
 * <p>以确定性的轻量规则从追问回复中抽取槽位，<b>不调用 LLM</b>，从而满足「优先槽位填充而非全量
 * 意图重识别」的要求，并保证可测试性。覆盖常用槽位：
 * <ul>
 *   <li>物流单号类（{@code tracking_no}/{@code waybill*}）：匹配 {@code 字母前缀 + 数字}（如 {@code SF1234567890}）；</li>
 *   <li>手机号类（{@code phone}/{@code mobile*}）：匹配 11 位数字；</li>
 *   <li>其余通用槽位（如 {@code pet_name}）：在消息不像社交寒暄/问句时，整条消息视为取值。</li>
 * </ul>
 * 社交寒暄（"你好""在吗""谢谢"等）一律视为<b>未填充</b>，以便上层累计无效输入并在达阈值后放弃（BR-33）。
 */
@Component
public class RuleBasedSlotFiller implements SlotFiller {

    /** 物流单号：可选字母前缀 + 至少 5 位数字（SF/ZTO/YT/YD/EMS/JD 等均可）。 */
    private static final Pattern TRACKING_NO = Pattern.compile("[A-Za-z]{0,6}\\d{5,}");

    /** 手机号：11 位数字。 */
    private static final Pattern PHONE = Pattern.compile("\\d{11}");

    /** 社交寒暄/无信息内容（视为未填充）。 */
    private static final Set<String> SOCIAL_UTTERANCES = Set.of(
            "你好", "您好", "在吗", "在么", "嗯", "嗯嗯", "哦", "谢谢", "感谢",
            "哈哈", "嘿嘿", "哈喽", "喂", "hi", "hello", "?");

    @Override
    public Optional<Map<String, String>> fill(List<String> pendingSlots, String userMessage) {
        if (pendingSlots == null || pendingSlots.isEmpty()) {
            return Optional.empty();
        }
        String message = userMessage == null ? "" : userMessage.trim();
        if (message.isEmpty() || isSocial(message)) {
            return Optional.empty();
        }
        for (String slot : pendingSlots) {
            String value = extract(slot, message);
            if (value != null && !value.isBlank()) {
                Map<String, String> filled = new LinkedHashMap<>();
                filled.put(slot, value);
                return Optional.of(filled);
            }
        }
        return Optional.empty();
    }

    private static boolean isSocial(String message) {
        String normalized = message.trim().toLowerCase();
        return SOCIAL_UTTERANCES.contains(normalized);
    }

    private static String extract(String slot, String message) {
        if (slot == null) {
            return null;
        }
        String lower = slot.toLowerCase();
        if (lower.contains("tracking") || lower.contains("waybill") || lower.contains("运单")) {
            return match(TRACKING_NO, message, true);
        }
        if (lower.contains("phone") || lower.contains("mobile") || lower.contains("手机")) {
            return match(PHONE, message, false);
        }
        // 通用槽位：整条消息作为取值（去除常见前缀标签与标点，剔除以问号结尾的问句）
        String cleaned = message
                .replaceFirst("^(运单号|单号|单号是|我叫|名字是|名字叫|是|叫)[:：\\s]*", "")
                .replaceAll("[。！，,]+$", "")
                .trim();
        if (cleaned.isEmpty() || cleaned.length() > 64 || cleaned.endsWith("?") || cleaned.endsWith("？")) {
            return null;
        }
        return cleaned;
    }

    private static String match(Pattern pattern, String message, boolean upperCase) {
        Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            String value = matcher.group();
            return upperCase ? value.toUpperCase() : value;
        }
        return null;
    }
}
