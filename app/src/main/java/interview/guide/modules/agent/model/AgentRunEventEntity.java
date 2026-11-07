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
 * Agent Run 的追加式低敏事件。
 *
 * <p>事件用于恢复诊断、运行评测和模型/工具批次关联；不保存 Prompt、工具参数正文、
 * 工具结果正文或模型隐藏推理，只保存稳定标识和摘要哈希。</p>
 */
@Entity
@Table(name = "agent_run_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "owner_key", length = 64)
    private String ownerKey;

    @Column(name = "agent_session_id", length = 64)
    private String agentSessionId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "model_turn_id", length = 64)
    private String modelTurnId;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "arguments_hash", length = 64)
    private String argumentsHash;

    @Column(name = "result_hash", length = 64)
    private String resultHash;

    @Column(length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
