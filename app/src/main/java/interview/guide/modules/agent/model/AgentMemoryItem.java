package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/**
 * 可由用户管理的单条长期记忆。
 *
 * <p>只保存结构化摘要，不保存原始面试问答；source 和 jobScope 用于说明记忆从哪里来、
 * 适用于哪个面试方向。sourceId/version/authority/selectedReason 只用于来源治理和展示，
 * 不会作为记忆正文注入 Agent Prompt。</p>
 */
public record AgentMemoryItem(
    String id,
    String category,
    String content,
    String source,
    String sourceId,
    String version,
    String authority,
    String selectedReason,
    String jobScope,
    Double confidence,
    LocalDateTime updatedAt,
    LocalDateTime expiresAt,
    LocalDateTime revokedAt
) {
    /** 兼容尚未携带撤销时间的结构化记忆调用方。 */
    public AgentMemoryItem(
        String id,
        String category,
        String content,
        String source,
        String jobScope,
        Double confidence,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt
    ) {
        this(id, category, content, source, null, null, null, null, jobScope,
            confidence, updatedAt, expiresAt, null);
    }

    /** 兼容新增过期时间前的结构化记忆调用方。 */
    public AgentMemoryItem(
        String id,
        String category,
        String content,
        String source,
        String jobScope,
        Double confidence,
        LocalDateTime updatedAt
    ) {
        this(id, category, content, source, null, null, null, null, jobScope,
            confidence, updatedAt, null, null);
    }

    public AgentMemoryItem(
        String id,
        String category,
        String content,
        String source,
        String jobScope,
        LocalDateTime updatedAt
    ) {
        this(id, category, content, source, null, null, null, null, jobScope,
            0.7d, updatedAt, null, null);
    }

    /** 兼容仅携带撤销时间的旧结构化记忆调用方。 */
    public AgentMemoryItem(
        String id,
        String category,
        String content,
        String source,
        String jobScope,
        Double confidence,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt,
        LocalDateTime revokedAt
    ) {
        this(id, category, content, source, null, null, null, null, jobScope,
            confidence, updatedAt, expiresAt, revokedAt);
    }
}
