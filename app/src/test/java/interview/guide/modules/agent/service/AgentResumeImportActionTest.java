package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.agent.model.AgentActionRequestEntity;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.modules.agent.repository.AgentActionRequestRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentResumeImportActionTest {

    @Mock private AgentActionRequestRepository repository;
    @Mock private AgentAuditService auditService;
    @Mock private AgentResumeImportService resumeImportService;

    @Test
    void proposesAndConfirmsGeneralResumeImportWithProtectedAssetId() {
        AgentContextAssetEntity asset = AgentContextAssetEntity.builder()
            .assetId("asset-1")
            .originalFilename("resume.pdf")
            .fileSize(1024L)
            .extractedText("resume")
            .reviewStatus(AgentContextAssetStatus.APPROVED_FOR_CONTEXT)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
        when(resumeImportService.requireImportableAsset("asset-1", "user-1")).thenReturn(asset);
        when(repository.findByOwnerKeyAndIdempotencyKey("user-1", "import-1"))
            .thenReturn(Optional.empty());
        when(repository.save(any(AgentActionRequestEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByActionIdForUpdate(any()))
            .thenAnswer(invocation -> Optional.of(importAction(
                invocation.getArgument(0), "user-1", "asset-1"
            )));

        AgentActionService service = new AgentActionService(
            repository, auditService, null, null, null, null, null,
            new PromptSanitizer(new LlmProviderProperties()), resumeImportService
        );
        var proposed = service.proposeResumeImportDraft(
            "agent-1", "user-1", "asset-1", "import-1", "run-1"
        );
        assertThat(proposed.status()).isEqualTo(AgentActionStatus.PROPOSED);
        assertThat(proposed.toolId()).isEqualTo("resume.import");

        var saved = org.mockito.ArgumentCaptor.forClass(AgentActionRequestEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRunId()).isEqualTo("run-1");
        verify(auditService).recordQuietly(
            "user-1", null, "agent-1", "run-1",
            "ACTION_PROPOSED", "agent-action", saved.getValue().getActionId(), "PROPOSED"
        );
        assertThat(proposed.interviewSessionId()).isNull();

        when(resumeImportService.importApprovedAsset("asset-1", "user-1"))
            .thenReturn(new AgentResumeImportService.ResumeImportResult(
                42L, "resume.pdf", false, true, "文件已导入简历管理，分析任务已提交后台处理"
            ));
        var confirmed = service.confirm(proposed.actionId(), "user-1");

        assertThat(confirmed.status()).isEqualTo(AgentActionStatus.SUCCEEDED);
        assertThat(confirmed.resultSummary()).contains("导入简历管理");
        verify(resumeImportService).importApprovedAsset("asset-1", "user-1");
    }

    private AgentActionRequestEntity importAction(String actionId, String owner, String assetId) {
        return AgentActionRequestEntity.builder()
            .actionId(actionId)
            .ownerKey(owner)
            .agentSessionId("agent-1")
            .toolId("resume.import")
            .riskLevel("BUSINESS_WRITE")
            .status(AgentActionStatus.PROPOSED)
            .title("导入简历管理")
            .previewText("确认后导入")
            .draftContent("文件：resume.pdf")
            .contextAssetId(assetId)
            .idempotencyKey("import-1")
            .build();
    }
}
