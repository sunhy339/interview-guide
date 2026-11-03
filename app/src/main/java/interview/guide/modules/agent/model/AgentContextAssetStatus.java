package interview.guide.modules.agent.model;

/**
 * Agent 上传素材的审核生命周期。
 *
 * <p>READY_FOR_REVIEW 只代表技术检查完成，不代表素材已经可以进入长期知识库；
 * APPROVED_FOR_CONTEXT 也只允许作为当前 Agent 的资料，写入 RAG 仍需单独确认。</p>
 */
public enum AgentContextAssetStatus {
    READY_FOR_REVIEW,
    APPROVED_FOR_CONTEXT,
    KB_IMPORT_PENDING,
    KB_IMPORT_COMPLETED,
    KB_IMPORT_FAILED,
    REJECTED,
    EXPIRED
}
