package interview.guide.modules.agent.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Provider 响应的低敏关联摘要。
 *
 * <p>只保留 Provider 返回的稳定 ID、工具调用 ID 的摘要和数量，不保存模型消息、工具
 * 参数、工具结果或隐藏推理。一个流式模型轮次可能包含多个 ChatResponse，因此先在内存
 * 中聚合，轮次结束时再写入一条 Run 事件。</p>
 */
public final class AgentProviderResponseTrace {

    private final Set<String> responseIds = new LinkedHashSet<>();
    private final Set<String> toolCallIds = new LinkedHashSet<>();
    private int responseCount;
    private int toolCallCount;

    /** 观察一个 Provider 返回的响应片段。该方法不读取正文。 */
    public void observe(ChatResponse response) {
        if (response == null) {
            return;
        }
        responseCount++;
        if (response.getMetadata() != null) {
            add(responseIds, response.getMetadata().getId());
        }
        if (response.getResults() == null) {
            return;
        }
        for (Generation generation : response.getResults()) {
            if (generation == null || generation.getOutput() == null) {
                continue;
            }
            AssistantMessage output = generation.getOutput();
            if (output.getToolCalls() == null) {
                continue;
            }
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                if (toolCall == null) {
                    continue;
                }
                if (toolCall.id() == null || toolCall.id().isBlank()) {
                    toolCallCount++;
                } else if (add(toolCallIds, toolCall.id())) {
                    // Streaming providers may repeat the same tool call ID across chunks;
                    // count the logical call once, not once per chunk.
                    toolCallCount++;
                }
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            responseIds.stream().findFirst().orElse(null),
            digest(responseIds),
            digest(toolCallIds),
            responseCount,
            toolCallCount
        );
    }

    private boolean add(Set<String> values, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (!normalized.isBlank()) {
            return values.add(normalized);
        }
        return false;
    }

    private String digest(Set<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    public record Snapshot(
        String responseId,
        String responseIdsHash,
        String toolCallIdsHash,
        int responseCount,
        int toolCallCount
    ) {
        public boolean hasProviderMetadata() {
            return responseId != null || responseIdsHash != null || toolCallIdsHash != null
                || responseCount > 0 || toolCallCount > 0;
        }
    }
}
