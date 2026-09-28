package com.lumensteward.clawbot.domain.tool;

/**
 * 工具可见性判定上下文（W10 工具动态可见性 / 轨道 C）。
 *
 * <p>编排器每轮发放前构造一次，随「本用户本链路的已知情境」变化：工具经
 * {@link Tool#visibleIn(ToolVisibilityContext)} 自述出现条件，注册表据此裁剪实际下发给模型的
 * 函数 Schema——把「注册即暴露」升级为「按需暴露」的机制底座（母本 M-2 §03-2 实证教训：
 * 工具多了模型会选错，故 Schema 须随上下文收敛）。
 *
 * <p>字段按首个真实消费方（W11 识图缓存与追问续接）设计；后续情境信号在此扩展，
 * 不在工具侧另立判定入口（与 FR-23「派生口径自述 + 注册表反查」同一格局）。
 *
 * @param openid            当前用户（用于按用户裁剪；可为 null，如脱离会话的干跑）
 * @param imageCachePresent 本用户当前是否存在可追问的识图缓存（W11 识图续接的出现判据；
 *                          W10 阶段编排器恒填 {@code false}，即以它为出现条件的工具本轮不可见）
 */
public record ToolVisibilityContext(String openid, boolean imageCachePresent) {

    /** 最小上下文：仅用户标识，无附加情境信号。 */
    public static ToolVisibilityContext of(String openid) {
        return new ToolVisibilityContext(openid, false);
    }
}
