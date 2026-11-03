package interview.guide.modules.agent.model;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;

/** Agent 上下文选择器使用的知识库摘要；不向前端暴露原始文档内容。 */
public record AgentKnowledgeBaseOption(
    Long id,
    String name,
    String category,
    String originalFilename,
    String vectorStatus
) {
    public static AgentKnowledgeBaseOption from(KnowledgeBaseEntity knowledgeBase) {
        VectorStatus status = knowledgeBase.getVectorStatus();
        return new AgentKnowledgeBaseOption(
            knowledgeBase.getId(),
            knowledgeBase.getName(),
            knowledgeBase.getCategory(),
            knowledgeBase.getOriginalFilename(),
            status == null ? null : status.name()
        );
    }
}
