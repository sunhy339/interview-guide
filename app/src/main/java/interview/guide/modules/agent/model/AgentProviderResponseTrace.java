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
 * 参数、工具结果或隐藏推理。一个流式模型轮次可能包含多个 ChatResponse；工具批次只在
 * 观察到新的逻辑调用时增量记录，轮次结束时再写入聚合结果。</p>
 */
public final class AgentProviderResponseTrace {

    private final Set<String> responseIds = new LinkedHashSet<>();
    private final Set<String> toolCallIds = new LinkedHashSet<>();
    private int responseCount;
    private int toolCallCount;
    private boolean anonymousToolCallObserved;

    /**
     * 观察一个 Provider 返回的响应片段。该方法不保存正文，并返回本片段新增的
     * 工具批次摘要，供运行事件做低敏关联。重复的流式 tool-call ID 不会再次计数。
     */
    public BatchSnapshot observe(ChatResponse response) {
        if (response == null) {
            return BatchSnapshot.empty();
        }
        responseCount++;
        Set<String> batchResponseIds = new LinkedHashSet<>();
        Set<String> batchToolCallIds = new LinkedHashSet<>();
        boolean anonymousToolCall = false;
        if (response.getMetadata() != null) {
            String responseId = response.getMetadata().getId();
            add(responseIds, responseId);
            add(batchResponseIds, responseId);
        }
        if (response.getResults() == null) {
            return batchSnapshot(batchResponseIds, batchToolCallIds, false);
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
                    anonymousToolCall = true;
                } else if (add(toolCallIds, toolCall.id())) {
                    // Streaming providers may repeat the same tool call ID across chunks;
                    // count the logical call once, not once per chunk.
                    toolCallCount++;
                    add(batchToolCallIds, toolCall.id());
                }
            }
        }
        boolean newAnonymousToolBatch = anonymousToolCall && !anonymousToolCallObserved;
        if (newAnonymousToolBatch) {
            // 某些兼容网关不返回 tool-call ID；至少保证一个模型轮次不会因分片
            // 重复而膨胀调用数。多个无 ID 并行调用无法安全区分，保持保守计数。
            anonymousToolCallObserved = true;
            toolCallCount++;
        }
        return batchSnapshot(batchResponseIds, batchToolCallIds,
            !batchToolCallIds.isEmpty() || newAnonymousToolBatch);
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

    private BatchSnapshot batchSnapshot(
        Set<String> responseIds,
        Set<String> toolCallIds,
        boolean toolBatch
    ) {
        return new BatchSnapshot(
            responseIds.stream().findFirst().orElse(null),
            digest(responseIds),
            digest(toolCallIds),
            Math.max(1, responseIds.size()),
            toolCallIds.size() + (toolBatch && toolCallIds.isEmpty() ? 1 : 0),
            toolBatch
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

    /** 单个流式响应片段中的低敏工具批次摘要。 */
    public record BatchSnapshot(
        String responseId,
        String responseIdsHash,
        String toolCallIdsHash,
        int responseCount,
        int toolCallCount,
        boolean toolBatch
    ) {
        public static BatchSnapshot empty() {
            return new BatchSnapshot(null, null, null, 0, 0, false);
        }

        public boolean hasToolBatch() {
            return toolBatch && toolCallCount > 0;
        }
    }
}
