package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumePersistenceService;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentResumeImportServiceTest {

    @Mock private AgentContextAssetService contextAssetService;
    @Mock private ResumeRepository resumeRepository;
    @Mock private FileStorageService storageService;
    @Mock private ResumePersistenceService persistenceService;
    @Mock private AnalyzeStreamProducer analyzeStreamProducer;

    @Test
    void copiesApprovedAssetAndQueuesResumeAnalysisWithoutDeletingOriginal() {
        AgentContextAssetEntity asset = approvedAsset();
        ResumeEntity saved = new ResumeEntity();
        saved.setId(42L);
        saved.setOriginalFilename("resume.pdf");
        when(contextAssetService.requireApproved("asset-1", "user-1")).thenReturn(asset);
        when(resumeRepository.findByFileHashAndOwnerId("hash-1", "user-1"))
            .thenReturn(Optional.empty());
        when(storageService.copyAgentContextAssetToResume("agent-context-assets/resume.pdf", "resume.pdf"))
            .thenReturn("resumes/2026/11/06/resume-copy.pdf");
        when(storageService.getFileUrl("resumes/2026/11/06/resume-copy.pdf"))
            .thenReturn("http://storage/resumes/2026/11/06/resume-copy.pdf");
        when(persistenceService.saveImportedResume(
            eq("user-1"), eq("hash-1"), eq("resume.pdf"), eq(1024L), eq("application/pdf"),
            eq("resumes/2026/11/06/resume-copy.pdf"),
            eq("http://storage/resumes/2026/11/06/resume-copy.pdf"), eq("Java backend resume")
        )).thenReturn(saved);
        when(analyzeStreamProducer.sendAnalyzeTask(42L)).thenReturn(true);

        AgentResumeImportService service = service();
        AgentResumeImportService.ResumeImportResult result = service.importApprovedAsset(
            "asset-1", "user-1"
        );

        assertThat(result.resumeId()).isEqualTo(42L);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.enqueueAccepted()).isTrue();
        assertThat(result.message()).contains("简历管理");
        verify(analyzeStreamProducer).sendAnalyzeTask(42L);
        verify(storageService, never()).deleteAgentContextAsset(any());
        verify(storageService, never()).deleteResume(any());
    }

    @Test
    void reusesExistingResumeByOwnerAndHashWithoutCopyingAsset() {
        AgentContextAssetEntity asset = approvedAsset();
        ResumeEntity existing = new ResumeEntity();
        existing.setId(7L);
        existing.setOriginalFilename("resume.pdf");
        existing.setAccessCount(3);
        when(contextAssetService.requireApproved("asset-1", "user-1")).thenReturn(asset);
        when(resumeRepository.findByFileHashAndOwnerId("hash-1", "user-1"))
            .thenReturn(Optional.of(existing));

        AgentResumeImportService.ResumeImportResult result = service().importApprovedAsset(
            "asset-1", "user-1"
        );

        assertThat(result.resumeId()).isEqualTo(7L);
        assertThat(result.duplicate()).isTrue();
        assertThat(existing.getAccessCount()).isEqualTo(4);
        verify(resumeRepository).save(existing);
        verify(storageService, never()).copyAgentContextAssetToResume(any(), any());
        verify(analyzeStreamProducer, never()).sendAnalyzeTask(any());
    }

    @Test
    void refusesAssetLargerThanResumeUploadLimit() {
        AgentContextAssetEntity asset = approvedAsset();
        asset.setFileSize(10 * 1024 * 1024L + 1);
        when(contextAssetService.requireApproved("asset-1", "user-1")).thenReturn(asset);

        assertThatThrownBy(() -> service().importApprovedAsset("asset-1", "user-1"))
            .hasMessageContaining("不能超过10MB");
        verify(resumeRepository, never()).findByFileHashAndOwnerId(any(), any());
        verify(storageService, never()).copyAgentContextAssetToResume(any(), any());
    }

    private AgentResumeImportService service() {
        return new AgentResumeImportService(
            contextAssetService, resumeRepository, storageService,
            persistenceService, analyzeStreamProducer
        );
    }

    private AgentContextAssetEntity approvedAsset() {
        return AgentContextAssetEntity.builder()
            .assetId("asset-1")
            .ownerId("user-1")
            .fileHash("hash-1")
            .originalFilename("resume.pdf")
            .fileSize(1024L)
            .contentType("application/pdf")
            .storageKey("agent-context-assets/resume.pdf")
            .storageUrl("http://storage/agent-context-assets/resume.pdf")
            .extractedText("Java backend resume")
            .reviewStatus(AgentContextAssetStatus.APPROVED_FOR_CONTEXT)
            .expiresAt(LocalDateTime.now().plusDays(1))
            .build();
    }
}
