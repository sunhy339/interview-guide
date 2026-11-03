package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetResponse;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.modules.agent.repository.AgentContextAssetRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 上下文素材的数据库事务边界。
 *
 * <p>这里只处理数据库状态，不调用对象存储、文件解析或 Redis。外层服务在事务提交后
 * 才能投递向量化任务，避免数据库回滚但异步任务已经可见。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentContextAssetPersistenceService {

    private final AgentContextAssetRepository repository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBasePersistenceService knowledgeBasePersistenceService;

    @Transactional
    public KnowledgeBaseImportPreparation prepareKnowledgeBaseImport(
        String assetId, String ownerId, String name, String category
    ) {
        AgentContextAssetEntity asset = find(assetId, ownerId);
        if (asset.getKnowledgeBaseId() != null
            && asset.getReviewStatus() == AgentContextAssetStatus.KB_IMPORT_COMPLETED) {
            return new KnowledgeBaseImportPreparation(AgentContextAssetResponse.from(asset), false);
        }
        if (asset.getReviewStatus() != AgentContextAssetStatus.KB_IMPORT_PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先审核素材并申请加入知识库");
        }
        if (asset.getKnowledgeBaseId() != null) {
            return new KnowledgeBaseImportPreparation(AgentContextAssetResponse.from(asset), true);
        }

        Optional<KnowledgeBaseEntity> duplicate = knowledgeBaseRepository
            .findByFileHashAndOwnerId(asset.getFileHash(), ownerId);
        KnowledgeBaseEntity knowledgeBase = duplicate.orElseGet(() ->
            knowledgeBasePersistenceService.saveKnowledgeBaseFromStoredAsset(
                name, category, asset.getOriginalFilename(), asset.getFileSize(), asset.getContentType(),
                asset.getStorageKey(), asset.getStorageUrl(), asset.getFileHash(), ownerId
            )
        );
        asset.setKnowledgeBaseId(knowledgeBase.getId());
        asset.setReviewStatus(AgentContextAssetStatus.KB_IMPORT_COMPLETED);
        AgentContextAssetEntity saved = repository.save(asset);
        return new KnowledgeBaseImportPreparation(
            AgentContextAssetResponse.from(saved), duplicate.isEmpty()
        );
    }

    @Transactional
    public AgentContextAssetResponse markVectorizationEnqueueFailed(
        String assetId, String ownerId, String notice
    ) {
        AgentContextAssetEntity asset = find(assetId, ownerId);
        asset.setReviewStatus(AgentContextAssetStatus.KB_IMPORT_FAILED);
        asset.setSecurityNotice(notice);
        return AgentContextAssetResponse.from(repository.save(asset));
    }

    @Transactional
    public void reject(String assetId, String ownerId) {
        AgentContextAssetEntity asset = find(assetId, ownerId);
        if (asset.getKnowledgeBaseId() != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "已进入知识库的素材不能从 Agent 待审核区删除");
        }
        asset.setReviewStatus(AgentContextAssetStatus.REJECTED);
        repository.save(asset);
    }

    private AgentContextAssetEntity find(String assetId, String ownerId) {
        if (assetId == null || assetId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "素材 ID 不能为空");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份已失效，请刷新页面后重试");
        }
        return repository.findByAssetIdAndOwnerId(assetId.trim(), ownerId.trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 素材不存在"));
    }

    public record KnowledgeBaseImportPreparation(
        AgentContextAssetResponse asset,
        boolean vectorizationRequired
    ) {
    }
}
