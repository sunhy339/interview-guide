package interview.guide.modules.agent.model;

/**
 * Agent 回答使用的外部上下文来源摘要。
 */
public record AgentSource(
    String kind,
    String title,
    Double score,
    String reference,
    String sourceId,
    String version,
    String authority,
    String selectedReason
) {
    /** 兼容已有调用方和旧会话来源 JSON。 */
    public AgentSource(String kind, String title, Double score, String reference) {
        this(kind, title, score, reference, null, null, null, null);
    }
}
