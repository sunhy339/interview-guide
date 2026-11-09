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

/**
 * Agent 运行检查点。
 *
 * <p>它是会话运行态的兼容扩展，不替换现有会话表。原始参数、工具结果和模型隐藏推理
 * 不落库，只保存用于恢复、审批和审计关联的摘要。</p>
 */
@Entity
@Table(name = "agent_run_states")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRunStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false, unique = true, length = 64)
    private String runId;

    @Column(name = "owner_key", length = 64)
    private String ownerKey;

    @Column(name = "interview_session_id", length = 64)
    private String interviewSessionId;

    @Column(name = "agent_session_id", nullable = false, length = 64)
    private String agentSessionId;

    /** RunState 持久化契约版本；旧记录为空时按 v1 兼容读取。 */
    @Column(name = "checkpoint_version")
    private Integer checkpointVersion;

    @Column(name = "assistant_message_id")
    private Long assistantMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentRunStatus status;

    @Column(length = 32)
    private String phase;

    /**
     * 应用重启后用于判断能否安全重试的最后边界；不保存 Prompt、工具参数正文或结果正文。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_boundary", length = 32)
    private AgentRecoveryBoundary recoveryBoundary;

    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    @Column(name = "tool_call_id", length = 128)
    private String toolCallId;

    @Column(name = "tool_name", length = 128)
    private String toolName;

    @Column(name = "tool_arguments_hash", length = 64)
    private String toolArgumentsHash;

    @Column(name = "tool_result_reference", length = 128)
    private String toolResultReference;

    /** 工具结果的版本化契约摘要；不保存工具结果正文。 */
    @Column(name = "tool_result_contract_version", length = 64)
    private String toolResultContractVersion;

    /** 工具结果的有限状态摘要；不保存工具结果正文。 */
    @Column(name = "tool_result_status", length = 64)
    private String toolResultStatus;

    /** 当前或最近一次模型轮次的稳定摘要标识；不保存请求正文或模型隐藏推理。 */
    @Column(name = "model_turn_id", length = 64)
    private String modelTurnId;

    @Column(name = "model_provider", length = 64)
    private String modelProvider;

    /** STARTED、SUCCEEDED、FAILED、CANCELLED 或 INTERRUPTED。 */
    @Column(name = "model_turn_status", length = 16)
    private String modelTurnStatus;

    @Column(name = "model_turn_index", nullable = false)
    private Integer modelTurnIndex;

    /** 仅保存状态摘要，不能据此推断模型输出正文。 */
    @Column(name = "model_result_reference", length = 128)
    private String modelResultReference;

    /** 最近一次模型轮次看到的 Provider 响应元数据；不保存消息正文。 */
    @Column(name = "provider_response_id", length = 128)
    private String providerResponseId;

    @Column(name = "provider_response_ids_hash", length = 64)
    private String providerResponseIdsHash;

    @Column(name = "provider_tool_call_ids_hash", length = 64)
    private String providerToolCallIdsHash;

    @Column(name = "provider_response_count")
    private Integer providerResponseCount;

    @Column(name = "provider_tool_call_count")
    private Integer providerToolCallCount;

    /** 审批事务已提交但续跑任务尚未启动时，用于重启后找回原动作。 */
    @Column(name = "resume_action_id", length = 64)
    private String resumeActionId;

    @Column(name = "resume_notice", length = 500)
    private String resumeNotice;

    @Column(name = "pending_approval", nullable = false)
    private Boolean pendingApproval;

    @Column(name = "context_version", nullable = false)
    private Integer contextVersion;

    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (stepIndex == null) {
            stepIndex = 0;
        }
        if (pendingApproval == null) {
            pendingApproval = false;
        }
        if (contextVersion == null) {
            contextVersion = 1;
        }
        if (attempt == null) {
            attempt = 0;
        }
        if (modelTurnIndex == null) {
            modelTurnIndex = 0;
        }
        if (checkpointVersion == null) {
            checkpointVersion = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
