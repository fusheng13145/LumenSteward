package com.lumensteward.clawbot.domain.port;

import com.lumensteward.clawbot.domain.port.model.LinkContent;

/**
 * 链接抓取端口（迭代 4 W16；domain 定义、infrastructure 实现，G-14 依赖倒置）。
 *
 * <p>实现须自带安全前置（手册 §7.7 W16）：协议与端口白名单、私网/环回地址拒绝（SSRF）、
 * 跳转次数上限且<b>每跳重验</b>、响应体大小上限、超时。抓取失败以
 * {@link LinkFetchException} 表达，工具层如实回告用户，不编造正文（BR-09）。
 */
public interface LinkFetchPort {

    /**
     * 抓取并提取正文。
     *
     * @param url 页面地址（http/https）
     * @return 标题 + 纯文本正文
     * @throws LinkFetchException 地址不合规、内容类型不支持或网络失败
     */
    LinkContent fetch(String url) throws LinkFetchException;
}
