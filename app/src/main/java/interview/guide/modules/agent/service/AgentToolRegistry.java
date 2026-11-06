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

    private AgentToolDescriptor describe(ToolCallback callback) {
        String name = callback.getToolDefinition().name();
        AgentIntentRouter.Capability capability = name.startsWith("schedule_")
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
        return new AgentToolDescriptor(
            name, capability, risk, risk == AgentToolDescriptor.RiskLevel.DRAFT_WRITE, callback
        );
    }
}
