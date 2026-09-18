package com.lumensteward.clawbot.domain.port;

/**
 * 视觉端口异常（domain 层，脱离基础设施异常体系）。
 *
 * <p>由 {@code LlmVisionAdapter} 在捕获 {@code LlmException} 时转译，避免 domain 反向依赖 infra。
 */
public class VisionPortException extends RuntimeException {

    public VisionPortException(String message) {
        super(message);
    }

    public VisionPortException(String message, Throwable cause) {
        super(message, cause);
    }
}
