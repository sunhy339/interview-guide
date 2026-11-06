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

    private final AgentRunStateRepository repository;

    public AgentRunStateService(AgentRunStateRepository repository) {
        this.repository = repository;
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
            heartbeat(state);
            state.setFinishedAt(null);
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

    public void finish(AgentChatSessionEntity session) {
        runSafely("结束", session == null ? null : session.getRunId(), () -> {
            if (session == null || blank(session.getRunId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(session.getRunId());
            state.setStatus(session.getRunStatus() == null ? AgentRunStatus.FAILED : session.getRunStatus());
            state.setPhase(clean(session.getRunStep(), MAX_PHASE));
            state.setStepIndex(Math.max(0, session.getLoopNextStep()));
            state.setPendingApproval(false);
            state.setAssistantMessageId(session.getActiveMessageId());
            state.setFinishedAt(LocalDateTime.now());
            heartbeat(state);
            repository.save(state);
        });
    }

    /** 记录一次工具调用的安全摘要；工具参数正文永远不落库。 */
    public void recordToolCall(
        AgentMetrics.RunTracker tracker, String toolName, String toolInput
    ) {
        runSafely("工具调用", tracker == null ? null : tracker.auditContext().runId(), () -> {
            if (tracker == null) {
                return;
            }
            AgentMetrics.AuditContext audit = tracker.auditContext();
            if (audit == null || blank(audit.runId())) {
                return;
            }
            AgentRunStateEntity state = findOrCreate(audit.runId());
            state.setToolCallId(clean(toolName, MAX_TOOL_NAME) + ":" + hash(toolInput));
            state.setToolName(clean(toolName, MAX_TOOL_NAME));
            state.setToolArgumentsHash(hash(toolInput));
            state.setToolResultReference(null);
            state.setPendingApproval(false);
            state.setAttempt(value(state.getAttempt()) + 1);
            state.setPhase("tool");
            heartbeat(state);
            repository.save(state);
        });
    }

    /** 记录工具结果的摘要和是否产生人工确认，不保存结果正文。 */
    public void recordToolResult(
        AgentMetrics.RunTracker tracker, String toolName, String result, boolean pendingApproval
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
            state.setToolName(clean(toolName, MAX_TOOL_NAME));
            state.setToolResultReference("sha256:" + hash(result));
            state.setPendingApproval(pendingApproval);
            state.setPhase(pendingApproval ? "waiting_user" : "tool");
            heartbeat(state);
            repository.save(state);
        });
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
}
