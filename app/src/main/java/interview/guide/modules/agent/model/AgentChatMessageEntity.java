package interview.guide.modules.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Agent 会话中的一条用户或助手消息。 */
@Entity
@Table(name = "agent_chat_messages", uniqueConstraints = {
    @UniqueConstraint(name = "uk_agent_message_session_sequence", columnNames = {"session_id", "sequence_num"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private AgentChatSessionEntity session;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private MessageRole role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "sequence_num", nullable = false)
    private Integer sequenceNum;

    @Column(nullable = false)
    @Builder.Default
    private Boolean completed = true;

    /** 用户在当前 Agent 运行期间发送、等待下一次工具回合处理的补充输入。 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean steering = false;

    /** 补充输入已经注入当前运行的工具结果后，不能再次注入。 */
    @Column(name = "steering_consumed", nullable = false)
    @Builder.Default
    private Boolean steeringConsumed = false;

    /** 只允许注入接收该消息时的运行代次，避免跨运行串入。 */
    @Column(name = "steering_run_id", length = 36)
    private String steeringRunId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum MessageRole {
        USER,
        ASSISTANT
    }
}
