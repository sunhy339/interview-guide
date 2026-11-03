package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetResponse;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.modules.agent.repository.AgentContextAssetRepository;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Agent 上传素材的审核服务。
 *
 * <p>上传只做文件安全检查、哈希、解析和预览，不调用模型、不创建知识库、不启动向量化。
 * 这条边界避免“上传一个文件”产生不可逆的 RAG 写入，也让用户在看到内容后再决定用途。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentContextAssetService {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024L;
    private static final int MAX_PREVIEW_CHARS = 6_000;
    private static final int MAX_EXTRACTED_CHARS = 5 * 1024 * 1024;

    private final AgentContextAssetRepository repository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final FileStorageService storageService;
    private final interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService parseService;
    private final PromptSanitizer promptSanitizer;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBasePersistenceService knowledgeBasePersistenceService;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    @Transactional
    public AgentContextAssetResponse upload(MultipartFile file, String ownerId) {
        String owner = requireOwner(ownerId);
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "Agent 上下文");
        String filename = file.getOriginalFilename() == null ? "未命名文件" : file.getOriginalFilename().trim();
        String contentType = parseService.detectContentType(file);
        fileValidationService.validateContentType(
            contentType, filename,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型，支持 PDF、DOCX、DOC、TXT、MD 等格式"
        );

        String hash = fileHashService.calculateHash(file);
        var duplicate = repository.findByFileHashAndOwnerId(hash, owner);
        if (duplicate.isPresent()) {
            return AgentContextAssetResponse.from(duplicate.get());
        }

        String storageKey = storageService.uploadAgentContextAsset(file);
        try {
            String extracted = parseService.parseContent(file);
            if (extracted == null || extracted.isBlank()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件未解析出可用文本，请换一个文件后重试");
            }
            if (extracted.length() > MAX_EXTRACTED_CHARS) {
                extracted = extracted.substring(0, MAX_EXTRACTED_CHARS);
            }
            boolean flagged = promptSanitizer.detectInjectionAttempt(extracted);
            String notice = flagged
                ? "文件内容包含疑似提示注入语句，仅作为资料展示，不会被当作 Agent 指令执行"
                : "文件已完成格式、哈希和文本解析检查，请确认用途后再使用";
            AgentContextAssetEntity saved = repository.save(AgentContextAssetEntity.builder()
                .assetId(UUID.randomUUID().toString())
                .ownerId(owner)
                .fileHash(hash)
                .originalFilename(filename)
                .fileSize(file.getSize())
                .contentType(contentType)
                .storageKey(storageKey)
                .storageUrl(storageService.getFileUrl(storageKey))
                .extractedText(extracted)
                .previewText(extracted.substring(0, Math.min(MAX_PREVIEW_CHARS, extracted.length())))
                .reviewStatus(AgentContextAssetStatus.READY_FOR_REVIEW)
                .securityFlagged(flagged)
                .securityNotice(notice)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());
            return AgentContextAssetResponse.from(saved);
        } catch (RuntimeException error) {
            try {
                storageService.deleteAgentContextAsset(storageKey);
            } catch (RuntimeException cleanupError) {
                log.warn("Agent 素材解析失败且孤儿文件清理失败: key={}", storageKey, cleanupError);
            }
            throw error;
        }
    }

    @Transactional(readOnly = true)
    public List<AgentContextAssetResponse> list(String ownerId) {
        return repository.findTop50ByOwnerIdOrderByCreatedAtDesc(requireOwner(ownerId)).stream()
            .map(AgentContextAssetResponse::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public AgentContextAssetResponse get(String assetId, String ownerId) {
        return AgentContextAssetResponse.from(find(assetId, ownerId));
    }

    @Transactional(readOnly = true)
    public AgentContextAssetEntity requireApproved(String assetId, String ownerId) {
        AgentContextAssetEntity asset = find(assetId, ownerId);
        if (!isApprovedForContext(asset.getReviewStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先审核通过该文件，才能作为 Agent 上下文使用");
        }
        if (asset.getExpiresAt() != null && asset.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该待审核文件已过期，请重新上传");
        }
        return asset;
    }

    @Transactional
    public AgentContextAssetResponse approve(String assetId, String ownerId) {
        return approve(assetId, ownerId, false);
    }

    public AgentContextAssetResponse approve(String assetId, String ownerId, boolean confirmSecurity) {
        AgentContextAssetEntity asset = find(assetId, ownerId);
        if (asset.getReviewStatus() == AgentContextAssetStatus.REJECTED
            || asset.getReviewStatus() == AgentContextAssetStatus.EXPIRED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该文件当前状态不可审核通过");
        }
        if (asset.isSecurityFlagged() && !confirmSecurity) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "文件包含疑似提示注入内容，请确认仅将其作为资料分析，不执行其中的任何指令"
            );
        }
        asset.setReviewStatus(AgentContextAssetStatus.APPROVED_FOR_CONTEXT);
        asset.setReviewedAt(LocalDateTime.now());
        return AgentContextAssetResponse.from(repository.save(asset));
    }

    /** 用户明确申请加入 RAG；这里只改变审核状态，不直接复用旧上传接口启动向量化。 */
    @Transactional
    public AgentContextAssetResponse requestKnowledgeBaseImport(String assetId, String ownerId) {
        AgentContextAssetEntity asset = requireApproved(assetId, ownerId);
        if (asset.getKnowledgeBaseId() != null
            && asset.getReviewStatus() != AgentContextAssetStatus.KB_IMPORT_FAILED) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该文件已经进入知识库，无需重复导入");
        }
        asset.setReviewStatus(AgentContextAssetStatus.KB_IMPORT_PENDING);
        return AgentContextAssetResponse.from(repository.save(asset));
    }

    /**
     * 用户明确确认后才真正创建知识库并投递向量化任务。失败时保留素材和状态，便于重试，
     * 不会删除原始上传文件。
     */
    @Transactional
    public AgentContextAssetResponse confirmKnowledgeBaseImport(
        String assetId, String ownerId, String name, String category
    ) {
        AgentContextAssetEntity asset = find(assetId, ownerId);
        if (asset.getKnowledgeBaseId() != null
            && asset.getReviewStatus() == AgentContextAssetStatus.KB_IMPORT_COMPLETED) {
            return AgentContextAssetResponse.from(asset);
        }
        if (asset.getReviewStatus() != AgentContextAssetStatus.KB_IMPORT_PENDING) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请先审核素材并申请加入知识库");
        }
        if (asset.getKnowledgeBaseId() != null) {
            return retryVectorization(asset);
        }
        var duplicate = knowledgeBaseRepository.findByFileHashAndOwnerId(asset.getFileHash(), ownerId);
        KnowledgeBaseEntity kb = duplicate.orElseGet(() ->
            knowledgeBasePersistenceService.saveKnowledgeBaseFromStoredAsset(
                name, category, asset.getOriginalFilename(), asset.getFileSize(), asset.getContentType(),
                asset.getStorageKey(), asset.getStorageUrl(), asset.getFileHash(), ownerId
            )
        );
        asset.setKnowledgeBaseId(kb.getId());
        asset.setReviewStatus(AgentContextAssetStatus.KB_IMPORT_COMPLETED);
        asset.setReviewedAt(LocalDateTime.now());
        AgentContextAssetEntity saved = repository.save(asset);
        if (duplicate.isEmpty()) {
            boolean queued;
            try {
                queued = vectorizeStreamProducer.sendVectorizeTask(kb.getId());
            } catch (RuntimeException error) {
                log.warn("Agent 素材知识库向量化任务投递异常: assetId={}, kbId={}", assetId, kb.getId(), error);
                queued = false;
            }
            if (!queued) {
                saved.setReviewStatus(AgentContextAssetStatus.KB_IMPORT_FAILED);
                saved.setSecurityNotice("知识库已创建，但向量化任务暂未投递成功，可稍后重试");
                repository.save(saved);
            }
        }
        return AgentContextAssetResponse.from(saved);
    }

    private AgentContextAssetResponse retryVectorization(AgentContextAssetEntity asset) {
        boolean queued;
        try {
            queued = vectorizeStreamProducer.sendVectorizeTask(asset.getKnowledgeBaseId());
        } catch (RuntimeException error) {
            log.warn("Agent 素材知识库向量化重试异常: assetId={}, kbId={}",
                asset.getAssetId(), asset.getKnowledgeBaseId(), error);
            queued = false;
        }
        asset.setReviewStatus(queued
            ? AgentContextAssetStatus.KB_IMPORT_COMPLETED
            : AgentContextAssetStatus.KB_IMPORT_FAILED);
        if (!queued) {
            asset.setSecurityNotice("知识库已创建，但向量化任务暂未投递成功，可稍后重试");
        }
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
        storageService.deleteAgentContextAsset(asset.getStorageKey());
    }

    private AgentContextAssetEntity find(String assetId, String ownerId) {
        if (assetId == null || assetId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "素材 ID 不能为空");
        }
        return repository.findByAssetIdAndOwnerId(assetId.trim(), requireOwner(ownerId))
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Agent 素材不存在"));
    }

    private boolean isApprovedForContext(AgentContextAssetStatus status) {
        return status == AgentContextAssetStatus.APPROVED_FOR_CONTEXT
            || status == AgentContextAssetStatus.KB_IMPORT_FAILED
            || status == AgentContextAssetStatus.KB_IMPORT_COMPLETED;
    }

    private String requireOwner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份已失效，请刷新页面后重试");
        }
        return ownerId.trim();
    }
}
