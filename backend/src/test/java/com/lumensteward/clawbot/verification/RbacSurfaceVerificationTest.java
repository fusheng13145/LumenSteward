package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.infrastructure.config.SecurityConfig;
import com.lumensteward.clawbot.interfaces.admin.AuditLogController;
import com.lumensteward.clawbot.interfaces.admin.ConfigController;
import com.lumensteward.clawbot.interfaces.admin.DashboardController;
import com.lumensteward.clawbot.interfaces.admin.DoctorController;
import com.lumensteward.clawbot.interfaces.admin.GrayController;
import com.lumensteward.clawbot.interfaces.admin.MemoryController;
import com.lumensteward.clawbot.interfaces.admin.PetController;
import com.lumensteward.clawbot.interfaces.admin.SessionController;
import com.lumensteward.clawbot.interfaces.admin.ToolLogController;
import com.lumensteward.clawbot.interfaces.admin.UserController;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证：RBAC 表面 —— 后台写接口是否有 {@code @PreAuthorize} 兜底，
 * 以及哪些端点被 {@code SecurityConfig} 放行（AC-E2 / AC-E3 / AC-E9）。
 *
 * <p>方法：反射扫描控制器方法，凡非公开前缀下的 POST/PUT/DELETE 必须带 {@code @PreAuthorize}，
 * 否则上报为"未加保护的后台端点"。这是"安静失效"类缺陷的静态排查。
 */
class RbacSurfaceVerificationTest {

    /** 非 /api 后台域：微信回调与登录/登出/改密（由 anyRequest().authenticated() 统一兜底）。 */
    private static final List<Class<?>> CONTROLLERS = List.of(
            ConfigController.class, UserController.class, PetController.class,
            SessionController.class, ToolLogController.class, DashboardController.class,
            AuditLogController.class, DoctorController.class, WechatCallbackController.class,
            MemoryController.class, GrayController.class);

    private static String basePath(Class<?> type) {
        RequestMapping mapping = type.getAnnotation(RequestMapping.class);
        if (mapping == null || mapping.value().length == 0) {
            return "";
        }
        return mapping.value()[0];
    }

    private static boolean isWrite(Method method) {
        return method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }

    private static boolean isPublicPrefix(String basePath) {
        return basePath.startsWith("/api/auth") || basePath.startsWith("/api/wx");
    }

    @Test
    @DisplayName("AC-E3/E9：后台写接口（POST/PUT/DELETE）必须声明 @PreAuthorize，不得存在未加保护的写端点")
    void everyAdminWriteEndpointIsGuarded() {
        List<String> unguarded = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            String base = basePath(controller);
            if (isPublicPrefix(base)) {
                continue;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (isWrite(method) && method.getAnnotation(PreAuthorize.class) == null) {
                    unguarded.add(controller.getSimpleName() + "#" + method.getName()
                            + " (" + base + ")");
                }
            }
        }
        assertThat(unguarded).as("发现未加保护的后台写端点：%s", unguarded).isEmpty();
    }

    @Test
    @DisplayName("AC-E3：配置写接口仅 SUPER_ADMIN（OPERATOR 调配置写应为 403）")
    void configEndpointsRequireSuperAdmin() {
        for (Method method : ConfigController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
            if (preAuthorize != null) {
                assertThat(preAuthorize.value())
                        .as("ConfigController#%s 应仅 SUPER_ADMIN", method.getName())
                        .contains("SUPER_ADMIN");
                assertThat(preAuthorize.value()).doesNotContain("OPERATOR").doesNotContain("AUDITOR");
            }
        }
    }

    @Test
    @DisplayName("AC-E9：AUDITOR 不得拥有写接口（档案写仅 SUPER_ADMIN/OPERATOR）")
    void auditorHasNoWriteAccess() {
        assertThat(annotationOf(PetController.class, "create")).contains("OPERATOR").doesNotContain("AUDITOR");
        assertThat(annotationOf(PetController.class, "update")).contains("OPERATOR").doesNotContain("AUDITOR");
        assertThat(annotationOf(PetController.class, "delete")).contains("OPERATOR").doesNotContain("AUDITOR");
        assertThat(annotationOf(UserController.class, "updateStatus")).contains("SUPER_ADMIN");
    }

    @Test
    @DisplayName("AC-E9：AUDITOR 的只读接口开放（用户/会话/工具日志/审计/看板）")
    void auditorReadEndpointsOpen() {
        assertThat(annotationOf(UserController.class, "list")).contains("AUDITOR");
        assertThat(annotationOf(SessionController.class, "list")).contains("AUDITOR");
        assertThat(annotationOf(ToolLogController.class, "list")).contains("AUDITOR");
        assertThat(annotationOf(DashboardController.class, "summary")).contains("AUDITOR");
        assertThat(annotationOf(AuditLogController.class, "list")).contains("AUDITOR");
    }

    @Test
    @DisplayName("SecurityConfig 放行清单不得包含后台管理端点（configs/users/pets/sessions/tool-logs/dashboard/audit）")
    void publicEndpointsDoNotLeakAdminApis() throws Exception {
        Field field = SecurityConfig.class.getDeclaredField("PUBLIC_ENDPOINTS");
        field.setAccessible(true);
        String[] endpoints = (String[]) field.get(null);

        assertThat(endpoints).isNotEmpty();
        for (String endpoint : endpoints) {
            assertThat(endpoint).doesNotContain("/api/configs")
                    .doesNotContain("/api/users")
                    .doesNotContain("/api/pets")
                    .doesNotContain("/api/sessions")
                    .doesNotContain("/api/tool-logs")
                    .doesNotContain("/api/dashboard")
                    .doesNotContain("/api/audit-logs")
                    .doesNotContain("/api/doctor");
        }
    }

    private static String annotationOf(Class<?> controller, String methodName) {
        for (Method method : controller.getDeclaredMethods()) {
            PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);
            if (method.getName().equals(methodName) && preAuthorize != null) {
                return preAuthorize.value();
            }
        }
        return "<未声明 @PreAuthorize>";
    }
}
