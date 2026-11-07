package interview.guide.modules.agent.service;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStateEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentRunStateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.HexFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent 运行检查点服务。
 *
 * <p>该服务只保存可恢复的运行元数据和摘要，不保存原始用户消息、文件正文或模型隐藏推理。
 * 没有检查点时不影响现有会话流程，便于迁移期逐步接入。</p>
 */
@Service
@Slf4j
public class AgentRunStateService {
    private static final int LEASE_MINUTES = 5;
    private static final int MAX_PHASE = 32;
    private static final int MAX_TOOL_NAME = 128;
    private static final int MAX_MODEL_PROVIDER = 64;

    private final AgentRunStateRepository repository;
    private AgentRunEventService eventService;

    public AgentRunStateService(AgentRunStateRepository repository) {
        this.repository = repository;
    }

    /** 新增事件表采用可选注入，迁移期间缺少表时不影响原有检查点。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setEventService(AgentRunEventService eventService) {
        this.eventService = eventService;
    }

    public void start(AgentChatSessionEntity session) {
        runSafely("启动", session == null ? null : session.getRunId(), () -> {
            if (session == null || blank(session.getRunId()) || blank(session.getAgentSessionId())) {
                return;
            }
            AgentRunStateEntity state = repository.findByRunId(session.getRunId())
                .orElseGet(() -> AgentRunStateEntity.builder().runId(session.getRunId()).build());
            state.setOwnerKey(clean(session.getOwnerKey(), 64));
            state.setInterviewSessionId(clean(session.getInterviewSessionId(), 64));
            state.setAgentSessionId(clean(session.getAgentSessionId(), 64));
            state.setAssistantMessageId(session.getActiveMessageId());
            state.setStatus(session.getRunStatus() == null ? AgentRunStatus.RUNNING : session.getRunStatus());
            state.setPhase(clean("context", MAX_PHASE));
            state.setStepIndex(Math.max(0, session.getLoopNextStep()));
            state.setPendingApproval(false);
            state.setContextVersion(1);
            state.setAttempt(Math.max(1, value(state.getAttempt())));
            state.setModelTurnId(null);
            state.setModelProvider(null);
            state.setModelTurnStatus(null);
            state.setModelResultReference(null);
            heartbeat(state);
            state.setFinishedAt(null);
            repository.save(state);
        });
    }

    /**
     * 在真正订阅模型流前落一个模型轮次检查点。
     *
     * <p>这里记录的是轮次和提供商元数据，不记录提示词、工具参数、资料正文或隐藏
     * 推理。进程在模型流中断时，恢复扫描可以知道上一个安全边界是“模型轮次中间”，
     * 后续只能安全重试/重新生成，不能假装精确恢复了模型内部上下文。</p>
     */
    public void modelTurnStarted(
        AgentMetrics.RunTracker tracker, String providerId, boolean compacted
    ) {
        modelTurnStarted(tracker, providerId, compacted, null);
    }

    /**
     * 记录带有编排层轮次标识的模型检查点，便于把持久化状态与低敏 Trace 对齐。
     * 旧的三参数入口继续使用服务端生成的兼容 ID。
     */
    public void modelTurnStarted(
        AgentMetrics.RunTracker tracker,
        String providerId,
        boolean compacted,
        String requestedTurnId
    ) {
        String runId = runId(tracker);
        runSafely("模型轮次开始", runId, () -> {
            if (blank(runId)) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(runId);
            int index = value(state.getModelTurnIndex()) + 1;
            state.setModelTurnIndex(index);
            String safeTurnId = clean(requestedTurnId, 64);
            state.setModelTurnId(safeTurnId == null
                ? "turn-" + hash(runId + ":" + index).substring(0, 32)
                : safeTurnId);
            state.setModelProvider(clean(providerId, MAX_MODEL_PROVIDER));
            state.setModelTurnStatus("STARTED");
            state.setModelResultReference(null);
            state.setPhase(clean(compacted ? "model_compacted" : "model", MAX_PHASE));
            state.setFinishedAt(null);
            heartbeat(state);
            repository.save(state);
            if (eventService != null) {
                eventService.append(tracker, "MODEL_TURN_STARTED", state.getModelTurnId(), null,
                    null, null, null, compacted ? "compacted" : "normal");
            }
        });
    }

    /** 模型轮次结束只保存有限状态，禁止把自然语言正文写入检查点。 */
    public void modelTurnFinished(
        AgentMetrics.RunTracker tracker, String providerId, String result
    ) {
        String runId = runId(tracker);
        runSafely("模型轮次结束", runId, () -> {
            if (blank(runId)) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(runId);
            String status = normalizeModelTurnStatus(result);
            state.setModelProvider(clean(providerId, MAX_MODEL_PROVIDER));
            if (blank(state.getModelTurnId())) {
                int index = Math.max(1, value(state.getModelTurnIndex()));
                state.setModelTurnIndex(index);
                state.setModelTurnId("turn-" + hash(runId + ":" + index).substring(0, 32));
            }
            state.setModelTurnStatus(status);
            state.setModelResultReference("status:" + status.toLowerCase(java.util.Locale.ROOT));
            state.setPhase("model");
            heartbeat(state);
            repository.save(state);
            if (eventService != null) {
                eventService.append(tracker, "MODEL_TURN_COMPLETED", state.getModelTurnId(), null,
                    null, null, null, status);
            }
        });
    }

    /**
     * 恢复接管前标记旧轮次为中断，帮助诊断“重新生成”与“精确续跑”的差异。
     * 该标记不改变会话终态，也不触发任何业务工具重放。
     */
    public void markInterrupted(String runId) {
        runSafely("标记模型轮次中断", runId, () -> {
            if (blank(runId)) {
                return;
            }
            AgentRunStateEntity state = repository.findByRunId(runId).orElse(null);
            if (state == null || !"STARTED".equals(state.getModelTurnStatus())) {
                return;
            }
            state.setModelTurnStatus("INTERRUPTED");
            state.setModelResultReference("status:interrupted");
            state.setPhase("model_interrupted");
            state.setFinishedAt(LocalDateTime.now());
            heartbeat(state);
            repository.save(state);
        });
    }

    public void checkpoint(AgentChatSessionEntity session) {
        runSafely("检查点", session == null ? null : session.getRunId(), () -> {
            if (session == null || blank(session.getRunId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(session.getRunId());
            state.setOwnerKey(clean(session.getOwnerKey(), 64));
            state.setInterviewSessionId(clean(session.getInterviewSessionId(), 64));
            state.setAgentSessionId(clean(session.getAgentSessionId(), 64));
            state.setAssistantMessageId(session.getActiveMessageId());
            state.setStatus(session.getRunStatus() == null ? AgentRunStatus.RUNNING : session.getRunStatus());
            state.setPhase(clean(session.getRunStep(), MAX_PHASE));
            state.setStepIndex(Math.max(0, session.getLoopNextStep()));
            heartbeat(state);
            repository.save(state);
        });
    }

    /**
     * 延长仍在等待模型/重试的运行租约。
     *
     * <p>恢复扫描按会话开始时间筛选候选，但不能仅凭开始时间判断任务已经失联。
     * 心跳有效时，其他实例不得接管该运行；数据库读取失败时按“仍有租约”处理，
     * 让安全性优先于自动恢复。</p>
     */
    public void heartbeat(AgentMetrics.RunTracker tracker) {
        String runId = runId(tracker);
        runSafely("心跳", runId, () -> {
            if (blank(runId)) {
                return;
            }
            AgentRunStateEntity state = repository.findByRunId(runId).orElse(null);
            if (state == null || state.getStatus() != AgentRunStatus.RUNNING) {
                return;
            }
            heartbeat(state);
            repository.save(state);
        });
    }

    /** 返回当前运行是否仍受有效租约保护。读取失败时保守地返回 true。 */
    public boolean hasLiveLease(String runId) {
        if (blank(runId)) {
            return false;
        }
        try {
            return repository.findByRunId(runId)
                .map(state -> state.getStatus() == AgentRunStatus.RUNNING
                    && state.getLeaseUntil() != null
                    && state.getLeaseUntil().isAfter(LocalDateTime.now()))
                .orElse(false);
        } catch (RuntimeException error) {
            log.warn("读取 Agent 运行租约失败，暂不接管运行: runId={}", runId, error);
            return true;
        }
    }

    /** 审批结果已提交后，复用原 runId 重新进入可执行状态。 */
    public void resume(AgentChatSessionEntity session) {
        resume(session, null, null);
    }

    /** 审批结果已提交后先落一个可恢复标记，再由事务提交后的事件启动续跑。 */
    public void resume(AgentChatSessionEntity session, String actionId, String notice) {
        runSafely("恢复", session == null ? null : session.getRunId(), () -> {
            if (session == null || blank(session.getRunId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(session.getRunId());
            state.setOwnerKey(clean(session.getOwnerKey(), 64));
            state.setInterviewSessionId(clean(session.getInterviewSessionId(), 64));
            state.setAgentSessionId(clean(session.getAgentSessionId(), 64));
            state.setAssistantMessageId(session.getActiveMessageId());
            state.setStatus(AgentRunStatus.RUNNING);
            state.setPhase(clean(
                blank(actionId) ? "resume" : "resume_pending", MAX_PHASE
            ));
            state.setPendingApproval(false);
            state.setResumeActionId(clean(actionId, 64));
            state.setResumeNotice(clean(notice, 500));
            state.setAttempt(value(state.getAttempt()) + 1);
            state.setFinishedAt(null);
            heartbeat(state);
            repository.save(state);
        });
    }

    /** 返回重启后需要继续处理的审批动作标识；读取失败时不影响主流程。 */
    public Optional<ApprovalResumeMarker> findPendingApprovalResume(String runId) {
        if (blank(runId)) {
            return Optional.empty();
        }
        try {
            return repository.findByRunId(runId)
                .filter(state -> "resume_pending".equals(state.getPhase())
                    || "resume".equals(state.getPhase()))
                .filter(state -> !blank(state.getResumeActionId()))
                .map(state -> new ApprovalResumeMarker(
                    state.getResumeActionId(), state.getResumeNotice()
                ));
        } catch (RuntimeException error) {
            log.warn("读取 Agent 审批续跑标记失败: runId={}", runId, error);
            return Optional.empty();
        }
    }

    /** 会话悲观锁确认接管后，将续跑标记推进到执行阶段，避免多实例重复接管。 */
    public void markResumeStarted(String runId) {
        runSafely("接管审批续跑", runId, () -> {
            if (blank(runId)) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(runId);
            if (!"resume_pending".equals(state.getPhase())) {
                return;
            }
            state.setPhase("resume");
            heartbeat(state);
            repository.save(state);
        });
    }

    public void finish(AgentChatSessionEntity session) {
        runSafely("结束", session == null ? null : session.getRunId(), () -> {
            if (session == null || blank(session.getRunId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(session.getRunId());
            state.setStatus(session.getRunStatus() == null ? AgentRunStatus.FAILED : session.getRunStatus());
            state.setPhase(clean(session.getRunStep(), MAX_PHASE));
            state.setStepIndex(Math.max(0, session.getLoopNextStep()));
            if (session.getRunStatus() != AgentRunStatus.WAITING_USER) {
                state.setPendingApproval(false);
                state.setResumeActionId(null);
                state.setResumeNotice(null);
            }
            state.setAssistantMessageId(session.getActiveMessageId());
            state.setFinishedAt(LocalDateTime.now());
            heartbeat(state);
            repository.save(state);
        });
    }

    /**
     * 记录一次工具调用的安全摘要；工具参数正文永远不落库。
     *
     * <p>返回值只是在当前 Run 内递增的关联标识，方便把同一次工具调用的结果
     * 与开始检查点对齐；它不包含工具参数、用户内容或外部资源 ID。</p>
     */
    public String recordToolCall(
        AgentMetrics.RunTracker tracker, String toolName, String toolInput
    ) {
        String toolCallId = tracker == null ? null : tracker.nextToolCallId();
        runSafely("工具调用", tracker == null ? null : tracker.auditContext().runId(), () -> {
            if (tracker == null) {
                return;
            }
            AgentMetrics.AuditContext audit = tracker.auditContext();
            if (audit == null || blank(audit.runId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(audit.runId());
            state.setToolCallId(clean(toolCallId, MAX_TOOL_NAME));
            state.setToolName(clean(toolName, MAX_TOOL_NAME));
            state.setToolArgumentsHash(hash(toolInput));
            state.setToolResultReference(null);
            state.setPendingApproval(false);
            state.setAttempt(value(state.getAttempt()) + 1);
            state.setPhase("tool");
            heartbeat(state);
            repository.save(state);
            if (eventService != null) {
                eventService.append(tracker, "TOOL_STARTED", tracker.currentModelTurnId(), toolCallId,
                    toolName, toolInput, null, "started");
            }
        });
        return toolCallId;
    }

    /** 兼容旧调用方：记录工具结果但沿用当前检查点的工具关联标识。 */
    public void recordToolResult(
        AgentMetrics.RunTracker tracker, String toolName, String result, boolean pendingApproval
    ) {
        recordToolResult(tracker, toolName, null, result, pendingApproval);
    }

    /** 记录工具结果的摘要和是否产生人工确认，不保存结果正文。 */
    public void recordToolResult(
        AgentMetrics.RunTracker tracker,
        String toolName,
        String toolCallId,
        String result,
        boolean pendingApproval
    ) {
        runSafely("工具结果", tracker == null ? null : tracker.auditContext().runId(), () -> {
            if (tracker == null) {
                return;
            }
            AgentMetrics.AuditContext audit = tracker.auditContext();
            if (audit == null || blank(audit.runId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(audit.runId());
            if (!blank(toolCallId)) {
                state.setToolCallId(clean(toolCallId, MAX_TOOL_NAME));
            }
            state.setToolName(clean(toolName, MAX_TOOL_NAME));
            state.setToolResultReference("sha256:" + hash(result));
            state.setPendingApproval(pendingApproval);
            state.setPhase(pendingApproval ? "waiting_user" : "tool");
            heartbeat(state);
            repository.save(state);
            if (eventService != null) {
                eventService.append(tracker, "TOOL_COMPLETED", tracker.currentModelTurnId(), toolCallId,
                    toolName, null, result, pendingApproval ? "waiting_user" : "completed");
            }
        });
    }

    /** 读取运行是否有待用户确认的动作；检查点不可用时安全返回 false。 */
    public boolean hasPendingApproval(String runId) {
        if (blank(runId)) {
            return false;
        }
        try {
            return repository.findByRunId(runId)
                .map(AgentRunStateEntity::getPendingApproval)
                .orElse(false);
        } catch (RuntimeException error) {
            log.warn("读取 Agent 待确认状态失败，按无待确认动作处理: runId={}", runId, error);
            return false;
        }
    }

    private void runSafely(String operation, String runId, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException error) {
            log.warn("Agent 运行检查点{}失败，保留主流程: runId={}", operation, runId, error);
        }
    }

    private AgentRunStateEntity findOrCreate(String runId) {
        return repository.findByRunId(runId)
            .orElseGet(() -> AgentRunStateEntity.builder()
                .runId(clean(runId, 64))
                .status(AgentRunStatus.RUNNING)
                .agentSessionId("unknown")
                .build());
    }

    private void heartbeat(AgentRunStateEntity state) {
        LocalDateTime now = LocalDateTime.now();
        state.setHeartbeatAt(now);
        state.setLeaseUntil(now.plusMinutes(LEASE_MINUTES));
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private String clean(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(max, trimmed.length()));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private int value(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private String runId(AgentMetrics.RunTracker tracker) {
        if (tracker == null || tracker.auditContext() == null) {
            return null;
        }
        return tracker.auditContext().runId();
    }

    private String normalizeModelTurnStatus(String result) {
        return switch (result == null ? "" : result.toLowerCase(java.util.Locale.ROOT)) {
            case "success", "succeeded" -> "SUCCEEDED";
            case "cancel", "cancelled" -> "CANCELLED";
            default -> "FAILED";
        };
    }

    public record ApprovalResumeMarker(String actionId, String notice) {
    }
}
