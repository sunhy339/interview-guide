package interview.guide.modules.agent.model;

/**
 * Agent 运行在应用重启或进程中断时最后确认到的安全边界。
 *
 * <p>这些值只描述恢复策略，不代表模型正文或业务动作结果。尤其是工具执行已经开始
 * 但没有记录结果时，不能把整条用户消息重新播放，否则可能重复产生外部副作用。</p>
 */
public enum AgentRecoveryBoundary {
    CONTEXT,
    MODEL_TURN_IN_PROGRESS,
    MODEL_RESPONSE_OBSERVED,
    TOOL_EXECUTION_IN_PROGRESS,
    TOOL_EXECUTION_UNCERTAIN,
    TOOL_RESULT_OBSERVED,
    WAITING_USER,
    APPROVAL_RESUME_PENDING,
    APPROVAL_RESUME_IN_PROGRESS,
    SAFE_RETRY,
    COMPLETED,
    FAILED,
    CANCELLED
}
