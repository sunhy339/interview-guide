package interview.guide.modules.agent.service;

import org.springframework.ai.tool.ToolCallback;

/**
 * Agent 工具的统一运行时描述。
 *
 * <p>描述只负责目录、能力和风险分级，不替代工具内部的 owner、参数和确认校验。
 * 这样后续接入外部工具或 MCP 时，可以复用同一份能力策略，而不需要把工具名散落
 * 在编排服务中。</p>
 */
public record AgentToolDescriptor(
    String name,
    String version,
    AgentIntentRouter.Capability capability,
    RiskLevel riskLevel,
    boolean requiresApproval,
    boolean ownerScoped,
    String requiredPermission,
    long timeoutMillis,
    int maxAttempts,
    RetryPolicy retryPolicy,
    Idempotency idempotency,
    ResultContract resultContract,
    String inputSchema,
    ToolCallback callback
) {
    /** 兼容迁移期旧调用方；新工具应使用完整契约元数据。 */
    public AgentToolDescriptor(
        String name,
        AgentIntentRouter.Capability capability,
        RiskLevel riskLevel,
        boolean requiresApproval,
        ToolCallback callback
    ) {
        this(
            name, "v1", capability, riskLevel, requiresApproval, true,
            "agent:unknown", 5_000L, 1, RetryPolicy.NONE,
            requiresApproval ? Idempotency.IDEMPOTENCY_KEYED : Idempotency.IDEMPOTENT,
            requiresApproval ? ResultContract.ACTION_OR_CONFIRMATION : ResultContract.DATA_BOUNDARY,
            callback == null || callback.getToolDefinition() == null
                ? "{}" : callback.getToolDefinition().inputSchema(),
            callback
        );
    }

    public AgentToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 工具名称不能为空");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Agent 工具版本不能为空");
        }
        if (capability == null || riskLevel == null || callback == null) {
            throw new IllegalArgumentException("Agent 工具描述不完整");
        }
        if (!ownerScoped) {
            throw new IllegalArgumentException("Agent 业务工具必须绑定 owner 作用域");
        }
        if (requiredPermission == null || requiredPermission.isBlank()) {
            throw new IllegalArgumentException("Agent 工具权限不能为空");
        }
        if (timeoutMillis <= 0 || timeoutMillis > 60_000L) {
            throw new IllegalArgumentException("Agent 工具超时必须在 1-60000 毫秒之间");
        }
        if (maxAttempts < 1 || maxAttempts > 3) {
            throw new IllegalArgumentException("Agent 工具尝试次数必须在 1-3 之间");
        }
        if (retryPolicy == null || idempotency == null || resultContract == null) {
            throw new IllegalArgumentException("Agent 工具执行契约不完整");
        }
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("Agent 工具输入 Schema 不能为空");
        }
        if (retryPolicy != RetryPolicy.NONE && idempotency == Idempotency.NON_RETRYABLE) {
            throw new IllegalArgumentException("不可重试工具不能配置自动重试");
        }
    }

    public enum RiskLevel {
        READ_ONLY,
        DRAFT_WRITE
    }

    /** 当前业务工具默认不在回调层自动重试，避免重复创建动作；预留受控策略扩展位。 */
    public enum RetryPolicy {
        NONE,
        TRANSIENT_ONLY,
        SAFE_READ_ONLY
    }

    public enum Idempotency {
        IDEMPOTENT,
        IDEMPOTENCY_KEYED,
        NON_RETRYABLE
    }

    public enum ResultContract {
        DATA_BOUNDARY,
        ACTION_OR_CONFIRMATION
    }
}
