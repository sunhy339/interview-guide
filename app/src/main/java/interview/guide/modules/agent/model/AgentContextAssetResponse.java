package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** 不返回完整原文，避免列表接口意外泄露大段资料。 */
public record AgentContextAssetResponse(
    String assetId,
    String originalFilename,
    Long fileSize,
    String contentType,
    AgentContextAssetStatus reviewStatus,
    boolean securityFlagged,
    String securityNotice,
    String previewText,
    Long knowledgeBaseId,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime expiresAt
) {
    public static AgentContextAssetResponse from(AgentContextAssetEntity asset) {
        return new AgentContextAssetResponse(
            asset.getAssetId(), asset.getOriginalFilename(), asset.getFileSize(), asset.getContentType(),
            asset.getReviewStatus(), asset.isSecurityFlagged(), asset.getSecurityNotice(), asset.getPreviewText(),
            asset.getKnowledgeBaseId(), asset.getCreatedAt(), asset.getUpdatedAt(), asset.getExpiresAt()
        );
    }
}
