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

/** Agent 异步领域任务与原始 Run 的低敏关联。 */
@Entity
@Table(name = "agent_async_operations")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAsyncOperationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "operation_id", nullable = false, unique = true, length = 64)
    private String operationId;

    @Column(name = "owner_key", nullable = false, length = 64)
    private String ownerKey;

    @Column(name = "agent_session_id", length = 64)
    private String agentSessionId;

    @Column(name = "run_id", length = 64)
    private String runId;

    @Column(name = "asset_id", length = 64)
    private String assetId;

    @Column(name = "knowledge_base_id")
    private Long knowledgeBaseId;

    @Column(nullable = false, length = 64)
    private String operation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AgentAsyncOperationStatus status;

    @Column(length = 500)
    private String detail;

    @Column(nullable = false)
    private Integer attempt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (attempt == null) {
            attempt = 0;
        }
        if (status == null) {
            status = AgentAsyncOperationStatus.SUBMITTED;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
