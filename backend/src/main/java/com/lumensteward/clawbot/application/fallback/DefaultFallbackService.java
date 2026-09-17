package com.lumensteward.clawbot.application.fallback;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 默认兜底文案实现（SRS 9.5 降级矩阵）。
 *
 * <p>文案与 9.5 矩阵的"用户感知"列严格对齐，确保降级后用户收到的仍是可理解、不误导的说明。
 */
@Service
public class DefaultFallbackService implements FallbackService {

    @Override
    public String render(FallbackReason reason, Map<String, Object> ctx) {
        if (reason == null) {
            return "抱歉，我暂时无法处理这个请求。";
        }
        return switch (reason) {
            case SIGNATURE_FAILED -> "请求校验未通过。";
            case MSG_DUPLICATED -> "success";
            case UNKNOWN_MSG_TYPE -> "这种消息我暂时还认不出来，可以发文字告诉我吗？";
            case LLM_TIMEOUT -> "我暂时无法回应，请稍后再试。";
            case LLM_UNAVAILABLE -> "我这会儿有点联系不上，请稍后再试。";
            case LLM_INVALID_OUTPUT -> "我这边有点卡住啦，方便换个说法再试试吗？";
            case LOW_CONFIDENCE_CLARIFY -> "我没太听明白，可以再具体说说吗？";
            case TOOL_NOT_FOUND -> "我暂时还没有这个能力哦。";
            case INVALID_ARGS -> "信息好像不太完整，可以补充一下吗？";
            case TOOL_FAILED -> "这项操作暂时没有成功，请稍后重试。";
            case TOOL_TIMEOUT -> "这一步有点慢，暂时没完成，请稍后再试。";
            case EMPTY_RESULT -> "暂时没有查到相关信息，请核对后重试。";
            case EXECUTION_HALLUCINATION -> "抱歉，我暂时无法获取该项信息，请稍后重试。";
            case CONTENT_BLOCKED -> "该内容我无法处理。";
            case SAFETY_UNAVAILABLE -> "内容暂时无法处理，请稍后再试。";
            case REDIS_UNAVAILABLE -> "我的多轮记忆暂时不可用，会尽量回答当前这条。";
            case DB_UNAVAILABLE -> "暂时无法保存，请稍后重试。";
            case RATE_LIMITED -> "请求有点频繁，休息一下再找我吧。";
            case BUDGET_EXCEEDED -> "今天的用量到上限啦，明天再来找我吧。";
            case FORCED_CONVERGENCE -> "我尽力了，但信息可能不完整：";
            case PET_NAME_DUPLICATE -> renderPetNameDuplicate(ctx);
            case MISSING_SLOT -> "它叫什么名字呀？";
        };
    }

    private static String renderPetNameDuplicate(Map<String, Object> ctx) {
        String petName = ctx == null ? null : String.valueOf(ctx.getOrDefault("petName", ""));
        if (petName == null || petName.isBlank() || "null".equals(petName)) {
            return "你已经有这只宠物的档案啦，是要更新它的信息吗？";
        }
        return "你已有一只叫" + petName + "的宠物，是要更新它的信息吗？";
    }
}
