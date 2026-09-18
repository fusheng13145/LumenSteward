package com.lumensteward.clawbot.domain.port.model;

import java.util.Optional;

/**
 * 出行方式（领域端口 DTO，上提自 {@code infrastructure/client/map/model}）。
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
