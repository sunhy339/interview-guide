package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentSource;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSourceIntegrityPolicyTest {

    @Test
    void treatsMissingSourcesAsNotApplicable() {
        assertThat(AgentSourceIntegrityPolicy.assess(List.of()).status())
            .isEqualTo(AgentSourceIntegrityPolicy.Status.NOT_APPLICABLE);
    }

    @Test
    void acceptsDistinctVersionedSources() {
        AgentSource interview = new AgentSource(
            "interview", "面试报告", null, "INTERVIEW", "session-1",
            "interview-report-v1", "user-selected", "explicit-context"
        );
        AgentSource rag = new AgentSource(
            "rag", "资料", 0.9, "R1", "kb-1:R1",
            "rag-retrieval-v1", "knowledge-base", "retrieval"
        );

        AgentSourceIntegrityPolicy.Assessment result = AgentSourceIntegrityPolicy.assess(
            List.of(interview, rag)
        );

        assertThat(result.status()).isEqualTo(AgentSourceIntegrityPolicy.Status.OK);
        assertThat(result.sourceCount()).isEqualTo(2);
        assertThat(result.auditValue()).isEqualTo("ok");
    }

    @Test
    void detectsDuplicateReferences() {
        AgentSource first = source("kb-1:R1", "rag-retrieval-v1", "R1");
        AgentSource second = source("kb-2:R1", "rag-retrieval-v1", "R1");

        AgentSourceIntegrityPolicy.Assessment result = AgentSourceIntegrityPolicy.assess(
            List.of(first, second)
        );

        assertThat(result.status()).isEqualTo(AgentSourceIntegrityPolicy.Status.DUPLICATE_REFERENCE);
        assertThat(result.duplicateReferences()).containsExactly("R1");
    }

    @Test
    void detectsVersionConflictForSameSource() {
        AgentSource first = source("resume-1", "resume-record-v1", "RESUME-1");
        AgentSource second = source("resume-1", "resume-record-v2", "RESUME-1-UPDATED");

        AgentSourceIntegrityPolicy.Assessment result = AgentSourceIntegrityPolicy.assess(
            List.of(first, second)
        );

        assertThat(result.status()).isEqualTo(AgentSourceIntegrityPolicy.Status.SOURCE_VERSION_CONFLICT);
        assertThat(result.conflictingSourceIds()).containsExactly("resume-1");
    }

    @Test
    void detectsAuthorityConflictForSameSource() {
        AgentSource first = source("file-1", "context-asset-v1", "FILE-1");
        AgentSource second = new AgentSource(
            "file", "文件", null, "FILE-1-UPDATED", "file-1",
            "context-asset-v1", "user-reviewed", "explicit-context"
        );

        AgentSourceIntegrityPolicy.Assessment result = AgentSourceIntegrityPolicy.assess(
            List.of(first, second)
        );

        assertThat(result.status()).isEqualTo(AgentSourceIntegrityPolicy.Status.SOURCE_AUTHORITY_CONFLICT);
        assertThat(result.conflictingAuthoritySourceIds()).containsExactly("file-1");
    }

    @Test
    void marksLegacySourceMetadataWithoutBreakingCompatibility() {
        AgentSourceIntegrityPolicy.Assessment result = AgentSourceIntegrityPolicy.assess(
            List.of(new AgentSource("skill", "Java", null, "skill-reference"))
        );

        assertThat(result.status()).isEqualTo(AgentSourceIntegrityPolicy.Status.LEGACY_METADATA);
        assertThat(result.legacyMetadataCount()).isEqualTo(1);
    }

    private AgentSource source(String sourceId, String version, String reference) {
        return new AgentSource(
            "rag", "资料", 0.8, reference, sourceId, version,
            "knowledge-base", "retrieval"
        );
    }
}
