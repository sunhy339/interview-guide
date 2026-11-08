package interview.guide.modules.agent.model;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;

class AgentProviderResponseTraceTest {

    @Test
    void emitsOnlyOneLowSensitivityToolBatchForRepeatedStreamingCallId() {
        AgentProviderResponseTrace trace = new AgentProviderResponseTrace();
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
            "call-1", "function", "schedule_list", "{}"
        );
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(call))
            .build();
        ChatResponse response = new ChatResponse(
            List.of(new Generation(message)),
            ChatResponseMetadata.builder().id("response-1").build()
        );

        AgentProviderResponseTrace.BatchSnapshot first = trace.observe(response);
        AgentProviderResponseTrace.BatchSnapshot repeated = trace.observe(response);

        assertThat(first.hasToolBatch()).isTrue();
        assertThat(first.toolCallCount()).isEqualTo(1);
        assertThat(repeated.hasToolBatch()).isFalse();
        assertThat(trace.snapshot().toolCallCount()).isEqualTo(1);
    }

    @Test
    void doesNotInflateAnonymousToolCallsAcrossStreamingFragments() {
        AgentProviderResponseTrace trace = new AgentProviderResponseTrace();
        AssistantMessage message = AssistantMessage.builder()
            .toolCalls(List.of(new AssistantMessage.ToolCall(
                "", "function", "schedule_list", "{}"
            )))
            .build();
        ChatResponse response = new ChatResponse(
            List.of(new Generation(message)),
            ChatResponseMetadata.builder().build()
        );

        AgentProviderResponseTrace.BatchSnapshot first = trace.observe(response);
        AgentProviderResponseTrace.BatchSnapshot repeated = trace.observe(response);

        assertThat(first.hasToolBatch()).isTrue();
        assertThat(repeated.hasToolBatch()).isFalse();
        assertThat(trace.snapshot().toolCallCount()).isEqualTo(1);
    }

    @Test
    void aggregatesProviderIdsAndToolCallBatchWithoutPersistingMessageContent() {
        AssistantMessage toolMessage = AssistantMessage.builder()
            .content("不应进入 Trace")
            .toolCalls(List.of(new AssistantMessage.ToolCall(
                "call-1", "function", "schedule_list", "{\"date\":\"tomorrow\"}"
            )))
            .build();
        ChatResponse first = new ChatResponse(
            List.of(new Generation(toolMessage)),
            ChatResponseMetadata.builder().id("response-1").model("provider-model").build()
        );
        ChatResponse second = new ChatResponse(
            List.of(new Generation(new AssistantMessage("最终回答"))),
            ChatResponseMetadata.builder().id("response-1").model("provider-model").build()
        );

        AgentProviderResponseTrace trace = new AgentProviderResponseTrace();
        trace.observe(first);
        trace.observe(second);

        AgentProviderResponseTrace.Snapshot snapshot = trace.snapshot();
        assertThat(snapshot.responseId()).isEqualTo("response-1");
        assertThat(snapshot.responseIdsHash()).hasSize(64);
        assertThat(snapshot.toolCallIdsHash()).hasSize(64);
        assertThat(snapshot.responseCount()).isEqualTo(2);
        assertThat(snapshot.toolCallCount()).isEqualTo(1);
    }
}
