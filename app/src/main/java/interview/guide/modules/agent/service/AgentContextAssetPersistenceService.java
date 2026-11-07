package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetResponse;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.modules.agent.repository.AgentContextAssetRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 上下文素材的数据库事务边界。
 *
 * <p>这里只处理数据库状态，不调用对象存储、文件解析或 Redis。外层服务在事务提交后
 * 才能投递向量化任务，避免数据库回滚但异步任务已经可见。</p>
 */
@Service
public class AgentContextAssetPersistenceService {

    private static final int MAX_KNOWLEDGE_BASE_NAME = 120;
    private static final int MAX_KNOWLEDGE_BASE_CATEGORY = 100;
    private static final int MAX_ORIGINAL_FILENAME = 255;

    private final AgentContextAssetRepository repository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBasePersistenceService knowledgeBasePersistenceService;
    private final PromptSanitizer promptSanitizer;

    /** 保留轻量构造方式，兼容迁移期测试和内部调用方。 */
    public AgentContextAssetPersistenceService(
        AgentContextAssetRepository repository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBasePersistenceService knowledgeBasePersistenceService
    ) {
        this(repository, knowledgeBaseRepository, knowledgeBasePersistenceService,
            new PromptSanitizer(new LlmProviderProperties()));
    }

    @Autowired
    public AgentContextAssetPersistenceService(
        AgentContextAssetRepository repository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KnowledgeBasePersistenceService knowledgeBasePersistenceService,
        PromptSanitizer promptSanitizer
    ) {
        this.repository = repository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.knowledgeBasePersistenceService = knowledgeBasePersistenceService;
        this.promptSanitizer = promptSanitizer;
    }

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
        String safeName = sanitizeMetadata(name, MAX_KNOWLEDGE_BASE_NAME);
        String safeCategory = sanitizeMetadata(category, MAX_KNOWLEDGE_BASE_CATEGORY);
        String safeFilename = sanitizeMetadata(asset.getOriginalFilename(), MAX_ORIGINAL_FILENAME);
        KnowledgeBaseEntity knowledgeBase = duplicate.orElseGet(() ->
            knowledgeBasePersistenceService.saveKnowledgeBaseFromStoredAsset(
                safeName, safeCategory, safeFilename, asset.getFileSize(), asset.getContentType(),
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

    /** 只读读取 Agent 素材对应知识库的真实向量化状态，不修改任何任务状态。 */
    @Transactional(readOnly = true)
    public KnowledgeBaseStatusSnapshot getKnowledgeBaseStatus(String assetId, String ownerId) {
        AgentContextAssetEntity asset = findReadable(assetId, ownerId);
        AgentContextAssetResponse response = AgentContextAssetResponse.from(asset);
        if (asset.getKnowledgeBaseId() == null) {
            return new KnowledgeBaseStatusSnapshot(response, null, null, null, null, null);
        }
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findAccessibleById(
            asset.getKnowledgeBaseId(), ownerId.trim()
        ).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "关联知识库不存在"));
        return new KnowledgeBaseStatusSnapshot(
            response,
            knowledgeBase.getVectorStatus(),
            knowledgeBase.getVectorError(),
            knowledgeBase.getChunkCount(),
            knowledgeBase.getVectorizedAt(),
            knowledgeBase.getVectorUpdatedAt()
        );
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
        return repository.findByAssetIdAndOwnerIdForUpdate(assetId.trim(), ownerId.trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 素材不存在"));
    }

    private AgentContextAssetEntity findReadable(String assetId, String ownerId) {
        if (assetId == null || assetId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "素材 ID 不能为空");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份已失效，请刷新页面后重试");
        }
        return repository.findByAssetIdAndOwnerId(assetId.trim(), ownerId.trim())
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 素材不存在"));
    }

    /** 知识库名称、分类和文件名会成为可检索元数据，不能绕过 Agent 资料边界直接落库。 */
    private String sanitizeMetadata(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength);
        }
        String safe = promptSanitizer.sanitizeForAction(normalized);
        return safe == null || safe.isBlank() ? null : safe;
    }

    public record KnowledgeBaseImportPreparation(
        AgentContextAssetResponse asset,
        boolean vectorizationRequired
    ) {
    }

    public record KnowledgeBaseStatusSnapshot(
        AgentContextAssetResponse asset,
        VectorStatus vectorStatus,
        String vectorError,
        Integer chunkCount,
        java.time.LocalDateTime vectorizedAt,
        java.time.LocalDateTime vectorUpdatedAt
    ) {
    }
}
