package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.UserAdminService;
import com.lumensteward.clawbot.application.admin.UserQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.user.UserDetailVO;
import com.lumensteward.clawbot.interfaces.dto.user.UserProfileUpdateRequest;
import com.lumensteward.clawbot.interfaces.dto.user.UserQuery;
import com.lumensteward.clawbot.interfaces.dto.user.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 用户管理控制器（架构 4.3 / SRS 3.3 权限矩阵）。
 *
 * <p>读接口对全部角色开放；启用/禁用与导出仅 SUPER_ADMIN（RBAC 独立在后端执行，AC-E3/E9）。
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理", description = "用户列表 / 详情 / 启停 / 导出（只读 + SUPER_ADMIN 写）")
public class UserController {

    private final UserQueryService userQueryService;
    private final UserAdminService userAdminService;
    private final MaskingAssembler maskingAssembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param userQueryService 用户查询服务（读）
     * @param userAdminService 用户写服务（启停 / 档案维护，FR-16 T11）
     * @param maskingAssembler 脱敏装配器
     */
    public UserController(UserQueryService userQueryService,
                          UserAdminService userAdminService,
                          MaskingAssembler maskingAssembler) {
        this.userQueryService = userQueryService;
        this.userAdminService = userAdminService;
        this.maskingAssembler = maskingAssembler;
    }

    /**
     * 分页查询用户。
     *
     * @param query 查询条件
     * @return 分页视图
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "用户列表", description = "支持 keyword/status/startTime/endTime 过滤，全局唯一分页")
    public ApiResponse<PageResult<UserVO>> list(@Valid UserQuery query) {
        PageResult<WxUserEntity> page = userQueryService.page(query, query.getKeyword(),
                query.getStatus(), query.getStartTime(), query.getEndTime());
        return ApiResponse.success(maskingAssembler.assemblePage(page, maskingAssembler::toUserVO));
    }

    /**
     * 用户详情（含档案数/会话数/工具调用数）。
     *
     * @param id 用户主键
     * @return 详情视图
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "用户详情")
    public ApiResponse<UserDetailVO> detail(@PathVariable Long id) {
        WxUserEntity user = userQueryService.requireById(id);
        long petCount = userQueryService.countPets(user.getOpenid());
        long sessionCount = userQueryService.countSessions(user.getOpenid());
        long toolCallCount = userQueryService.countToolCalls(user.getOpenid());
        return ApiResponse.success(
                maskingAssembler.toUserDetailVO(user, petCount, sessionCount, toolCallCount));
    }

    /**
     * 启用/禁用用户（SUPER_ADMIN 独占）。
     *
     * @param id        用户主键
     * @param body      请求体（status=1/0）
     * @param principal 当前主体
     * @return 空响应
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "启用/禁用用户", description = "仅 SUPER_ADMIN；变更写入 log_audit")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                          @RequestBody Map<String, Integer> body,
                                          @AuthenticationPrincipal AuthPrincipal principal,
                                          HttpServletRequest httpRequest) {
        Integer status = body == null ? null : body.get("status");
        userAdminService.updateStatus(id, status,
                principal == null ? null : principal.adminId(), ClientIp.of(httpRequest));
        return ApiResponse.success();
    }

    /**
     * 维护用户档案（昵称，FR-16 AC③ 前后值留痕）。
     *
     * @param id        用户主键
     * @param request   档案更新请求
     * @param principal 当前主体
     * @return 空响应
     */
    @PutMapping("/{id}/profile")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR')")
    @Operation(summary = "维护用户档案", description = "昵称维护；变更前后值写入 log_audit")
    public ApiResponse<Void> updateProfile(@PathVariable Long id,
                                           @Valid @RequestBody UserProfileUpdateRequest request,
                                           @AuthenticationPrincipal AuthPrincipal principal,
                                           HttpServletRequest httpRequest) {
        userAdminService.updateProfile(id, request == null ? null : request.nickname(),
                principal == null ? null : principal.adminId(), ClientIp.of(httpRequest),
                request == null ? null : request.reason());
        return ApiResponse.success();
    }

    /**
     * 导出用户 CSV（脱敏，SUPER_ADMIN 独占）。
     *
     * @param query 查询条件（复用列表过滤）
     * @return CSV 文件
     */
    @GetMapping("/export")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "导出 CSV", description = "openid 经脱敏后导出（BR-21）")
    public ResponseEntity<byte[]> export(@Valid UserQuery query) {
        List<WxUserEntity> users = userQueryService.listForExport(query.getKeyword(),
                query.getStatus(), query.getStartTime(), query.getEndTime());
        StringBuilder csv = new StringBuilder();
        csv.append("id,openid,nickname,status,lastInteractAt,createdAt\n");
        for (WxUserEntity user : users) {
            csv.append(csvCell(user.getId() == null ? null : String.valueOf(user.getId()))).append(',')
                    .append(csvCell(MaskUtils.openid(user.getOpenid()))).append(',')
                    .append(csvCell(user.getNickname())).append(',')
                    .append(csvCell(user.getStatus() == null ? null : String.valueOf(user.getStatus()))).append(',')
                    .append(csvCell(user.getLastInteractAt() == null ? null : user.getLastInteractAt().toString())).append(',')
                    .append(csvCell(user.getCreatedAt() == null ? null : user.getCreatedAt().toString()))
                    .append('\n');
        }
        // UTF-8 BOM 使 Excel 正确识别中文
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = csv.toString().getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, payload, 0, bom.length);
        System.arraycopy(body, 0, payload, bom.length, body.length);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=users.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(payload);
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
