package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent 私有上传素材；审核通过前不会创建知识库或向量任务。 */
@Entity
@Table(name = "agent_context_assets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentContextAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false, unique = true, length = 36)
    private String assetId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "storage_url", length = 1000)
    private String storageUrl;

    /** 解析后的私有文本；上下文注入前还会再次经过 PromptSanitizer 和长度预算。 */
    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Column(name = "preview_text", columnDefinition = "TEXT")
    private String previewText;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    @Builder.Default
    private AgentContextAssetStatus reviewStatus = AgentContextAssetStatus.READY_FOR_REVIEW;

    /** 只做安全提示，不把文件直接当成恶意文件拒绝；最终仍需要用户明确审核。 */
    @Column(name = "security_flagged", nullable = false)
    @Builder.Default
    private boolean securityFlagged = false;

    @Column(name = "security_notice", length = 500)
    private String securityNotice;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "knowledge_base_id")
    private Long knowledgeBaseId;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (expiresAt == null) {
            expiresAt = now.plusDays(7);
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
