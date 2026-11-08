package interview.guide.modules.agent.model;

/** Agent 发起的异步业务任务状态。 */
public enum AgentAsyncOperationStatus {
    SUBMITTED,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED
}
