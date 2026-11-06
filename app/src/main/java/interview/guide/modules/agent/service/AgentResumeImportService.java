package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.resume.listener.AnalyzeStreamProducer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumePersistenceService;
import jakarta.transaction.Transactional;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 将已经审核的 Agent 上传素材导入简历管理。
 *
 * <p>导入只接受已审核素材，保存时复制对象存储文件而不是复用原 key，
 * 这样删除简历不会影响 Agent 原始上传文件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentResumeImportService {

    private static final long MAX_RESUME_FILE_SIZE = 10 * 1024 * 1024L;

    private final AgentContextAssetService contextAssetService;
    private final ResumeRepository resumeRepository;
    private final FileStorageService storageService;
    private final ResumePersistenceService persistenceService;
    private final AnalyzeStreamProducer analyzeStreamProducer;

    public AgentContextAssetEntity requireImportableAsset(String assetId, String ownerKey) {
        AgentContextAssetEntity asset = contextAssetService.requireApproved(assetId, ownerKey);
        validateResumeSize(asset);
        if (asset.getExtractedText() == null || asset.getExtractedText().isBlank()) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "文件未解析出可用简历内容");
        }
        return asset;
    }

    @Transactional
    public ResumeImportResult importApprovedAsset(String assetId, String ownerKey) {
        AgentContextAssetEntity asset = requireImportableAsset(assetId, ownerKey);
        Optional<ResumeEntity> existing = resumeRepository.findByFileHashAndOwnerId(
            asset.getFileHash(), ownerKey
        );
        if (existing.isPresent()) {
            ResumeEntity resume = existing.get();
            resume.incrementAccessCount();
            resumeRepository.save(resume);
            return new ResumeImportResult(
                resume.getId(), resume.getOriginalFilename(), true, true,
                "这份文件已经在简历管理中，未重复创建"
            );
        }

        String copiedKey = storageService.copyAgentContextAssetToResume(
            asset.getStorageKey(), asset.getOriginalFilename()
        );
        try {
            ResumeEntity saved = persistenceService.saveImportedResume(
                ownerKey, asset.getFileHash(), asset.getOriginalFilename(), asset.getFileSize(),
                asset.getContentType(), copiedKey, storageService.getFileUrl(copiedKey),
                asset.getExtractedText()
            );
            boolean queued = analyzeStreamProducer.sendAnalyzeTask(saved.getId());
            return new ResumeImportResult(
                saved.getId(), saved.getOriginalFilename(), false, queued,
                queued ? "文件已加入简历管理，简历分析已提交后台处理"
                    : "文件已加入简历管理，但分析任务暂未投递成功"
            );
        } catch (RuntimeException error) {
            try {
                storageService.deleteResume(copiedKey);
            } catch (RuntimeException cleanupError) {
                log.warn("导入简历失败且副本清理失败: key={}", copiedKey, cleanupError);
            }
            throw error;
        }
    }

    private void validateResumeSize(AgentContextAssetEntity asset) {
        if (asset.getFileSize() == null || asset.getFileSize() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件大小无效，不能导入为简历");
        }
        if (asset.getFileSize() > MAX_RESUME_FILE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "简历文件不能超过10MB，请压缩文件后重试");
        }
    }

    public record ResumeImportResult(
        Long resumeId,
        String originalFilename,
        boolean duplicate,
        boolean enqueueAccepted,
        String message
    ) {
    }
}
