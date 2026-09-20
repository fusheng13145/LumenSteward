package com.lumensteward.clawbot.pet;

import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.model.PetProfilePatch;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.domain.service.PetProfileServiceImpl;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.support.InMemoryPetProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 档案变更审计（字段级 diff）测试（FR-16 / A-4 / T7）。
 *
 * <p>验证 {@link PetProfileServiceImpl} 的写操作审计：前后值从「整对象 JSON」改为「字段级 diff」，
 * 形成可查询、低冗余的变更历史。
 */
class PetProfileAuditTest {

    private static final String OPENID = "openid-audit-user";

    private final InMemoryPetProfileRepository repository = new InMemoryPetProfileRepository();
    private final CapturingAudit audit = new CapturingAudit();
    private final PetProfileServiceImpl service =
            new PetProfileServiceImpl(repository, null, audit);

    @Test
    @DisplayName("CREATE 审计：before=null，after 为新建快照（字段级，不含 openid）")
    void shouldAuditCreateAsSnapshot() {
        service.create(OPENID, command("豆豆", "狗", "柯基"));

        assertThat(audit.calls).hasSize(1);
        CapturingAudit.Call call = audit.calls.get(0);
        assertThat(call.regType).isEqualTo("PROFILE");
        assertThat(call.action).isEqualTo("CREATE");
        assertThat(call.before).isNull();
        Map<String, Object> after = parse(call.after);
        assertThat(after).containsEntry("pet_name", "豆豆").containsEntry("pet_type", "狗");
        assertThat(after).doesNotContainKey("openid");
        // target 已脱敏（非原始 openid，含掩码）
        assertThat(call.target).isNotEqualTo(OPENID);
        assertThat(call.target).contains("****");
    }

    @Test
    @DisplayName("UPDATE 审计：仅记录变更字段的字段级 diff（pet_type 猫→狗）")
    void shouldAuditUpdateAsFieldDiff() {
        service.create(OPENID, command("豆豆", "猫", "英短"));
        audit.calls.clear();

        service.update(OPENID, "豆豆", new PetProfilePatch(
                "狗", null, null, null, null, null, null));

        assertThat(audit.calls).hasSize(1);
        CapturingAudit.Call call = audit.calls.get(0);
        assertThat(call.action).isEqualTo("UPDATE");
        Map<String, Object> before = parse(call.before);
        Map<String, Object> after = parse(call.after);
        assertThat(before).containsExactlyEntriesOf(Map.of("pet_type", "猫"));
        assertThat(after).containsExactlyEntriesOf(Map.of("pet_type", "狗"));
    }

    @Test
    @DisplayName("UPDATE 审计：无实际字段变更时记录空 diff")
    void shouldAuditNoChangeAsEmptyDiff() {
        service.create(OPENID, command("豆豆", "狗", "柯基"));
        audit.calls.clear();

        // 用与现状完全相同的值打补丁（增量更新不改变任何字段）
        service.update(OPENID, "豆豆", new PetProfilePatch(
                "狗", "柯基", "未知", null, BigDecimal.valueOf(5), "黏人", null));

        assertThat(audit.calls).hasSize(1);
        CapturingAudit.Call call = audit.calls.get(0);
        assertThat(parse(call.before)).isEmpty();
        assertThat(parse(call.after)).isEmpty();
    }

    @Test
    @DisplayName("DELETE 审计：after=null，before 为删除前快照")
    void shouldAuditDeleteAsSnapshot() {
        service.create(OPENID, command("豆豆", "狗", "柯基"));
        audit.calls.clear();

        service.softDelete(OPENID, "豆豆");

        assertThat(audit.calls).hasSize(1);
        CapturingAudit.Call call = audit.calls.get(0);
        assertThat(call.action).isEqualTo("DELETE");
        assertThat(call.after).isNull();
        Map<String, Object> before = parse(call.before);
        assertThat(before).containsEntry("pet_name", "豆豆").containsEntry("pet_type", "狗");
    }

    private static PetProfileCommand command(String name, String type, String breed) {
        return new PetProfileCommand(name, type, breed, "未知", null, BigDecimal.valueOf(5), "黏人", null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {});
    }

    /** 捕获审计调用的桩（用于断言前后值语义）。 */
    private static final class CapturingAudit implements AuditLogService {
        final List<Call> calls = new java.util.ArrayList<>();

        @Override
        public void record(Long adminId, String regType, String action, String target, String before,
                           String after, String reason, String ip, int result) {
            calls.add(new Call(regType, action, target, before, after));
        }

        private record Call(String regType, String action, String target, String before, String after) {
        }
    }
}
