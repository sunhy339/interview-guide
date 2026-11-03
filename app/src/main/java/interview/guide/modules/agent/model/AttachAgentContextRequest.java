package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 用户明确选择面试报告作为通用 Agent 上下文时使用。 */
public record AttachAgentContextRequest(
    @NotBlank(message = "面试记录不能为空")
    @Size(max = 36, message = "面试记录标识不合法")
    String interviewSessionId,
    @Size(max = 64, message = "来源消息标识不能超过64个字符")
    String sourceMessageId
) {
    public Long sourceMessageIdAsLong() {
        if (sourceMessageId == null || sourceMessageId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(sourceMessageId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
