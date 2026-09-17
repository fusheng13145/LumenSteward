package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.MediaId;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * 真实微信出站通道（架构 5.1 / SRS 9.4.6）。
 *
 * <p>当 {@code wx.mock-enabled=false} 时装配。出站失败统一降级为 {@link SendResult#fail}，
 * 不抛出中断链路（BR-10）；遇平台 token 失效错误码（40001/42001）时清缓存并<b>仅</b>回源重试一次。
 */
@Component
@ConditionalOnProperty(name = "wx.mock-enabled", havingValue = "false")
public class RealWechatTransport implements WechatTransport {

    private static final Logger log = LoggerFactory.getLogger(RealWechatTransport.class);

    public static final String MODE = "real";

    private static final int ERR_TOKEN_INVALID_40001 = 40001;
    private static final int ERR_TOKEN_INVALID_42001 = 42001;

    private final RestClient wechatRestClient;
    private final WechatTokenService tokenService;

    /**
     * 构造器注入（G-14）。
     *
     * @param wechatRestClient 微信出站客户端
     * @param tokenService     token 服务
     */
    public RealWechatTransport(RestClient wechatRestClient, WechatTokenService tokenService) {
        this.wechatRestClient = wechatRestClient;
        this.tokenService = tokenService;
    }

    @Override
    public String transportMode() {
        return MODE;
    }

    @Override
    public SendResult sendCustomerMessage(String openid, CustomerMessage message) {
        long start = System.currentTimeMillis();
        SendResult result = doSend(openid, message, tokenService.getAccessToken());
        if (!result.success() && isTokenInvalid(result.errCode())) {
            log.warn("平台判定 token 失效，清缓存并单次回源重试: errCode={}", result.errCode());
            result = doSend(openid, message, tokenService.evictAndRefresh());
        }
        long latency = System.currentTimeMillis() - start;
        log.info("出站客服消息 send_status={}({}) openid={} latish={}ms",
                result.success() ? 0 : 1, result.success() ? "成功" : "失败",
                MaskUtils.openid(openid), latency);
        return new SendResult(result.success(), result.errCode(), result.errMsg(), latency);
    }

    private SendResult doSend(String openid, CustomerMessage message, String token) {
        if (token == null || token.isBlank()) {
            return SendResult.fail(-1, "access_token 不可用", 0L);
        }
        ObjectNode body = JsonUtils.mapper().createObjectNode();
        body.put("touser", openid);
        body.put("msgtype", message.msgType());
        if (CustomerMessage.TYPE_TEXT.equals(message.msgType())) {
            body.putObject("text").put("content", message.content());
        } else if (CustomerMessage.TYPE_VOICE.equals(message.msgType())) {
            body.putObject("voice").put("media_id", message.mediaId());
        }
        try {
            String raw = wechatRestClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/message/custom/send")
                            .queryParam("access_token", token).build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
            JsonNode root = JsonUtils.readTree(raw);
            int errCode = root == null ? -1 : root.path("errcode").asInt(0);
            if (errCode == 0) {
                return SendResult.ok(0L);
            }
            return SendResult.fail(errCode, root.path("errmsg").asText(null), 0L);
        } catch (RuntimeException e) {
            log.warn("客服消息发送异常: {}", e.getMessage());
            return SendResult.fail(-1, "发送异常", 0L);
        }
    }

    @Override
    public MediaId uploadTempMedia(byte[] bytes, String type, String filename) {
        String token = tokenService.getAccessToken();
        if (token == null || bytes == null) {
            return null;
        }
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return filename == null ? "upload.bin" : filename;
                }
            };
            form.add("media", resource);
            String raw = wechatRestClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/media/upload")
                            .queryParam("access_token", token)
                            .queryParam("type", type).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode root = JsonUtils.readTree(raw);
            if (root == null || !root.hasNonNull("media_id")) {
                return null;
            }
            Long createdAt = root.hasNonNull("created_at") ? root.get("created_at").asLong() : null;
            return new MediaId(root.get("media_id").asText(), type, createdAt);
        } catch (RuntimeException e) {
            log.warn("上传素材异常: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public byte[] downloadMedia(String mediaId) {
        String token = tokenService.getAccessToken();
        if (token == null) {
            return new byte[0];
        }
        try {
            byte[] body = wechatRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/media/get")
                            .queryParam("access_token", token)
                            .queryParam("media_id", mediaId).build())
                    .retrieve()
                    .body(byte[].class);
            return body == null ? new byte[0] : body;
        } catch (RuntimeException e) {
            log.warn("下载素材异常: {}", e.getMessage());
            return new byte[0];
        }
    }

    private static boolean isTokenInvalid(int errCode) {
        return errCode == ERR_TOKEN_INVALID_40001 || errCode == ERR_TOKEN_INVALID_42001;
    }
}
