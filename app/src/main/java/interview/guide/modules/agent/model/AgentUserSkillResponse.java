package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** 用户 Skill 的安全展示对象；正文只在用户打开详情时返回。 */
public record AgentUserSkillResponse(
    String skillId,
    String skillKey,
    String originalFilename,
    String name,
    String description,
    String content,
    Integer version,
    AgentUserSkillStatus status,
    boolean securityFlagged,
    String securityNotice,
    LocalDateTime reviewedAt,
    LocalDateTime enabledAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static AgentUserSkillResponse from(AgentUserSkillEntity entity, boolean includeContent) {
        return new AgentUserSkillResponse(
            entity.getSkillId(), entity.getSkillKey(), entity.getOriginalFilename(),
            entity.getName(), entity.getDescription(), includeContent ? entity.getContent() : null,
            entity.getVersion(), entity.getStatus(), entity.isSecurityFlagged(), entity.getSecurityNotice(),
            entity.getReviewedAt(), entity.getEnabledAt(), entity.getCreatedAt(), entity.getUpdatedAt()
        );
    }
}
