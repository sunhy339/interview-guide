package interview.guide.modules.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 将已审核的业务资料绑定到 Agent 会话。 */
public record AttachAgentResourceRequest(
    @NotBlank @Size(max = 32) String sourceType,
    @NotBlank @Size(max = 128) String resourceId,
    @Size(max = 16) String scope,
    @Size(max = 36) String sourceMessageId
) {
    public String normalizedType() {
        return sourceType == null ? "" : sourceType.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public String normalizedScope() {
        return scope == null || scope.isBlank() ? "SESSION" : scope.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public Long sourceMessageIdAsLong() {
        if (sourceMessageId == null || sourceMessageId.isBlank()) return null;
        try {
            return Long.parseLong(sourceMessageId.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
