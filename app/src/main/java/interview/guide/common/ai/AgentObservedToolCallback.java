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
            String result = sanitizeResult(delegate.call(toolInput, toolContext));
            record("success", startedNanos);
            audit(runTracker, "success");
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

    /** SkillsTool 等外部资料工具没有自己的上下文包装，统一在 Agent 边界再净化一次。 */
    private String sanitizeResult(String result) {
        if (promptSanitizer == null || result == null) {
            return result;
        }
        // 工具返回值本身也是不可信资料。先去掉伪造的边界/角色标记，再用新的
        // 服务端边界重新包裹；不能直接返回净化文本，否则模型可能看不到“工具数据”
        // 的结构边界，也不能继续信任工具结果中的任何指令。
        String safe = promptSanitizer.sanitizeForContext(result);
        return promptSanitizer.wrapWithDelimiters("agent-observed-tool-result", safe);
    }
}
