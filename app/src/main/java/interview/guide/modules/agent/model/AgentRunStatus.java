package interview.guide.modules.agent.model;

/** Agent 最近一次任务的生命周期状态。 */
public enum AgentRunStatus {
    IDLE,
    RUNNING,
    /** Agent 已生成需要用户确认的动作，等待确认/拒绝后再结束该运行。 */
    WAITING_USER,
    COMPLETED,
    FAILED,
    CANCELLED
}
