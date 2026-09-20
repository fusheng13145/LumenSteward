package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.compliance.ComplianceReport;
import com.lumensteward.clawbot.application.compliance.ComplianceReportService;
import com.lumensteward.clawbot.interfaces.assembler.ComplianceReportAssembler;
import com.lumensteward.clawbot.interfaces.dto.compliance.ComplianceReportVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 合规报告控制器（B-5 / W4 合规报告自动化）。
 *
 * <p>导出留存 / 删除执行合规报告（Markdown）。仅 {@code SUPER_ADMIN} 可访问（RBAC 后端独立执行，
 * AC-E3/E9）。响应严格镜像 {@link UserController#export} 的下载范式：UTF-8 正文、
 * {@code Content-Disposition: attachment; filename=compliance-report-YYYYMMDD.md}、
 * {@code Content-Type: text/markdown}。报告正文由 {@link ComplianceReportAssembler} 渲染，
 * 其中所有 openid 已脱敏（BR-21）。
 */
@RestController
@RequestMapping("/api/compliance")
@Tag(name = "合规报告", description = "一键导出留存 / 删除执行合规报告（SUPER_ADMIN 独占）")
public class ComplianceController {

    private final ComplianceReportService reportService;
    private final ComplianceReportAssembler assembler;

    /**
     * 构造器注入（G-14）。
     *
     * @param reportService 合规报告服务
     * @param assembler     合规报告装配器（领域 → VO → Markdown）
     */
    public ComplianceController(ComplianceReportService reportService,
                                ComplianceReportAssembler assembler) {
        this.reportService = reportService;
        this.assembler = assembler;
    }

    /**
     * 导出合规报告（Markdown 字节流）。
     *
     * @return Markdown 文件字节流
     */
    @GetMapping("/report")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "导出合规报告", description = "聚合留存/删除执行证据并导出 Markdown（BR-21 脱敏）")
    public ResponseEntity<byte[]> report() {
        ComplianceReport report = reportService.generateReport();
        ComplianceReportVO vo = assembler.toVO(report);
        String markdown = assembler.renderMarkdown(vo);
        byte[] body = markdown.getBytes(StandardCharsets.UTF_8);
        String filename = "compliance-report-"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".md";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .body(body);
    }
}
