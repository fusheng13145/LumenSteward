package com.lumensteward.clawbot.support;

import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.impl.ManagePetProfileTool;
import com.lumensteward.clawbot.domain.tool.impl.PlanRouteTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import com.lumensteward.clawbot.domain.tool.impl.RecognizeImageTool;
import com.lumensteward.clawbot.domain.tool.impl.SynthesizeVoiceTool;

import java.util.List;

/**
 * 与生产容器同源的工具注册表（单测支撑）。
 *
 * <p>FR-23 之后，一致性校验关键词、看板归因域、任务意图三处口径都由<b>工具自述</b>并经
 * {@link ToolRegistry} 反查，因此需要真实工具语义的单测必须持有真实注册表，而不是在测试里
 * 抄一份关键词表（抄的那份迟早与工具漂移）。
 *
 * <p>构造依赖一律传 {@code null}：注册表只读元信息（name / Schema / 自述口径），
 * <b>不会</b>调用 {@code execute()}，故不需要外部适配器。
 */
public final class ToolRegistries {

    private ToolRegistries() {
        // 静态工厂，禁止实例化
    }

    /**
     * 生产工具全集的离线注册表。
     *
     * @return 含六个已注册工具的 {@link ToolRegistry}
     */
    public static ToolRegistry productionTools() {
        return new ToolRegistry(List.of(
                new ManagePetProfileTool(null),
                new QueryExpressTool(null),
                new PlanRouteTool(null),
                new SynthesizeVoiceTool(null, null, null),
                new RecognizeImageTool(null, null),
                new QueryWeatherTool()));
    }
}
