package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.infrastructure.bootstrap.StartupDoctor;
import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DependencyCheck;
import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DoctorReport;
import com.lumensteward.clawbot.interfaces.dto.doctor.DoctorReportVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统自检端点（SUP-05 / A-1 / AC-D4，架构 4.3）。
 *
 * <p>{@code GET /api/doctor} 返回结构化体检报告（DB/Redis/微信/LLM/TTS/物流/地图：
 * 连通性 + 模式 + 配置校验结论）。体检过程不抛异常，故本端点永不为 5xx。
 *
 * <p><b>T05 变更：</b>已纳入认证区（任意已认证角色可用），与 {@code SecurityConfig} 收紧一致。
 */
@RestController
@RequestMapping("/api/doctor")
@Tag(name = "系统自检", description = "Startup Doctor 结构化体检报告")
public class DoctorController {

    private final StartupDoctor startupDoctor;

    /**
     * 构造器注入（G-14）。
     *
     * @param startupDoctor 启动自检服务
     */
    public DoctorController(StartupDoctor startupDoctor) {
        this.startupDoctor = startupDoctor;
    }

    /**
     * 执行一次体检并返回报告。
     *
     * @return 统一响应体包裹的体检报告
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "结构化体检报告", description = "逐项返回各依赖的连通性、运行模式与配置校验结论")
    public ApiResponse<DoctorReportVO> diagnose() {
        DoctorReport report = startupDoctor.diagnose();
        List<DoctorReportVO.Item> items = report.items() == null ? List.of()
                : report.items().stream().map(DoctorController::toItem).toList();
        return ApiResponse.success(new DoctorReportVO(report.overall(), report.checkedAt(), items));
    }

    private static DoctorReportVO.Item toItem(DependencyCheck check) {
        return new DoctorReportVO.Item(check.name(), check.connectivity(), check.mode(),
                check.configConclusion(), check.detail());
    }
}
