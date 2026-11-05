package com.lumensteward.clawbot.domain.port;

/**
 * 链接抓取异常（迭代 4 W16）：SSRF 拒绝、协议/内容类型不支持、网络失败等统一入口，
 * 由工具层转结构化失败（BR-09 不编造正文）。
 */
public class LinkFetchException extends Exception {

    public LinkFetchException(String message) {
        super(message);
    }

    public LinkFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
