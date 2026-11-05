package com.lumensteward.clawbot.domain.tool;

/**
 * 工具可见性判定上下文（W10 工具动态可见性 / 轨道 C）。
 *
 * <p>编排器每轮发放前构造一次，随「本用户本链路的已知情境」变化：工具经
 * {@link Tool#visibleIn(ToolVisibilityContext)} 自述出现条件，注册表据此裁剪实际下发给模型的
 * 函数 Schema——把「注册即暴露」升级为「按需暴露」的机制底座（母本 M-2 §03-2 实证教训：
 * 工具多了模型会选错，故 Schema 须随上下文收敛）。
 *
 * <p>字段按真实消费方逐步扩展（不在工具侧另立判定入口，与 FR-23「派生口径自述 +
 * 注册表反查」同一格局）：W11 识图缓存（{@code imageCachePresent}）→ W19 桌游进行中局
 * （{@code activeGame}，会话门控——3 个桌游工具仅在对应玩法进行中可见，使 D2 的
 * P1「桌游 ≥3 类」与 P2「每轮下发 Schema ≤8」同时成立）。
 *
 * @param openid            当前用户（用于按用户裁剪；可为 null，如脱离会话的干跑）
 * @param imageCachePresent 本用户当前是否存在可追问的识图缓存（W11 识图续接的出现判据；
 *                          编排器每轮按 {@code RecentImageStore.find(openid)} 实时填充，
 *                          存储不可用按 false，即追问工具本轮不可见）
 * @param activeGame        本用户当前进行中桌游的工具名（如 {@code turtle_soup}；W19 桌游的
 *                          会话门控判据；编排器每轮按 {@code GameStore.find(openid)} 实时填充，
 *                          存储不可用或无进行中局为 null）。刻意用 String 而非玩法枚举，
 *                          保持 domain/tool 与 domain/game 两包解耦
 */
public record ToolVisibilityContext(String openid, boolean imageCachePresent, String activeGame) {

    /**
     * 兼容构造（W10/W11 形态）：无桌游情境信号，等同 {@code activeGame} 为 null。
     *
     * @param openid            当前用户
     * @param imageCachePresent 是否存在可追问的识图缓存
     */
    public ToolVisibilityContext(String openid, boolean imageCachePresent) {
        this(openid, imageCachePresent, null);
    }

    /** 最小上下文：仅用户标识，无附加情境信号。 */
    public static ToolVisibilityContext of(String openid) {
        return new ToolVisibilityContext(openid, false, null);
    }
}
