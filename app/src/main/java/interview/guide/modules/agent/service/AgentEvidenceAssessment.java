package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;

/**
 * 汇总一次回答的证据边界。
 *
 * <p>这是低敏、只读的运行评估，不负责判断模型回答的事实语义是否正确，
 * 也不修改回答内容。它把“RAG 是否被请求、是否命中、引用是否合法、来源元数据
 * 是否完整/冲突”收敛为一个可观测状态，便于评测和排障。</p>
 */
public final class AgentEvidenceAssessment {

    private AgentEvidenceAssessment() {
    }

    public static Assessment assess(
        String answer,
        List<AgentSource> sources,
        boolean ragExpected
    ) {
        List<AgentSource> safeSources = sources == null ? List.of() : sources;
        long ragSourceCount = safeSources.stream()
            .filter(source -> source != null && "rag".equalsIgnoreCase(source.kind()))
            .count();
        AgentAnswerGroundingPolicy.Assessment grounding =
            AgentAnswerGroundingPolicy.assess(answer, safeSources);
        AgentSourceIntegrityPolicy.Assessment integrity = AgentSourceIntegrityPolicy.assess(safeSources);

        Status status;
        if (integrity.status() == AgentSourceIntegrityPolicy.Status.DUPLICATE_REFERENCE
            || integrity.status() == AgentSourceIntegrityPolicy.Status.SOURCE_VERSION_CONFLICT
            || integrity.status() == AgentSourceIntegrityPolicy.Status.SOURCE_AUTHORITY_CONFLICT) {
            status = Status.SOURCE_METADATA_CONFLICT;
        } else if (integrity.status() == AgentSourceIntegrityPolicy.Status.LEGACY_METADATA) {
            status = Status.SOURCE_METADATA_INCOMPLETE;
        } else if (ragExpected && ragSourceCount == 0) {
            status = Status.RAG_NOT_FOUND;
        } else {
            status = switch (grounding.status()) {
                case INVALID_REFERENCE -> Status.INVALID_REFERENCE;
                case MISSING_CITATION -> Status.MISSING_CITATION;
                case NO_RAG_SOURCE -> Status.RAG_NOT_FOUND;
                case OK -> Status.GROUNDED;
                case NOT_APPLICABLE -> ragSourceCount > 0 ? Status.GROUNDED : Status.NOT_APPLICABLE;
            };
        }
        return new Assessment(
            status,
            ragExpected,
            (int) Math.min(Integer.MAX_VALUE, ragSourceCount),
            grounding.citedReferences().size(),
            grounding.invalidReferences().size(),
            integrity.status().auditValue()
        );
    }

    public enum Status {
        NOT_APPLICABLE("not-applicable"),
        GROUNDED("grounded"),
        RAG_NOT_FOUND("rag-not-found"),
        MISSING_CITATION("missing-citation"),
        INVALID_REFERENCE("invalid-reference"),
        SOURCE_METADATA_CONFLICT("source-metadata-conflict"),
        SOURCE_METADATA_INCOMPLETE("source-metadata-incomplete");

        private final String auditValue;

        Status(String auditValue) {
            this.auditValue = auditValue;
        }

        public String auditValue() {
            return auditValue;
        }
    }

    public record Assessment(
        Status status,
        boolean ragExpected,
        int ragSourceCount,
        int citedReferenceCount,
        int invalidReferenceCount,
        String sourceIntegrityStatus
    ) {
        public Assessment {
            status = status == null ? Status.NOT_APPLICABLE : status;
            ragSourceCount = Math.max(0, ragSourceCount);
            citedReferenceCount = Math.max(0, citedReferenceCount);
            invalidReferenceCount = Math.max(0, invalidReferenceCount);
            sourceIntegrityStatus = sourceIntegrityStatus == null
                ? AgentSourceIntegrityPolicy.Status.NOT_APPLICABLE.auditValue()
                : sourceIntegrityStatus;
        }

        public String auditValue() {
            return status.auditValue();
        }
    }
}
