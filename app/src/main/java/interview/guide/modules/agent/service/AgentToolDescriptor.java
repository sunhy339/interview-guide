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
    AgentIntentRouter.Capability capability,
    RiskLevel riskLevel,
    boolean requiresApproval,
    ToolCallback callback
) {
    public AgentToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 工具名称不能为空");
        }
        if (capability == null || riskLevel == null || callback == null) {
            throw new IllegalArgumentException("Agent 工具描述不完整");
        }
    }

    public enum RiskLevel {
        READ_ONLY,
        DRAFT_WRITE
    }
}
