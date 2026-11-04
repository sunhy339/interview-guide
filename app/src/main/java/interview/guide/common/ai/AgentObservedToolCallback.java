package interview.guide.common.ai;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.service.AgentAuditService;
import java.time.Duration;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Agent 专用只读工具观测包装器。
 *
 * <p>仅记录工具名、成功/失败和耗时，不记录工具参数与返回内容，避免把用户上下文
 * 或 Skill 内容写入日志和指标系统。</p>
 */
final class AgentObservedToolCallback implements ToolCallback {

    private static final int MAX_RESULT_CHARS = 12_000;
    private static final String RESULT_TRUNCATION_NOTICE = "\n[工具资料已截断]";

    private final ToolCallback delegate;
    private final AgentMetrics metrics;
    private final AgentAuditService auditService;
    private final PromptSanitizer promptSanitizer;

    AgentObservedToolCallback(ToolCallback delegate, AgentMetrics metrics) {
        this(delegate, metrics, null);
    }

    AgentObservedToolCallback(
        ToolCallback delegate, AgentMetrics metrics, AgentAuditService auditService
    ) {
        this(delegate, metrics, auditService, null);
    }

    AgentObservedToolCallback(
        ToolCallback delegate,
        AgentMetrics metrics,
        AgentAuditService auditService,
        PromptSanitizer promptSanitizer
    ) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.auditService = auditService;
        this.promptSanitizer = promptSanitizer;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        long startedNanos = System.nanoTime();
        try {
            String result = sanitizeResult(delegate.call(toolInput));
            record("success", startedNanos);
            return result;
        } catch (RuntimeException error) {
            record("failure", startedNanos);
            throw error;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long startedNanos = System.nanoTime();
        AgentMetrics.RunTracker runTracker = runTracker(toolContext);
        try {
            reserveToolCall(runTracker);
            String rawResult = delegate.call(toolInput, toolContext);
            boolean rejected = isRejectedResult(rawResult);
            String result = sanitizeResult(rawResult);
            String outcome = rejected ? "rejected" : "success";
            record(outcome, startedNanos);
            audit(runTracker, outcome);
            return result;
        } catch (RuntimeException error) {
            record("failure", startedNanos);
            audit(runTracker, "failure");
            throw error;
        }
    }

    private void reserveToolCall(AgentMetrics.RunTracker runTracker) {
        if (runTracker != null) {
            runTracker.reserveToolCall();
        }
    }

    private AgentMetrics.RunTracker runTracker(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object value = toolContext.getContext().get(AgentMetrics.RUN_TRACKER_TOOL_CONTEXT_KEY);
        return value instanceof AgentMetrics.RunTracker runTracker ? runTracker : null;
    }

    private void audit(AgentMetrics.RunTracker runTracker, String result) {
        if (auditService == null || runTracker == null) {
            return;
        }
        AgentMetrics.AuditContext context = runTracker.auditContext();
        auditService.recordQuietly(
            context.ownerKey(), context.interviewSessionId(), context.agentSessionId(), context.runId(),
            "TOOL_CALLED", "agent-tool", getToolDefinition().name(), result
        );
    }

    private void record(String result, long startedNanos) {
        if (metrics != null) {
            metrics.recordToolCall(
                getToolDefinition().name(),
                result,
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos))
            );
        }
    }

    /**
     * AgentContextToolCallbackFactory 将业务拒绝包装在专用边界中返回，不能把这种安全拒绝
     * 当成工具成功。这里必须在二次净化前判断，因为净化会主动移除旧边界标签。
     */
    private boolean isRejectedResult(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        return result.matches(
            "(?s)^<data-boundary-([0-9a-f]{8})-agent-tool-error>.*"
                + "</data-boundary-\\1-agent-tool-error>$"
        );
    }

    /** SkillsTool 等外部资料工具没有自己的上下文包装，统一在 Agent 边界再净化一次。 */
    private String sanitizeResult(String result) {
        if (promptSanitizer == null || result == null) {
            return result;
        }
        // 工具返回值本身也是不可信资料。先去掉伪造的边界/角色标记，再用新的
        // 服务端边界重新包裹；不能直接返回净化文本，否则模型可能看不到“工具数据”
        // 的结构边界，也不能继续信任工具结果中的任何指令。
        String safe = promptSanitizer.sanitizeForContext(result);
        if (safe != null && safe.length() > MAX_RESULT_CHARS) {
            int contentChars = Math.max(0, MAX_RESULT_CHARS - RESULT_TRUNCATION_NOTICE.length());
            safe = safe.substring(0, contentChars) + RESULT_TRUNCATION_NOTICE;
        }
        return promptSanitizer.wrapWithDelimiters("agent-observed-tool-result", safe);
    }
}
