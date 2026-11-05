package com.lumensteward.clawbot.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.task.LongTaskRecord;
import com.lumensteward.clawbot.domain.task.LongTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 长任务用例服务（迭代 4 W15）：提交（校验类型与载荷）→ 落库 PENDING → 交执行器调度。
 *
 * <p>提交面当前是管理端（SUPER_ADMIN），是通道的<b>运维控制面</b>而非治理面新功能；
 * 用户侧入口随 W14 生图（工具内异步提交）接入。
 */
@Service
public class LongTaskService {

    private static final Logger log = LoggerFactory.getLogger(LongTaskService.class);

    /** 载荷上限（字符）：长任务入参是结构化 JSON，不是文本容器。 */
    static final int MAX_PAYLOAD_LENGTH = 4000;

    private final LongTaskStore store;
    private final LongTaskExecutor executor;

    /** 构造器注入（G-14）。 */
    public LongTaskService(LongTaskStore store, LongTaskExecutor executor) {
        this.store = store;
        this.executor = executor;
    }

    /**
     * 提交长任务。
     *
     * @param taskType    任务类型（须有已注册 handler）
     * @param openid      发起用户
     * @param payloadJson 入参 JSON 文本（空按空对象处理）
     * @param traceId     提交链路标识
     * @return 任务 id
     */
    public long submit(String taskType, String openid, String payloadJson, String traceId) {
        if (taskType == null || taskType.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "任务类型不能为空");
        }
        if (!executor.supports(taskType)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知任务类型: " + taskType);
        }
        if (payloadJson != null && payloadJson.length() > MAX_PAYLOAD_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "任务载荷超长（上限 " + MAX_PAYLOAD_LENGTH + " 字符）");
        }
        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                JsonUtils.mapper().readTree(payloadJson);
            } catch (Exception e) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "任务载荷必须是合法 JSON");
            }
        }
        long id = store.insert(new LongTaskRecord(null, taskType, openid, null,
                payloadJson == null || payloadJson.isBlank() ? null : payloadJson,
                null, null, 0, false, traceId));
        executor.dispatch(id);
        log.info("长任务已提交 id={} type={} openid={}", id, taskType, MaskUtils.openid(openid));
        return id;
    }

    /** 按主键查询。 */
    public java.util.Optional<LongTaskRecord> get(long id) {
        return store.find(id);
    }
}
