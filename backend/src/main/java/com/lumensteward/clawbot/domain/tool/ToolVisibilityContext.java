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
 * （{@code activeGame}，会话门控）→ W16 本轮含链接（{@code linkPresent}，当轮情境）。
 * <b>P2 预算收口规则（同轮互斥）：</b>「本轮情境」类工具（{@code read_link}）与
 * 「缓存延续」类工具（{@code ask_image}）至多出现一个——本轮含链接时 read_link 优先、
 * ask_image 让位（由 ask_image.visibleIn 自述此让位），使最坏情形恒为
 * 5 基础 + 大厅 + 1 局内 + 1 情境 = 8，广度扩张不突破 P2「每轮下发 Schema ≤8」。
 *
 * @param openid            当前用户（用于按用户裁剪；可为 null，如脱离会话的干跑）
 * @param imageCachePresent 本用户当前是否存在可追问的识图缓存（W11 识图续接的出现判据；
 *                          编排器每轮按 {@code RecentImageStore.find(openid)} 实时填充，
 *                          存储不可用按 false，即追问工具本轮不可见）
 * @param activeGame        本用户当前进行中桌游的工具名（如 {@code turtle_soup}；W19 桌游的
 *                          会话门控判据；编排器每轮按 {@code GameStore.find(openid)} 实时填充，
 *                          存储不可用或无进行中局为 null）。刻意用 String 而非玩法枚举，
 *                          保持 domain/tool 与 domain/game 两包解耦
 * @param linkPresent       本轮用户消息是否含链接（W16 链接读取的出现判据；编排器对当轮
 *                          userMessage 做 URL 探测实时填充）
 */
public record ToolVisibilityContext(String openid, boolean imageCachePresent, String activeGame,
                                    boolean linkPresent) {

    /**
     * 兼容构造（W10/W11/W19 形态）：无链接情境信号。
     *
     * @param openid            当前用户
     * @param imageCachePresent 是否存在可追问的识图缓存
     * @param activeGame        进行中桌游工具名（可 null）
     */
    public ToolVisibilityContext(String openid, boolean imageCachePresent, String activeGame) {
        this(openid, imageCachePresent, activeGame, false);
    }

    /**
     * 兼容构造（W10/W11 形态）：无桌游/链接情境信号。
     *
     * @param openid            当前用户
     * @param imageCachePresent 是否存在可追问的识图缓存
     */
    public ToolVisibilityContext(String openid, boolean imageCachePresent) {
        this(openid, imageCachePresent, null, false);
    }

    /** 最小上下文：仅用户标识，无附加情境信号。 */
    public static ToolVisibilityContext of(String openid) {
        return new ToolVisibilityContext(openid, false, null, false);
    }
}
