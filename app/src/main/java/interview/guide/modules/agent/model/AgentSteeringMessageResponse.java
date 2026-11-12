package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 运行期间追加输入的持久化结果。 */
public record AgentSteeringMessageResponse(
    Long messageId,
    String content,
    String runId,
    String status,
    LocalDateTime createdAt
) {
}
