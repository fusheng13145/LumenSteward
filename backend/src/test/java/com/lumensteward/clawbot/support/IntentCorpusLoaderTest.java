package com.lumensteward.clawbot.support;

import com.lumensteward.clawbot.domain.intent.IntentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 意图语料加载器测试（TODO-05 / 迭代 2 增量 PRD）。
 *
 * <p>纯解析验证，<b>不调用任何 LLM</b>。覆盖：train/test 语料规模、意图值合法、
 * 已知样本可正确解析、缺失资源抛 {@link IOException}。
 */
class IntentCorpusLoaderTest {

    private static final String TRAIN = "intent-corpus/train.jsonl";
    private static final String TEST = "intent-corpus/test.jsonl";

    @Test
    @DisplayName("语料规模：train ≥ 300 且 test 非空（合计 ≥ 300 监督样本）")
    void corpusScaleShouldMeetRequirement() throws IOException {
        List<IntentCorpusLoader.IntentSample> train = IntentCorpusLoader.load(TRAIN);
        List<IntentCorpusLoader.IntentSample> test = IntentCorpusLoader.load(TEST);

        assertThat(train).hasSizeGreaterThanOrEqualTo(300)
                .as("train.jsonl 至少 300 条").isNotEmpty();
        assertThat(test).isNotEmpty();
        assertThat(train.size() + test.size())
                .as("train + test 合计至少 300 条监督样本")
                .isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("意图值合法：所有样本的预期意图均属于 IntentType 枚举")
    void allIntentsShouldBeValidEnum() throws IOException {
        List<IntentCorpusLoader.IntentSample> samples = IntentCorpusLoader.load(TRAIN);

        assertThat(samples).isNotEmpty();
        assertThat(samples).allMatch(s -> s.expectedIntent() != null);
        assertThat(samples).allMatch(
                s -> EnumSet.allOf(IntentType.class).contains(s.expectedIntent()));
    }

    @Test
    @DisplayName("已知样本：含『柯基』的宠物档案样本被正确解析为 PET_PROFILE")
    void knownPetProfileSampleParsesCorrectly() throws IOException {
        List<IntentCorpusLoader.IntentSample> samples = IntentCorpusLoader.load(TRAIN);

        boolean found = samples.stream().anyMatch(s ->
                s.expectedIntent() == IntentType.PET_PROFILE && s.text().contains("柯基"));
        assertThat(found).as("应存在一条 PET_PROFILE 且文本含『柯基』的样本").isTrue();
    }

    @Test
    @DisplayName("槽位解析：快递样本携带 trackingNo 槽位")
    void expressSampleCarriesTrackingNoSlot() throws IOException {
        List<IntentCorpusLoader.IntentSample> samples = IntentCorpusLoader.load(TRAIN);

        boolean found = samples.stream().anyMatch(s ->
                s.expectedIntent() == IntentType.EXPRESS
                        && s.slots() != null
                        && s.slots().containsKey("trackingNo"));
        assertThat(found).as("应存在携带 trackingNo 槽位的 EXPRESS 样本").isTrue();
    }

    @Test
    @DisplayName("缺失资源：加载不存在的语料应抛 IOException")
    void missingResourceThrowsIOException() {
        assertThatThrownBy(() -> IntentCorpusLoader.load("intent-corpus/does-not-exist.jsonl"))
                .isInstanceOf(IOException.class);
    }
}
