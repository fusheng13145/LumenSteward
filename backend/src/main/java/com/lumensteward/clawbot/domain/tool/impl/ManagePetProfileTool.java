package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.model.PetProfilePatch;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.domain.service.PetProfileService;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * 宠物档案工具（架构 5.1 / SRS FR-14，附录 B-5，MVP <b>唯一</b>真实工具）。
 *
 * <p>{@code idempotent=false}（写操作不可自动重试，附录 B-5）、{@code critical=true}（档案写入属状态
 * 变更，上游失败须中断依赖链，SC-05）。仓储/字段校验/唯一约束/软删除委托
 * {@link PetProfileService}（BR-10：内部异常一律转结构化失败结果回注，不抛出）。
 */
@Component
public class ManagePetProfileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ManagePetProfileTool.class);

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "manage_pet_profile";

    private static final String DESCRIPTION =
            "登记、查询、修改或删除用户的宠物档案。适用于用户描述宠物信息（'我的猫叫咪咪，两岁了'）"
                    + "或询问宠物信息（'豆豆几岁了'）等场景。执行写入操作前必须确认用户意图明确。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["CREATE","READ","UPDATE","DELETE"],"description":"操作类型"},
              "pet_name":{"type":"string","description":"宠物昵称，CREATE 时必填，其余操作作为定位条件","maxLength":32},
              "pet_type":{"type":"string","enum":["猫","狗","其他"],"description":"宠物类型"},
              "breed":{"type":"string","description":"品种"},
              "gender":{"type":"string","enum":["公","母","未知"],"description":"性别"},
              "birthday":{"type":"string","description":"生日，格式 YYYY-MM-DD"},
              "personality":{"type":"string","description":"性格描述"},
              "notes":{"type":"string","description":"备注"}
            },"required":["action"]}
            """;

    private final PetProfileService petProfileService;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param petProfileService 宠物档案领域服务
     */
    public ManagePetProfileTool(PetProfileService petProfileService) {
        this.petProfileService = petProfileService;
        this.schema = JsonSchema.of(PARAMETERS_SCHEMA);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonSchema parametersSchema() {
        return schema;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean critical() {
        return true;
    }

    @Override
    public Set<String> claimKeywords() {
        return Set.of("记录", "保存", "更新", "删除", "档案", "登记");
    }

    @Override
    public String monitorDomain() {
        return "pet_profile";
    }

    @Override
    public IntentType taskIntent() {
        return IntentType.PET_PROFILE;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String openid = context == null ? null : context.openid();
        if (openid == null || openid.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "缺少用户标识", false);
        }
        String action = text(args, "action");
        if (action == null || action.isBlank()) {
            return failureInvalidArgs("缺少 action（操作类型）");
        }
        try {
            return switch (action.trim().toUpperCase()) {
                case "CREATE" -> create(openid, args, start);
                case "READ" -> read(openid, args, start);
                case "UPDATE" -> update(openid, args, start);
                case "DELETE" -> delete(openid, args, start);
                default -> failureInvalidArgs("未知的 action: " + action);
            };
        } catch (BizException e) {
            // BR-10：业务失败转结构化结果回注模型（不抛出）
            return mapBizException(e);
        } catch (RuntimeException e) {
            log.warn("宠物档案工具执行异常: {}", e.getMessage());
            return ToolResult.failure("TOOL_FAILED", "档案操作失败，请稍后重试", false);
        }
    }

    private ToolResult create(String openid, JsonNode args, long start) {
        String petName = text(args, "pet_name");
        if (petName == null || petName.isBlank()) {
            // BR-08：参数缺失不猜测执行，回注"缺少昵称"以触发追问
            return ToolResult.failure("INVALID_ARGS", "缺少宠物昵称 pet_name，请先向用户确认它叫什么名字", false);
        }
        LocalDate birthday = parseDate(args, "birthday");
        PetProfileCommand command = new PetProfileCommand(petName, text(args, "pet_type"),
                text(args, "breed"), text(args, "gender"), birthday, parseDecimal(args, "weight_kg"),
                text(args, "personality"), text(args, "notes"));
        PetProfileView view = petProfileService.create(openid, command);
        return success(view, start);
    }

    private ToolResult read(String openid, JsonNode args, long start) {
        String petName = text(args, "pet_name");
        if (petName != null && !petName.isBlank()) {
            return petProfileService.findLiveByName(openid, petName)
                    .map(view -> success(view, start))
                    .orElseGet(() -> ToolResult.failure("EMPTY_RESULT",
                            "没有找到名为 " + petName + " 的宠物", false));
        }
        List<PetProfileView> list = petProfileService.listLive(openid);
        if (list.isEmpty()) {
            return ToolResult.failure("EMPTY_RESULT", "你还没有登记过宠物档案", false);
        }
        JsonNode data = JsonUtils.mapper().valueToTree(list);
        return ToolResult.success(data, elapsed(start));
    }

    private ToolResult update(String openid, JsonNode args, long start) {
        String petName = text(args, "pet_name");
        if (petName == null || petName.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "更新操作需要 pet_name 作为定位条件", false);
        }
        PetProfilePatch patch = new PetProfilePatch(text(args, "pet_type"), text(args, "breed"),
                text(args, "gender"), parseDate(args, "birthday"), parseDecimal(args, "weight_kg"),
                text(args, "personality"), text(args, "notes"));
        if (patch.isEmpty()) {
            return ToolResult.failure("INVALID_ARGS", "没有需要更新的字段", false);
        }
        PetProfileView view = petProfileService.update(openid, petName, patch);
        return success(view, start);
    }

    private ToolResult delete(String openid, JsonNode args, long start) {
        String petName = text(args, "pet_name");
        if (petName == null || petName.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "删除操作需要 pet_name 作为定位条件", false);
        }
        petProfileService.softDelete(openid, petName);
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("deleted", true);
        data.put("pet_name", petName);
        return ToolResult.success(data, elapsed(start));
    }

    private ToolResult mapBizException(BizException e) {
        ErrorCode code = e.getErrorCode();
        return switch (code) {
            case PET_NAME_DUPLICATE -> ToolResult.failure("PET_NAME_DUPLICATE",
                    "该昵称已存在，请提示用户是否改为更新现有档案", false);
            case PET_NOT_FOUND -> ToolResult.failure("EMPTY_RESULT", e.getMessage(), false);
            case PET_BIRTHDAY_INVALID -> ToolResult.failure("INVALID_ARGS", e.getMessage(), false);
            case PET_FIELD_INVALID, PARAM_INVALID, PARAM_MISSING -> ToolResult.failure("INVALID_ARGS",
                    e.getMessage(), false);
            default -> ToolResult.failure("TOOL_FAILED", "档案操作失败，请稍后重试", false);
        };
    }

    private ToolResult success(PetProfileView view, long start) {
        JsonNode data = view == null ? null : JsonUtils.mapper().valueToTree(view);
        return ToolResult.success(data, elapsed(start));
    }

    private ToolResult failureInvalidArgs(String message) {
        return ToolResult.failure("INVALID_ARGS", message, false);
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        return args.get(field).asText();
    }

    private static LocalDate parseDate(JsonNode args, String field) {
        String value = text(args, field);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            // 交由领域服务校验兜底：此处显式抛出参数非法
            throw BizException.of(ErrorCode.PET_BIRTHDAY_INVALID, "生日格式应为 YYYY-MM-DD");
        }
    }

    private static BigDecimal parseDecimal(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        try {
            return new BigDecimal(args.get(field).asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
