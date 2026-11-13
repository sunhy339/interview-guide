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

/** 用户上传的 Skill 版本；Skill 正文只作为受审核的任务方法资料保存。 */
@Entity
@Table(name = "agent_user_skills")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentUserSkillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "skill_id", nullable = false, unique = true, length = 36)
    private String skillId;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "skill_key", nullable = false, length = 64)
    private String skillKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "version", nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Builder.Default
    private AgentUserSkillStatus status = AgentUserSkillStatus.PENDING_REVIEW;

    @Column(name = "security_flagged", nullable = false)
    @Builder.Default
    private boolean securityFlagged = false;

    @Column(name = "security_notice", length = 500)
    private String securityNotice;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "enabled_at")
    private LocalDateTime enabledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (version == null) {
            version = 1;
        }
        if (status == null) {
            status = AgentUserSkillStatus.PENDING_REVIEW;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
