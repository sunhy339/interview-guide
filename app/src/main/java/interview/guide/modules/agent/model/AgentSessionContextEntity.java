package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 会话当前使用的业务上下文引用。
 *
 * <p>这里只保存资源引用，不复制面试报告内容。报告内容由上下文编排服务按需读取，
 * 因此普通 Agent 会话可以不绑定任何面试。</p>
 */
@Entity
@Table(name = "agent_session_contexts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSessionContextEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_session_id", nullable = false, length = 36)
    private String agentSessionId;

    @Column(name = "context_type", nullable = false, length = 32)
    private String contextType;

    @Column(name = "interview_session_id", length = 36)
    private String interviewSessionId;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "resource_name", length = 255)
    private String resourceName;

    @Column(name = "scope", nullable = false, length = 16)
    @Builder.Default
    private String scope = "SESSION";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "attached_at", nullable = false, updatable = false)
    private LocalDateTime attachedAt;

    @Column(name = "detached_at")
    private LocalDateTime detachedAt;

    @Column(name = "source_message_id")
    private Long sourceMessageId;

    @PrePersist
    protected void onCreate() {
        if (attachedAt == null) {
            attachedAt = LocalDateTime.now();
        }
    }
}
