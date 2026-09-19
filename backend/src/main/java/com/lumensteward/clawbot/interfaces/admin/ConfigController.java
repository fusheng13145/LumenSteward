package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.ConfigAdminService;
import com.lumensteward.clawbot.application.admin.ConfigQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.audit.AuditLogVO;
import com.lumensteward.clawbot.interfaces.dto.config.ConfigUpdateRequest;
import com.lumensteward.clawbot.interfaces.dto.config.ConfigVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 系统配置控制器（架构 4.3 / SRS 3.3：SUPER_ADMIN 独占）。
 *
 * <p><b>迭代 2 T10：</b>写路径改由 {@link ConfigAdminService} 承担——按值类型校验非法值（AC④）、
 * 落库后失效缓存使配置<b>免重启生效</b>（AC①）；SECRET 出参仅尾号（AC②）、前后值留痕（AC③）。
 * 响应在列表前置 {@code runtime.*} 运行模式项，标识当前 Mock/Real 通道（AC-D3）。
 */
@RestController
@RequestMapping("/api/configs")
@Tag(name = "系统配置", description = "配置展示 / 批量更新 / 恢复默认 / 变更历史（SUPER_ADMIN）")
public class ConfigController {

    private final ConfigQueryService configQueryService;
    private final ConfigAdminService configAdminService;
    private final MaskingAssembler maskingAssembler;
    private final LlmProperties llmProperties;
    private final WechatProperties wechatProperties;

    /**
     * 构造器注入（G-14）。
     *
     * @param configQueryService 配置查询服务（读）
     * @param configAdminService 配置写服务（校验 / 落库 / 热生效 / 审计）
     * @param maskingAssembler   脱敏装配器
     * @param llmProperties      LLM 配置（运行模式标识）
     * @param wechatProperties   微信通道配置（运行模式标识）
     */
    public ConfigController(ConfigQueryService configQueryService,
                            ConfigAdminService configAdminService,
                            MaskingAssembler maskingAssembler,
                            LlmProperties llmProperties,
                            WechatProperties wechatProperties) {
        this.configQueryService = configQueryService;
        this.configAdminService = configAdminService;
        this.maskingAssembler = maskingAssembler;
        this.llmProperties = llmProperties;
        this.wechatProperties = wechatProperties;
    }

    /**
     * 配置列表（SECRET 仅返回尾号）。
     *
     * @return 配置视图列表（含运行模式项）
     */
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "配置列表", description = "SECRET 值仅返回尾号；含 Mock/Real 运行模式标识")
    public ApiResponse<List<ConfigVO>> list() {
        List<ConfigVO> result = new ArrayList<>();
        result.add(maskingAssembler.runtimeConfig("runtime.llm.provider",
                llmProperties.provider(),
                "LLM 运行模式（mock/real，由 llm.provider 决定，切换零代码改动）"));
        result.add(maskingAssembler.runtimeConfig("runtime.wx.mock.enabled",
                String.valueOf(wechatProperties.mockEnabled()),
                "微信通道运行模式（true=Mock / false=Real，由 wx.mock.enabled 决定）"));
        for (SysConfigEntity entity : configQueryService.list()) {
            result.add(maskingAssembler.toConfigVO(entity));
        }
        return ApiResponse.success(result);
    }

    /**
     * 批量更新配置（须 reason，BR-25）。
     *
     * @param request   更新请求
     * @param principal 当前主体
     * @return 空响应
     */
    @PutMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "批量更新配置", description = "校验后落库并即时生效（免重启）；非法值被拒（FR-18 AC①④）")
    public ApiResponse<Void> update(@Valid @RequestBody ConfigUpdateRequest request,
                                    @AuthenticationPrincipal AuthPrincipal principal,
                                    HttpServletRequest httpRequest) {
        List<ConfigAdminService.ConfigItem> items = request.items().stream()
                .map(item -> new ConfigAdminService.ConfigItem(item.configKey(), item.configValue()))
                .toList();
        configAdminService.update(items, request.reason(),
                principal == null ? null : principal.adminId(), ClientIp.of(httpRequest));
        return ApiResponse.success();
    }

    /**
     * 恢复默认值。
     *
     * @param key       配置键
     * @param principal 当前主体
     * @return 空响应
     */
    @PostMapping("/{key}/reset")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "恢复默认值")
    public ApiResponse<Void> reset(@PathVariable String key,
                                   @AuthenticationPrincipal AuthPrincipal principal,
                                   HttpServletRequest httpRequest) {
        configAdminService.reset(key, principal == null ? null : principal.adminId(),
                ClientIp.of(httpRequest));
        return ApiResponse.success();
    }

    /**
     * 配置变更历史。
     *
     * @param key 配置键
     * @return 审计记录列表
     */
    @GetMapping("/{key}/history")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "变更历史")
    public ApiResponse<List<AuditLogVO>> history(@PathVariable String key) {
        return ApiResponse.success(maskingAssembler.mapList(configQueryService.history(key),
                maskingAssembler::toAuditLogVO));
    }
}
