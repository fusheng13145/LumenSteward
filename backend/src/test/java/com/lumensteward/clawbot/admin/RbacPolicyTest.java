package com.lumensteward.clawbot.admin;

import com.lumensteward.clawbot.interfaces.admin.AuditLogController;
import com.lumensteward.clawbot.interfaces.admin.ConfigController;
import com.lumensteward.clawbot.interfaces.admin.DashboardController;
import com.lumensteward.clawbot.interfaces.admin.PetController;
import com.lumensteward.clawbot.interfaces.admin.ToolLogController;
import com.lumensteward.clawbot.interfaces.admin.UserController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 后台 RBAC 策略单测（SRS 3.3 权限矩阵 / AC-E3 / AC-E9）。
 *
 * <p>以后端「方法级 {@code @PreAuthorize}」为唯一事实来源做静态校验——防止出现
 * 「前端有守卫、后端无鉴权」的假安全。断言面向角色集合，而非字符串位置。
 */
class RbacPolicyTest {

    private static final List<Class<? extends Annotation>> MAPPINGS = List.of(
            GetMapping.class, PostMapping.class, PutMapping.class, DeleteMapping.class);

    /** 取出某控制器全部被 @PreAuthorize 保护的处理器方法（含注解值）。 */
    private static List<Method> protectedHandlers(Class<?> controller) {
        List<Method> result = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (isHandler(method) && method.isAnnotationPresent(PreAuthorize.class)) {
                result.add(method);
            }
        }
        return result;
    }

    private static boolean isHandler(Method method) {
        for (Class<? extends Annotation> mapping : MAPPINGS) {
            if (method.isAnnotationPresent(mapping)) {
                return true;
            }
        }
        return false;
    }

    private static String expression(Method method) {
        return Optional.ofNullable(method.getAnnotation(PreAuthorize.class))
                .map(PreAuthorize::value).orElse("");
    }

    @Test
    @DisplayName("AC-E3：所有后台写/读接口均在方法级声明 @PreAuthorize（后端独立鉴权）")
    void everyAdminHandlerShouldDeclarePreAuthorize() {
        List<Class<?>> controllers = List.of(UserController.class, PetController.class,
                ConfigController.class, AuditLogController.class, ToolLogController.class,
                DashboardController.class);

        for (Class<?> controller : controllers) {
            List<Method> handlers = new ArrayList<>();
            for (Method method : controller.getDeclaredMethods()) {
                if (isHandler(method)) {
                    handlers.add(method);
                }
            }
            assertThat(handlers).as("%s 应有处理器方法", controller.getSimpleName()).isNotEmpty();
            assertThat(protectedHandlers(controller))
                    .as("%s 的每个处理器方法都应声明 @PreAuthorize", controller.getSimpleName())
                    .hasSameSizeAs(handlers);
        }
    }

    @Test
    @DisplayName("AC-E3：配置管理为 SUPER_ADMIN 独占，OPERATOR/AUDITOR 一律不可访问")
    void configEndpointsShouldBeSuperAdminOnly() {
        List<Method> handlers = protectedHandlers(ConfigController.class);

        assertThat(handlers).isNotEmpty();
        for (Method method : handlers) {
            String expr = expression(method);
            assertThat(expr).as("配置接口 %s 仅 SUPER_ADMIN", method.getName())
                    .contains("SUPER_ADMIN")
                    .doesNotContain("OPERATOR")
                    .doesNotContain("AUDITOR");
        }
    }

    @Test
    @DisplayName("AC-E9：AUDITOR 只读——审计日志可读但 OPERATOR 不可读，写接口无 AUDITOR")
    void auditorShouldBeReadOnly() {
        List<Method> auditHandlers = protectedHandlers(AuditLogController.class);
        assertThat(auditHandlers).isNotEmpty();
        for (Method method : auditHandlers) {
            assertThat(expression(method)).contains("AUDITOR").doesNotContain("OPERATOR");
        }

        // 写接口（宠物增改删、用户启停、配置写）不得包含 AUDITOR
        List<Method> writeHandlers = new ArrayList<>();
        writeHandlers.addAll(protectedHandlers(PetController.class).stream()
                .filter(m -> m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class)).toList());
        writeHandlers.addAll(protectedHandlers(UserController.class).stream()
                .filter(m -> m.isAnnotationPresent(PutMapping.class)).toList());
        writeHandlers.addAll(protectedHandlers(ConfigController.class).stream()
                .filter(m -> m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(PostMapping.class)).toList());

        assertThat(writeHandlers).isNotEmpty();
        for (Method method : writeHandlers) {
            assertThat(expression(method)).as("写接口 %s 不得授权 AUDITOR", method.getName())
                    .doesNotContain("AUDITOR");
        }
    }

    @Test
    @DisplayName("OPERATOR 可写档案但不可写配置/不可禁用用户")
    void operatorScopeShouldBePetProfileOnly() {
        Method configUpdate = protectedHandlers(ConfigController.class).stream()
                .filter(m -> m.isAnnotationPresent(PutMapping.class)).findFirst().orElseThrow();
        assertThat(expression(configUpdate)).doesNotContain("OPERATOR");

        Method userStatus = protectedHandlers(UserController.class).stream()
                .filter(m -> m.isAnnotationPresent(PutMapping.class)).findFirst().orElseThrow();
        assertThat(expression(userStatus)).doesNotContain("OPERATOR");

        List<Method> petWrites = protectedHandlers(PetController.class).stream()
                .filter(m -> m.isAnnotationPresent(PostMapping.class)
                        || m.isAnnotationPresent(PutMapping.class)
                        || m.isAnnotationPresent(DeleteMapping.class)).toList();
        assertThat(petWrites).isNotEmpty();
        for (Method method : petWrites) {
            assertThat(expression(method)).as("档案写接口 %s 应授权 OPERATOR", method.getName())
                    .contains("OPERATOR");
        }
    }
}
