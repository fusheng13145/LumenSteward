package com.lumensteward.clawbot.infrastructure.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充（G-04 / 7.6.2）。
 *
 * <p>为带 {@code @TableField(fill = FieldFill.INSERT / INSERT_UPDATE)} 的实体字段自动写入
 * {@code createdAt} / {@code updatedAt}；<b>必须注册为 Spring Bean</b>，否则填充会静默失效
 * （不报错，字段为 null）。
 *
 * <p>使用 strict 系列 API：仅当字段声明了对应 fill 策略、且当前值为 null 时才写入，
 * 避免覆盖业务显式赋值的场景。断言测试见 {@code MyMetaObjectHandlerTest}。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    /** 创建时间字段名（对齐 7.2/7.6.2 的 {@code created_at}）。 */
    private static final String FIELD_CREATED_AT = "createdAt";

    /** 更新时间字段名（对齐 {@code updated_at}）。 */
    private static final String FIELD_UPDATED_AT = "updatedAt";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        this.strictInsertFill(metaObject, FIELD_CREATED_AT, LocalDateTime.class, now);
        this.strictInsertFill(metaObject, FIELD_UPDATED_AT, LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(metaObject, FIELD_UPDATED_AT, LocalDateTime.class, LocalDateTime.now());
    }
}
