package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEvidenceAssessmentTest {

    @Test
    void reportsRagNotFoundWhenTheRouterExpectedRagButNoRagSourceWasRetrieved() {
        AgentEvidenceAssessment.Assessment assessment = AgentEvidenceAssessment.assess(
            "没有检索到资料。",
            List.of(new AgentSource("skill", "Java Skill", null, "S1",
                "skill-java", "v1", "skill", "router")),
            true
        );

        assertThat(assessment.status()).isEqualTo(AgentEvidenceAssessment.Status.RAG_NOT_FOUND);
        assertThat(assessment.ragExpected()).isTrue();
        assertThat(assessment.ragSourceCount()).isZero();
        assertThat(assessment.sourceIntegrityStatus()).isEqualTo("ok");
    }

    @Test
    void reportsGroundedWhenReferencesAreValidAndMetadataIsComplete() {
        AgentEvidenceAssessment.Assessment assessment = AgentEvidenceAssessment.assess(
            "Redis 使用跳表。[R1]",
            List.of(new AgentSource("rag", "Redis 指南", 0.9, "R1",
                "kb-1:chunk-1", "v3", "knowledge-base", "retrieval")),
            true
        );

        assertThat(assessment.status()).isEqualTo(AgentEvidenceAssessment.Status.GROUNDED);
        assertThat(assessment.citedReferenceCount()).isEqualTo(1);
        assertThat(assessment.invalidReferenceCount()).isZero();
    }

    @Test
    void separatesMissingAndInvalidCitationsFromAHealthyRagHit() {
        AgentSource source = new AgentSource("rag", "Redis 指南", 0.9, "R1",
            "kb-1:chunk-1", "v3", "knowledge-base", "retrieval");

        AgentEvidenceAssessment.Assessment missing = AgentEvidenceAssessment.assess(
            "Redis 可以用于缓存。", List.of(source), true
        );
        AgentEvidenceAssessment.Assessment invalid = AgentEvidenceAssessment.assess(
            "Redis 可以用于缓存。[R9]", List.of(source), true
        );

        assertThat(missing.status()).isEqualTo(AgentEvidenceAssessment.Status.MISSING_CITATION);
        assertThat(invalid.status()).isEqualTo(AgentEvidenceAssessment.Status.INVALID_REFERENCE);
        assertThat(invalid.invalidReferenceCount()).isEqualTo(1);
    }

    @Test
    void givesMetadataConflictPriorityOverCitationStatus() {
        AgentEvidenceAssessment.Assessment assessment = AgentEvidenceAssessment.assess(
            "依据见 [R1]。",
            List.of(
                new AgentSource("rag", "旧版", 0.9, "R1", "kb-1", "v1", "knowledge-base", "retrieval"),
                new AgentSource("rag", "新版", 0.9, "R2", "kb-1", "v2", "knowledge-base", "retrieval")
            ),
            true
        );

        assertThat(assessment.status()).isEqualTo(AgentEvidenceAssessment.Status.SOURCE_METADATA_CONFLICT);
        assertThat(assessment.sourceIntegrityStatus()).isEqualTo("source-version-conflict");
    }

    @Test
    void keepsOrdinaryAnswerOutsideEvidenceAssessmentWhenNoRagIsExpected() {
        AgentEvidenceAssessment.Assessment assessment = AgentEvidenceAssessment.assess(
            "可以从 API 设计开始。",
            List.of(new AgentSource("skill", "Agent Skill", null, "S1")),
            false
        );

        assertThat(assessment.status()).isEqualTo(AgentEvidenceAssessment.Status.SOURCE_METADATA_INCOMPLETE);
        assertThat(assessment.ragSourceCount()).isZero();
    }
}
