package interview.guide.modules.agent.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Agent 业务工具目录。
 *
 * <p>这里集中维护工具能力和风险元数据，并提供请求级筛选入口。工具实例仍由现有
 * {@link AgentContextToolCallbackFactory} 创建，因此不改变原有实现、工具名或动作状态机。</p>
 */
@Component
public class AgentToolRegistry {

    private final AgentContextToolCallbackFactory callbackFactory;

    public AgentToolRegistry(AgentContextToolCallbackFactory callbackFactory) {
        this.callbackFactory = callbackFactory;
    }

    public List<AgentToolDescriptor> descriptors() {
        return callbackFactory.callbacks().stream()
            .map(this::describe)
            .toList();
    }

    public List<ToolCallback> callbacks(Set<String> allowedNames) {
        if (allowedNames == null) {
            return callbackFactory.callbacks();
        }
        Set<String> safeNames = Set.copyOf(allowedNames);
        return descriptors().stream()
            .filter(descriptor -> safeNames.contains(descriptor.name()))
            .map(AgentToolDescriptor::callback)
            .toList();
    }

    public Map<String, AgentToolDescriptor> descriptorMap() {
        return descriptors().stream().collect(Collectors.toUnmodifiableMap(
            AgentToolDescriptor::name, Function.identity()
        ));
    }

    /** 返回指定工具的完整契约；未知名称不允许悄悄降级成任意工具。 */
    public AgentToolDescriptor descriptor(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return descriptors().stream()
            .filter(descriptor -> name.equals(descriptor.name()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 为不属于业务目录的只读工具提供同一份执行契约。
     *
     * <p>Skill 仍由调用方按本轮意图单独决定是否挂载，不混入业务工具白名单；
     * 这里只复用 owner 无关的超时、重试、输入和结果边界。</p>
     */
    public AgentToolDescriptor descriptorForSkill(ToolCallback callback) {
        if (callback == null || callback.getToolDefinition() == null) {
            return null;
        }
        return new AgentToolDescriptor(
            callback.getToolDefinition().name(),
            "v1",
            AgentIntentRouter.Capability.SKILL,
            AgentToolDescriptor.RiskLevel.READ_ONLY,
            false,
            true,
            "agent:skill:read",
            5_000L,
            2,
            AgentToolDescriptor.RetryPolicy.SAFE_READ_ONLY,
            AgentToolDescriptor.Idempotency.IDEMPOTENT,
            AgentToolDescriptor.ResultContract.DATA_BOUNDARY,
            callback.getToolDefinition().inputSchema(),
            callback
        );
    }

    /**
     * 校验目录中的契约是否满足最小生产边界。
     *
     * <p>应用启动时不强制执行，避免历史测试替身或旧配置被一次性阻断；生产装配可在
     * 健康检查中调用此方法，并把异常作为工具目录配置错误处理。</p>
     */
    public List<String> contractErrors() {
        return descriptors().stream()
            .flatMap(descriptor -> {
                java.util.ArrayList<String> errors = new java.util.ArrayList<>();
                if (!descriptor.ownerScoped()) errors.add(descriptor.name() + ":owner-scope");
                if (descriptor.requiredPermission().isBlank()) {
                    errors.add(descriptor.name() + ":permission");
                }
                if (descriptor.inputSchema().isBlank()) errors.add(descriptor.name() + ":input-schema");
                if (descriptor.timeoutMillis() <= 0) errors.add(descriptor.name() + ":timeout");
                if (descriptor.maxAttempts() < 1) errors.add(descriptor.name() + ":attempts");
                return errors.stream();
            })
            .toList();
    }

    private AgentToolDescriptor describe(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        AgentIntentRouter.Capability capability = AgentContextToolCallbackFactory.ACTION_DRAFT.equals(name)
            ? AgentIntentRouter.Capability.ACTION
            : name.startsWith("schedule_")
            ? AgentIntentRouter.Capability.SCHEDULE
            : AgentIntentRouter.Capability.CONTEXT;
        AgentToolDescriptor.RiskLevel risk = switch (name) {
            case AgentContextToolCallbackFactory.INTERVIEW_SEARCH,
                AgentContextToolCallbackFactory.CONTEXT_LIST,
                AgentContextToolCallbackFactory.SCHEDULE_LIST,
                AgentContextToolCallbackFactory.SCHEDULE_CONFLICT ->
                AgentToolDescriptor.RiskLevel.READ_ONLY;
            default -> AgentToolDescriptor.RiskLevel.DRAFT_WRITE;
        };
        boolean readOnly = risk == AgentToolDescriptor.RiskLevel.READ_ONLY;
        String permission = capability == AgentIntentRouter.Capability.ACTION
            ? "agent:action:draft"
            : capability == AgentIntentRouter.Capability.SCHEDULE
            ? readOnly ? "agent:schedule:read" : "agent:schedule:draft"
            : readOnly ? "agent:context:read" : "agent:context:draft";
        return new AgentToolDescriptor(
            name,
            "v1",
            capability,
            risk,
            !readOnly,
            true,
            permission,
            readOnly ? 3_000L : 5_000L,
            readOnly ? 2 : 1,
            readOnly
                ? AgentToolDescriptor.RetryPolicy.SAFE_READ_ONLY
                : AgentToolDescriptor.RetryPolicy.NONE,
            readOnly
                ? AgentToolDescriptor.Idempotency.IDEMPOTENT
                : AgentToolDescriptor.Idempotency.IDEMPOTENCY_KEYED,
            readOnly
                ? AgentToolDescriptor.ResultContract.DATA_BOUNDARY
                : AgentToolDescriptor.ResultContract.ACTION_OR_CONFIRMATION,
            callback.getToolDefinition().inputSchema(),
            callback
        );
    }
}
