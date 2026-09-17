package com.lumensteward.clawbot.infrastructure.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计字段填充<b>生效断言</b>（G-04 / 7.6.2）。
 *
 * <p>动机：若 {@link MyMetaObjectHandler} 未实现或未注册为 Spring Bean，{@code createdAt}/{@code updatedAt}
 * 会<b>静默为 null</b>（不报错）。本测试直接驱动 {@code MetaObjectHandler} 的填充逻辑并断言字段被写入，
 * 同时校验其带 {@link Component} 注解（即会被组件扫描注册）。
 */
class MyMetaObjectHandlerTest {

    private final MyMetaObjectHandler handler = new MyMetaObjectHandler();

    /**
     * 初始化探针实体的 TableInfo，使 MyBatis-Plus 的 strict 填充能识别字段的 fill 策略。
     * 这是「不启动 Spring 容器即可对填充逻辑做单元断言」的关键前置。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProbeEntity.class);
    }

    @Test
    @DisplayName("insertFill 自动写入 createdAt 与 updatedAt")
    void shouldFillAuditFieldsOnInsert() {
        ProbeEntity entity = new ProbeEntity();

        handler.insertFill(metaObject(entity));

        assertThat(entity.getCreatedAt()).as("createdAt 应被自动填充").isNotNull();
        assertThat(entity.getUpdatedAt()).as("updatedAt 应被自动填充").isNotNull();
    }

    @Test
    @DisplayName("updateFill 仅刷新 updatedAt，不触碰 createdAt")
    void shouldFillUpdatedAtOnUpdate() {
        ProbeEntity entity = new ProbeEntity();

        handler.updateFill(metaObject(entity));

        assertThat(entity.getUpdatedAt()).as("updatedAt 应被刷新").isNotNull();
        assertThat(entity.getCreatedAt()).as("updateFill 不应写入 createdAt").isNull();
    }

    @Test
    @DisplayName("strict 填充不覆盖已有值（保留业务显式赋值）")
    void shouldNotOverrideExplicitValue() {
        LocalDateTime preset = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        ProbeEntity entity = new ProbeEntity();
        entity.setCreatedAt(preset);

        handler.insertFill(metaObject(entity));

        assertThat(entity.getCreatedAt()).isEqualTo(preset);
    }

    @Test
    @DisplayName("处理器已声明为 Spring 组件，可被扫描注册")
    void shouldBeRegisteredAsSpringComponent() {
        assertThat(MyMetaObjectHandler.class.isAnnotationPresent(Component.class))
                .as("MyMetaObjectHandler 必须为 @Component，否则填充静默失效")
                .isTrue();
    }

    private static MetaObject metaObject(Object entity) {
        return MetaObject.forObject(entity, SystemMetaObject.DEFAULT_OBJECT_FACTORY,
                SystemMetaObject.DEFAULT_OBJECT_WRAPPER_FACTORY, new DefaultReflectorFactory());
    }

    /**
     * 测试专用探针实体：仅用于验证填充逻辑，不参与生产映射。
     */
    @Getter
    @Setter
    @TableName("probe_entity")
    static class ProbeEntity {

        @TableId
        private Long id;

        @TableField(fill = FieldFill.INSERT)
        private LocalDateTime createdAt;

        @TableField(fill = FieldFill.INSERT_UPDATE)
        private LocalDateTime updatedAt;
    }
}
