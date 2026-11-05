package com.lumensteward.clawbot.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 样例长任务（迭代 4 W15，type=sample_report）：按 payload 延迟指定秒数后产出一份
 * 模拟报告——W15 交付判据「重启续跑 / 不重复下发」的取证载体，同时为 W14 生图接入
 * 提供联调样本。{@code data_source=mock-demo}，非真实业务能力。
 */
@Component
public class SampleReportHandler implements LongTaskHandler {

    /** 任务类型。 */
    public static final String TYPE = "sample_report";

    /** 延迟上限（秒）：取证够用，防误配把执行线程占满。 */
    static final int MAX_DELAY_SECONDS = 10;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public JsonNode execute(JsonNode payload) throws Exception {
        int delaySeconds = payload == null || !payload.hasNonNull("delay_seconds")
                ? 0 : Math.min(payload.get("delay_seconds").asInt(0), MAX_DELAY_SECONDS);
        if (delaySeconds > 0) {
            Thread.sleep(delaySeconds * 1000L);
        }
        String title = payload == null || !payload.hasNonNull("title")
                ? "样例报告" : payload.get("title").asText();
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("report", "sample");
        data.put("title", title);
        data.put("delay_seconds", delaySeconds);
        data.put("generated_at", Instant.now().toString());
        data.put("data_source", "mock-demo");
        return data;
    }

    @Override
    public String describe(JsonNode result) {
        String title = result == null || !result.hasNonNull("title")
                ? "样例报告" : result.get("title").asText();
        return "样例报告《" + title + "》出炉（data_source=mock-demo）";
    }
}
