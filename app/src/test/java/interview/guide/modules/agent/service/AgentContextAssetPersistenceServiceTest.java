package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.modules.agent.repository.AgentContextAssetRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentContextAssetPersistenceServiceTest {

    @Test
    void createsAndLinksKnowledgeBaseInsidePersistenceBoundary() {
        AgentContextAssetRepository assets = mock(AgentContextAssetRepository.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        KnowledgeBasePersistenceService knowledgeBasePersistence = mock(KnowledgeBasePersistenceService.class);
        AgentContextAssetEntity asset = pendingAsset();
        KnowledgeBaseEntity knowledgeBase = new KnowledgeBaseEntity();
        knowledgeBase.setId(42L);

        when(assets.findByAssetIdAndOwnerId("asset-1", "owner-1"))
            .thenReturn(Optional.of(asset));
        when(knowledgeBases.findByFileHashAndOwnerId("hash-1", "owner-1"))
            .thenReturn(Optional.empty());
        when(knowledgeBasePersistence.saveKnowledgeBaseFromStoredAsset(
            "notes", "agent-upload", "notes.md", 20L, "text/markdown",
            "asset/notes.md", "/private/notes.md", "hash-1", "owner-1"
        )).thenReturn(knowledgeBase);
        when(assets.save(asset)).thenReturn(asset);

        AgentContextAssetPersistenceService service = new AgentContextAssetPersistenceService(
            assets, knowledgeBases, knowledgeBasePersistence
        );

        AgentContextAssetPersistenceService.KnowledgeBaseImportPreparation result =
            service.prepareKnowledgeBaseImport("asset-1", "owner-1", "notes", "agent-upload");

        assertThat(result.vectorizationRequired()).isTrue();
        assertThat(result.asset().knowledgeBaseId()).isEqualTo(42L);
        assertThat(asset.getReviewStatus()).isEqualTo(AgentContextAssetStatus.KB_IMPORT_COMPLETED);
        verify(assets).save(asset);
    }

    @Test
    void reusesExistingKnowledgeBaseWithoutRequestingAnotherVectorization() {
        AgentContextAssetRepository assets = mock(AgentContextAssetRepository.class);
        KnowledgeBaseRepository knowledgeBases = mock(KnowledgeBaseRepository.class);
        KnowledgeBasePersistenceService knowledgeBasePersistence = mock(KnowledgeBasePersistenceService.class);
        AgentContextAssetEntity asset = pendingAsset();
        KnowledgeBaseEntity existing = new KnowledgeBaseEntity();
        existing.setId(7L);

        when(assets.findByAssetIdAndOwnerId("asset-1", "owner-1"))
            .thenReturn(Optional.of(asset));
        when(knowledgeBases.findByFileHashAndOwnerId("hash-1", "owner-1"))
            .thenReturn(Optional.of(existing));
        when(assets.save(asset)).thenReturn(asset);

        AgentContextAssetPersistenceService service = new AgentContextAssetPersistenceService(
            assets, knowledgeBases, knowledgeBasePersistence
        );

        AgentContextAssetPersistenceService.KnowledgeBaseImportPreparation result =
            service.prepareKnowledgeBaseImport("asset-1", "owner-1", "notes", "agent-upload");

        assertThat(result.vectorizationRequired()).isFalse();
        assertThat(result.asset().knowledgeBaseId()).isEqualTo(7L);
        verify(knowledgeBasePersistence, never()).saveKnowledgeBaseFromStoredAsset(
            any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    private AgentContextAssetEntity pendingAsset() {
        return AgentContextAssetEntity.builder()
            .assetId("asset-1")
            .ownerId("owner-1")
            .fileHash("hash-1")
            .originalFilename("notes.md")
            .fileSize(20L)
            .contentType("text/markdown")
            .storageKey("asset/notes.md")
            .storageUrl("/private/notes.md")
            .reviewStatus(AgentContextAssetStatus.KB_IMPORT_PENDING)
            .build();
    }
}
