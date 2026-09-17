package com.lumensteward.clawbot.infrastructure.client.map.model;

import java.util.Optional;

/**
 * 出行方式（SRS FR-13 / 附录 B-4，线值 driving/walking/riding/transit）。
 */
public enum RouteMode {

    /** 驾车。 */
    DRIVING("driving"),
    /** 步行。 */
    WALKING("walking"),
    /** 骑行。 */
    RIDING("riding"),
    /** 公交/地铁。 */
    TRANSIT("transit");

    private final String wire;

    RouteMode(String wire) {
        this.wire = wire;
    }

    /** 线值。 */
    public String wire() {
        return wire;
    }

    /**
     * 按线值解析（未知回退驾车）。
     *
     * @param wire 线值
     * @return 出行方式
     */
    public static RouteMode fromWire(String wire) {
        return Optional.ofNullable(wire)
                .flatMap(w -> {
                    for (RouteMode mode : values()) {
                        if (mode.wire.equalsIgnoreCase(w)) {
                            return Optional.of(mode);
                        }
                    }
                    return Optional.empty();
                })
                .orElse(DRIVING);
    }
}
