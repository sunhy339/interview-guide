package interview.guide.modules.agent.service;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.model.AgentProviderResponseTrace;
import interview.guide.modules.agent.model.AgentRunEventEntity;
import interview.guide.modules.agent.model.AgentRunEventResponse;
import interview.guide.modules.agent.repository.AgentRunEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 追加式 Run 事件记录器；失败不阻断 Agent 主流程。 */
@Slf4j
@Service
public class AgentRunEventService {

    private static final int MAX_EVENT_TYPE = 64;
    private static final int MAX_TOOL_NAME = 128;
    private static final int MAX_STATUS = 32;

    private final AgentRunEventRepository repository;

    public AgentRunEventService(AgentRunEventRepository repository) {
        this.repository = repository;
    }

    public List<AgentRunEventResponse> listForOwner(
        String ownerKey, String agentSessionId, String runId, int limit
    ) {
        if (blank(ownerKey) || blank(agentSessionId)) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(200, limit));
        List<AgentRunEventEntity> events = blank(runId)
            ? repository.findTop200ByOwnerKeyAndAgentSessionIdOrderByIdAsc(
                ownerKey.trim(), agentSessionId.trim()
            )
            : repository.findTop200ByOwnerKeyAndAgentSessionIdAndRunIdOrderByIdAsc(
                ownerKey.trim(), agentSessionId.trim(), runId.trim()
            );
        return events.stream().limit(safeLimit).map(AgentRunEventResponse::from).toList();
    }

    public void append(
        AgentMetrics.RunTracker tracker,
        String eventType,
        String modelTurnId,
        String toolCallId,
        String toolName,
        String arguments,
        String result,
        String status
    ) {
        appendInternal(
            tracker, eventType, modelTurnId, toolCallId, toolName, arguments, result, status,
            null, null, null, null, null
        );
    }

    /**
     * 记录一个模型轮次聚合后的 Provider 元数据，不保存 Provider 消息正文。
     */
    public void appendProviderResponse(
        AgentMetrics.RunTracker tracker,
        String modelTurnId,
        AgentProviderResponseTrace.Snapshot trace,
        String status
    ) {
        if (trace == null || !trace.hasProviderMetadata()) {
            return;
        }
        appendInternal(
            tracker, "MODEL_PROVIDER_RESPONSE", modelTurnId, null, null, null, null, status,
            trace.responseId(), trace.responseIdsHash(), trace.toolCallIdsHash(),
            trace.responseCount(), trace.toolCallCount()
        );
    }

    private void appendInternal(
        AgentMetrics.RunTracker tracker,
        String eventType,
        String modelTurnId,
        String toolCallId,
        String toolName,
        String arguments,
        String result,
        String status,
        String providerResponseId,
        String providerResponseIdsHash,
        String providerToolCallIdsHash,
        Integer providerResponseCount,
        Integer providerToolCallCount
    ) {
        try {
            if (tracker == null || tracker.auditContext() == null
                || blank(tracker.auditContext().runId()) || blank(eventType)) {
                return;
            }
            AgentMetrics.AuditContext context = tracker.auditContext();
            repository.save(AgentRunEventEntity.builder()
                .runId(clean(context.runId(), 64))
                .ownerKey(clean(context.ownerKey(), 64))
                .agentSessionId(clean(context.agentSessionId(), 64))
                .eventType(cleanStable(eventType, MAX_EVENT_TYPE))
                .modelTurnId(cleanStable(modelTurnId, 64))
                .toolCallId(cleanStable(toolCallId, 128))
                .toolName(cleanStable(toolName, MAX_TOOL_NAME))
                .argumentsHash(arguments == null ? null : hash(arguments))
                .resultHash(result == null ? null : hash(result))
                .providerResponseId(cleanStable(providerResponseId, 128))
                .providerResponseIdsHash(cleanStable(providerResponseIdsHash, 64))
                .providerToolCallIdsHash(cleanStable(providerToolCallIdsHash, 64))
                .providerResponseCount(providerResponseCount)
                .providerToolCallCount(providerToolCallCount)
                .status(cleanStable(status, MAX_STATUS))
                .build());
        } catch (RuntimeException error) {
            log.warn("Agent Run 事件写入失败: eventType={}", cleanStable(eventType, MAX_EVENT_TYPE), error);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private String clean(String value, int max) {
        if (blank(value)) return null;
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ")
            .replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized.substring(0, Math.min(max, normalized.length()));
    }

    private String cleanStable(String value, int max) {
        String normalized = clean(value, max);
        if (normalized == null) return null;
        return normalized.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]*") ? normalized : "redacted";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
