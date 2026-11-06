package interview.guide.common.ai;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.service.AgentAuditService;
import interview.guide.modules.agent.service.AgentToolDescriptor;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static final String OVERSIZED_INPUT_REJECTION =
        "[agent-tool-rejected] 工具参数过长，未执行任何操作";
    private static final String RESULT_CONTRACT_REJECTION_PREFIX =
        "[agent-tool-rejected] ";
    private static final ExecutorService TOOL_EXECUTOR =
        Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("agent-tool-execution-", 0).factory()
        );

    private final ToolCallback delegate;
    private final AgentMetrics metrics;
    private final AgentAuditService auditService;
    private final PromptSanitizer promptSanitizer;
    private final AgentToolDescriptor contract;

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
        this(delegate, metrics, auditService, promptSanitizer, null);
    }

    AgentObservedToolCallback(
        ToolCallback delegate,
        AgentMetrics metrics,
        AgentAuditService auditService,
        PromptSanitizer promptSanitizer,
        AgentToolDescriptor contract
    ) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.auditService = auditService;
        this.promptSanitizer = promptSanitizer;
        this.contract = contract;
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
            if (isOversizedInput(toolInput)) {
                String result = sanitizeResult(rejectOversizedInput());
                record("rejected", startedNanos);
                return result;
            }
            String contractError = validateContract(toolInput);
            if (contractError != null) {
                String result = sanitizeResult(rejectContractInput(contractError));
                record("rejected", startedNanos);
                return result;
            }
            String rawResult = invokeDelegate(toolInput, null);
            boolean rejected = isRejectedResult(rawResult);
            String resultContractError = rejected
                ? null : AgentToolResultContractValidator.validateOutput(rawResult, contract);
            if (resultContractError != null) {
                rawResult = rejectResultContract(resultContractError);
                rejected = true;
            }
            String result = sanitizeResult(rawResult);
            record(rejected ? "rejected" : "success", startedNanos);
            return result;
        } catch (RuntimeException error) {
            record(error instanceof ToolExecutionTimeoutException ? "timeout" : "failure", startedNanos);
            throw error;
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long startedNanos = System.nanoTime();
        AgentMetrics.RunTracker runTracker = runTracker(toolContext);
        try {
            reserveToolCall(runTracker);
            if (isOversizedInput(toolInput)) {
                String result = sanitizeResult(rejectOversizedInput());
                record("rejected", startedNanos);
                audit(runTracker, "rejected");
                auditSecurityRejection(runTracker);
                return result;
            }
            String contractError = validateContract(toolInput);
            if (contractError != null) {
                String result = sanitizeResult(rejectContractInput(contractError));
                record("rejected", startedNanos);
                audit(runTracker, "rejected");
                auditSecurityRejection(runTracker);
                return result;
            }
            String rawResult = invokeDelegate(toolInput, toolContext);
            boolean rejected = isRejectedResult(rawResult);
            String resultContractError = rejected
                ? null : AgentToolResultContractValidator.validateOutput(rawResult, contract);
            if (resultContractError != null) {
                rawResult = rejectResultContract(resultContractError);
                rejected = true;
            }
            String result = sanitizeResult(rawResult);
            String outcome = rejected ? "rejected" : "success";
            record(outcome, startedNanos);
            audit(runTracker, outcome);
            if (rejected) {
                auditSecurityRejection(runTracker);
            }
            return result;
        } catch (RuntimeException error) {
            String outcome = error instanceof ToolExecutionTimeoutException ? "timeout" : "failure";
            record(outcome, startedNanos);
            audit(runTracker, outcome);
            throw error;
        }
    }

    /**
     * 工具契约中的超时和重试不能只停留在元数据层。
     *
     * <p>只读工具可以按契约进行有限的瞬时失败重试；草稿/写入类工具默认不重试，
     * 避免同一请求重复产生动作。Future 只负责隔离 Agent 调用线程，超时后会发出
     * interrupt 并让当前模型轮次立即得到明确失败信号；业务回调自身仍由各模块负责
     * 正确处理中断和数据库事务。</p>
     */
    private String invokeDelegate(String toolInput, ToolContext toolContext) {
        if (contract == null) {
            return callDelegate(toolInput, toolContext);
        }
        int maxAttempts = contract.retryPolicy() == AgentToolDescriptor.RetryPolicy.NONE
            ? 1 : contract.maxAttempts();
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Future<String> future = TOOL_EXECUTOR.submit(() -> callDelegate(toolInput, toolContext));
            try {
                return future.get(contract.timeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                future.cancel(true);
                lastFailure = new ToolExecutionTimeoutException(
                    "Agent 工具 " + getToolDefinition().name() + " 执行超时（"
                        + contract.timeoutMillis() + "ms）"
                );
            } catch (InterruptedException error) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Agent 工具执行线程被中断", error);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                lastFailure = cause instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Agent 工具执行失败", cause);
            }
            if (attempt < maxAttempts) {
                continue;
            }
        }
        throw lastFailure == null
            ? new IllegalStateException("Agent 工具执行失败") : lastFailure;
    }

    private String callDelegate(String toolInput, ToolContext toolContext) {
        return toolContext == null
            ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
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

    private void auditSecurityRejection(AgentMetrics.RunTracker runTracker) {
        if (auditService == null || runTracker == null) {
            return;
        }
        AgentMetrics.AuditContext context = runTracker.auditContext();
        auditService.recordToolSecurityRejected(
            context.ownerKey(), context.interviewSessionId(), context.agentSessionId(),
            context.runId(), getToolDefinition().name()
        );
    }

    private boolean isOversizedInput(String toolInput) {
        return toolInput != null
            && toolInput.length() > PromptSecurityConstants.MAX_AGENT_TOOL_INPUT_CHARS;
    }

    private String rejectOversizedInput() {
        if (promptSanitizer == null) {
            return OVERSIZED_INPUT_REJECTION;
        }
        return promptSanitizer.wrapWithDelimiters(
            "agent-tool-error", "工具参数过长，未执行任何操作"
        );
    }

    private String validateContract(String toolInput) {
        return AgentToolContractValidator.validateInput(toolInput, contract);
    }

    private String rejectContractInput(String reason) {
        String message = reason == null || reason.isBlank()
            ? "工具参数不符合输入契约，未执行任何操作" : reason;
        if (promptSanitizer == null) {
            return "[agent-tool-rejected] " + message;
        }
        return promptSanitizer.wrapWithDelimiters("agent-tool-error", message);
    }

    private String rejectResultContract(String reason) {
        String message = reason == null || reason.isBlank()
            ? "工具结果不符合输出契约，未确认任何业务状态" : reason;
        if (promptSanitizer == null) {
            return RESULT_CONTRACT_REJECTION_PREFIX + message;
        }
        return promptSanitizer.wrapWithDelimiters("agent-tool-error", message);
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
        if (result.startsWith(OVERSIZED_INPUT_REJECTION)) {
            return true;
        }
        if (result.startsWith(RESULT_CONTRACT_REJECTION_PREFIX)) {
            return true;
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

/** 工具超过自身契约的执行时间预算。 */
final class ToolExecutionTimeoutException extends RuntimeException {

    ToolExecutionTimeoutException(String message) {
        super(message);
    }
}
