package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerGroundingPolicyTest {

    private static final List<AgentSource> SOURCES = List.of(
        new AgentSource("rag", "Redis 参考资料", 0.88, "R1"),
        new AgentSource("rag", "MQ 参考资料", 0.82, "R2"),
        new AgentSource("skill", "Skill: java-backend", null, "skill-reference")
    );

    @Test
    @DisplayName("真实引用不追加提示")
    void acceptsExistingCitation() {
        assertThat(AgentAnswerGroundingPolicy.buildNotice("Redis 使用跳表。[R1]", SOURCES))
            .isBlank();
        assertThat(AgentAnswerGroundingPolicy.assess("Redis 使用跳表。[R1]", SOURCES).status())
            .isEqualTo(AgentAnswerGroundingPolicy.Status.OK);
    }

    @Test
    @DisplayName("检索到资料但没有引用时追加透明提示")
    void warnsWhenAnswerDoesNotCiteRetrievedSources() {
        assertThat(AgentAnswerGroundingPolicy.buildNotice("建议复习 Redis。", SOURCES))
            .contains("没有明确标注来源编号");
        assertThat(AgentAnswerGroundingPolicy.assess("建议复习 Redis。", SOURCES).status())
            .isEqualTo(AgentAnswerGroundingPolicy.Status.MISSING_CITATION);
    }

    @Test
    @DisplayName("引用不存在的编号时追加校验提示")
    void warnsWhenAnswerCitesUnknownSource() {
        assertThat(AgentAnswerGroundingPolicy.buildNotice("结论来自资料。[R9]", SOURCES))
            .contains("未检索到的来源编号")
            .contains("R9");
        assertThat(AgentAnswerGroundingPolicy.assess("结论来自资料。[R9]", SOURCES))
            .satisfies(result -> {
                assertThat(result.status()).isEqualTo(AgentAnswerGroundingPolicy.Status.INVALID_REFERENCE);
                assertThat(result.invalidReferences()).containsExactly("R9");
            });
    }

    @Test
    @DisplayName("没有 RAG 来源时不干扰 Skill 或普通回答")
    void ignoresAnswersWithoutRagSources() {
        assertThat(AgentAnswerGroundingPolicy.buildNotice(
            "参考 Skill 资料即可。", List.of(new AgentSource("skill", "Skill", null, "skill-reference"))
        )).isBlank();
        assertThat(AgentAnswerGroundingPolicy.assess(
            "参考 Skill 资料即可。", List.of(new AgentSource("skill", "Skill", null, "skill-reference"))
        ).status()).isEqualTo(AgentAnswerGroundingPolicy.Status.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("没有 RAG 来源却出现引用编号时提示用户")
    void warnsWhenCitationAppearsWithoutRagSources() {
        assertThat(AgentAnswerGroundingPolicy.buildNotice(
            "结论来自资料。[R1]", List.of()
        )).contains("没有检索到可用的 RAG 来源");
        assertThat(AgentAnswerGroundingPolicy.assess("结论来自资料。[R1]", List.of()).status())
            .isEqualTo(AgentAnswerGroundingPolicy.Status.NO_RAG_SOURCE);
    }
}
