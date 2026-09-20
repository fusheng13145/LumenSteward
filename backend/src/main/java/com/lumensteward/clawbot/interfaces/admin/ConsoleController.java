package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.console.ConsoleEvent;
import com.lumensteward.clawbot.application.console.ConsoleEventBuffer;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 控制台实时事件流控制器（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * <p>暴露 {@code GET /api/sse/console}（{@code text/event-stream}），供管理后台实时观测编排链路。
 * 鉴权由 {@code JwtAuthenticationFilter}（支持 {@code Authorization} 头与 {@code ?token=} 回落）
 * 与 {@code @PreAuthorize} 完成，本控制器<b>不重复解析</b> JWT。BR-11：该通道为只读观测，不承载业务写入。
 *
 * <p>CORS/OPTIONS：沿用 {@code SecurityConfig} 的 STATELESS 链与 {@code WebMvcConfig} 的 MVC CORS；
 * SSE 为简单 GET，无需预检。SseEmitter 超时设 {@code Long.MAX_VALUE}，由 onCompletion/onError/onTimeout
 * 回调在连接关闭时注销（避免泄漏）。
 */
@RestController
@RequestMapping("/api/sse")
public class ConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);

    private final ConsoleEventBuffer consoleEventBuffer;

    public ConsoleController(ConsoleEventBuffer consoleEventBuffer) {
        this.consoleEventBuffer = consoleEventBuffer;
    }

    /**
     * 订阅控制台事件流。
     *
     * <p>支持续传：浏览器原生重连携带 {@code Last-Event-ID} 请求头；本组件手动重连携带
     * {@code ?lastEventId=} 查询参数。两者取其最大值作为续传起点。
     *
     * @param principal    当前管理员（鉴权后非空）
     * @param lastEventId  续传起点（查询参数，可空）
     * @param request      HTTP 请求（读取 Last-Event-ID 头）
     * @return SSE 发射器
     */
    @GetMapping("/console")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    public SseEmitter subscribe(@AuthenticationPrincipal AuthPrincipal principal,
                                @RequestParam(value = "lastEventId", required = false) Long lastEventId,
                                HttpServletRequest request) {
        long from = lastEventId == null ? 0L : Math.max(0L, lastEventId);
        String headerId = request.getHeader("Last-Event-ID");
        if (from == 0L && headerId != null && !headerId.isBlank()) {
            try {
                from = Math.max(0L, Long.parseLong(headerId.trim()));
            } catch (NumberFormatException ignore) {
                // 非法序号忽略，从头开始
            }
        }

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitter.onCompletion(() -> consoleEventBuffer.unregister(emitter));
        emitter.onTimeout(() -> consoleEventBuffer.unregister(emitter));
        emitter.onError((Throwable ex) -> consoleEventBuffer.unregister(emitter));

        // 先快照续传窗口，再注册，避免重复投递（订阅瞬间窗口内的单条事件可能被漏，观测通道可接受）
        List<ConsoleEvent> backlog = consoleEventBuffer.replay(from);
        consoleEventBuffer.register(emitter);
        for (ConsoleEvent event : backlog) {
            try {
                emitter.send(ConsoleEventBuffer.toSseEvent(event));
            } catch (Exception ex) {
                consoleEventBuffer.unregister(emitter);
                break;
            }
        }
        log.info("控制台 SSE 连接建立 principal={} from={} active={}",
                principal == null ? "?" : principal.username(), from, consoleEventBuffer.activeConnections());
        return emitter;
    }
}
