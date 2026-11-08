package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAsyncOperationEntity;
import interview.guide.modules.agent.model.AgentAsyncOperationStatus;
import interview.guide.modules.agent.repository.AgentAsyncOperationRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护 Agent 发起的异步任务与原始 Run 的轻量对账关系。
 *
 * <p>它不接管 Redis Stream，也不改变知识库任务本身的重试策略；只把状态变化反映到
 * Agent 可查询的低敏 Run 事件中。</p>
 */
@Slf4j
@Service
public class AgentAsyncOperationService {

    public static final String KNOWLEDGE_BASE_VECTORIZE = "KB_VECTORIZE";

    private final AgentAsyncOperationRepository repository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AgentRunEventService runEventService;

    public AgentAsyncOperationService(
        AgentAsyncOperationRepository repository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        AgentRunEventService runEventService
    ) {
        this.repository = repository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.runEventService = runEventService;
    }

    /** 在 Agent 工具得到“已投递”事实后登记异步任务，重复确认同一 Run 不重复登记。 */
    @Transactional
    public AgentAsyncOperationEntity registerKnowledgeBaseVectorization(
        String ownerKey,
        String agentSessionId,
        String runId,
        String assetId,
        Long knowledgeBaseId
    ) {
        require(ownerKey, "ownerKey");
        if (blank(runId) || knowledgeBaseId == null) {
            return null;
        }
        AgentAsyncOperationEntity existing = repository
            .findByOwnerKeyAndRunIdAndKnowledgeBaseIdAndOperation(
                ownerKey.trim(), runId.trim(), knowledgeBaseId, KNOWLEDGE_BASE_VECTORIZE
            )
            .orElse(null);
        if (existing != null) {
            return existing;
        }
        AgentAsyncOperationEntity operation = AgentAsyncOperationEntity.builder()
            .operationId(UUID.randomUUID().toString())
            .ownerKey(ownerKey.trim())
            .agentSessionId(clean(agentSessionId, 64))
            .runId(clean(runId, 64))
            .assetId(clean(assetId, 64))
            .knowledgeBaseId(knowledgeBaseId)
            .operation(KNOWLEDGE_BASE_VECTORIZE)
            .status(AgentAsyncOperationStatus.SUBMITTED)
            .attempt(0)
            .build();
        AgentAsyncOperationEntity saved = repository.save(operation);
        appendEvent(saved, "SUBMITTED", "async_job_submitted");

        // 处理“任务已完成但登记稍晚”的竞态，最终状态仍以知识库真实状态为准。
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null);
        if (knowledgeBase != null && knowledgeBase.getVectorStatus() == VectorStatus.COMPLETED) {
            transition(saved, AgentAsyncOperationStatus.COMPLETED, "vector_index_ready", true);
        } else if (knowledgeBase != null && knowledgeBase.getVectorStatus() == VectorStatus.FAILED) {
            transition(saved, AgentAsyncOperationStatus.FAILED,
                safeDetail(knowledgeBase.getVectorError(), "vector_index_failed"), true);
        }
        return saved;
    }

    @Transactional
    public void markProcessing(Long knowledgeBaseId) {
        transitionAll(knowledgeBaseId, AgentAsyncOperationStatus.PROCESSING,
            "vector_index_processing", false,
            AgentAsyncOperationStatus.SUBMITTED, AgentAsyncOperationStatus.RETRYING);
    }

    @Transactional
    public void markRetrying(Long knowledgeBaseId, String detail) {
        transitionAll(knowledgeBaseId, AgentAsyncOperationStatus.RETRYING,
            safeDetail(detail, "vector_index_retrying"), false,
            AgentAsyncOperationStatus.SUBMITTED, AgentAsyncOperationStatus.PROCESSING,
            AgentAsyncOperationStatus.RETRYING);
    }

    @Transactional
    public void markCompleted(Long knowledgeBaseId) {
        transitionAll(knowledgeBaseId, AgentAsyncOperationStatus.COMPLETED,
            "vector_index_ready", true,
            AgentAsyncOperationStatus.SUBMITTED, AgentAsyncOperationStatus.PROCESSING,
            AgentAsyncOperationStatus.RETRYING);
    }

    @Transactional
    public void markFailed(Long knowledgeBaseId, String detail) {
        transitionAll(knowledgeBaseId, AgentAsyncOperationStatus.FAILED,
            safeDetail(detail, "vector_index_failed"), true,
            AgentAsyncOperationStatus.SUBMITTED, AgentAsyncOperationStatus.PROCESSING,
            AgentAsyncOperationStatus.RETRYING);
    }

    private void transitionAll(
        Long knowledgeBaseId,
        AgentAsyncOperationStatus target,
        String detail,
        boolean terminal,
        AgentAsyncOperationStatus... allowed
    ) {
        if (knowledgeBaseId == null) {
            return;
        }
        List<AgentAsyncOperationEntity> operations = repository
            .findByKnowledgeBaseIdAndOperation(knowledgeBaseId, KNOWLEDGE_BASE_VECTORIZE);
        for (AgentAsyncOperationEntity operation : operations) {
            if (operation.getStatus() == null || !contains(allowed, operation.getStatus())) {
                continue;
            }
            transition(operation, target, detail, terminal);
        }
    }

    private void transition(
        AgentAsyncOperationEntity operation,
        AgentAsyncOperationStatus target,
        String detail,
        boolean terminal
    ) {
        if (operation.getStatus() == target && (!terminal || operation.getCompletedAt() != null)) {
            return;
        }
        operation.setStatus(target);
        operation.setDetail(safeDetail(detail, target.name().toLowerCase(java.util.Locale.ROOT)));
        int attempt = operation.getAttempt() == null ? 0 : Math.max(0, operation.getAttempt());
        operation.setAttempt(attempt + 1);
        if (terminal) {
            operation.setCompletedAt(LocalDateTime.now());
        }
        repository.save(operation);
        appendEvent(operation, target.name(), operation.getDetail());
    }

    private void appendEvent(AgentAsyncOperationEntity operation, String status, String detail) {
        if (runEventService == null || blank(operation.getRunId()) || blank(operation.getAgentSessionId())) {
            return;
        }
        runEventService.appendForContext(
            operation.getOwnerKey(), operation.getAgentSessionId(), operation.getRunId(),
            "ASYNC_OPERATION_" + status, operation.getAssetId(), KNOWLEDGE_BASE_VECTORIZE,
            detail, status
        );
    }

    private boolean contains(AgentAsyncOperationStatus[] statuses, AgentAsyncOperationStatus value) {
        for (AgentAsyncOperationStatus status : statuses) {
            if (status == value) {
                return true;
            }
        }
        return false;
    }

    private String safeDetail(String value, String fallback) {
        String normalized = blank(value) ? fallback : value.replaceAll("[\\p{Cntrl}]", " ")
            .replaceAll("\\s+", " ").trim();
        return normalized.substring(0, Math.min(500, normalized.length()));
    }

    private String clean(String value, int max) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized.substring(0, Math.min(max, normalized.length()));
    }

    private void require(String value, String field) {
        if (blank(value)) {
            throw new IllegalArgumentException(field + "不能为空");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
