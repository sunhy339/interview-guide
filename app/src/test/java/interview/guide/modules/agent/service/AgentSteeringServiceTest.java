package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.modules.agent.model.AgentChatMessageEntity;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.model.AgentSteeringMessageResponse;
import interview.guide.modules.agent.repository.AgentChatMessageRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Agent 运行期间补充输入")
class AgentSteeringServiceTest {

    @Mock private AgentChatSessionRepository sessionRepository;
    @Mock private AgentChatMessageRepository messageRepository;
    @Mock private AgentConversationService conversationService;

    private AgentSteeringService service;

    @BeforeEach
    void setUp() {
        service = new AgentSteeringService(
            sessionRepository,
            messageRepository,
            conversationService,
            new PromptSanitizer(new LlmProviderProperties())
        );
    }

    @Test
    @DisplayName("运行中追加消息只落当前会话，不创建第二个运行")
    void enqueuesMessageIntoCurrentRun() {
        AgentChatSessionEntity session = runningSession();
        when(conversationService.requireOwnerKey("user-1")).thenReturn("user-1");
        when(sessionRepository.findByAgentSessionIdForUpdate("agent-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findPendingSteeringForUpdate(7L, "run-1"))
            .thenReturn(List.of());
        when(messageRepository.findTopBySessionIdOrderBySequenceNumDesc(7L))
            .thenReturn(Optional.of(AgentChatMessageEntity.builder().sequenceNum(3).build()));
        when(messageRepository.save(any(AgentChatMessageEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentSteeringMessageResponse response = service.enqueue(
            null, "agent-1", "  请把刚才的结果整理成表格  ", "user-1"
        );

        assertThat(response.content()).isEqualTo("请把刚才的结果整理成表格");
        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(messageRepository).save(any(AgentChatMessageEntity.class));
    }

    @Test
    @DisplayName("工具回合只消费当前 run 的消息，并按发送顺序合并")
    void drainsCurrentRunMessagesOnce() {
        AgentChatSessionEntity session = runningSession();
        AgentChatMessageEntity first = steeringMessage(session, "先保留结论");
        AgentChatMessageEntity second = steeringMessage(session, "再补充风险");
        when(sessionRepository.findByAgentSessionIdForUpdate("agent-1"))
            .thenReturn(Optional.of(session));
        when(messageRepository.findPendingSteeringForUpdate(7L, "run-1"))
            .thenReturn(List.of(first, second));

        AgentSteeringService.SteeringBatch batch = service.drainForToolTurn("agent-1", "run-1");

        assertThat(batch.messages()).containsExactly("先保留结论", "再补充风险");
        assertThat(batch.asModelInput()).contains("消息 1", "消息 2", "先保留结论", "再补充风险");
        assertThat(first.getSteeringConsumed()).isTrue();
        assertThat(second.getSteeringConsumed()).isTrue();
        verify(messageRepository).saveAll(List.of(first, second));
    }

    @Test
    @DisplayName("不同 run 的工具回合不能消费旧消息")
    void doesNotDrainDifferentRun() {
        AgentChatSessionEntity session = runningSession();
        when(sessionRepository.findByAgentSessionIdForUpdate("agent-1"))
            .thenReturn(Optional.of(session));

        AgentSteeringService.SteeringBatch batch = service.drainForToolTurn("agent-1", "run-2");

        assertThat(batch.isEmpty()).isTrue();
        verify(messageRepository, org.mockito.Mockito.never())
            .findPendingSteeringForUpdate(anyLong(), eq("run-2"));
    }

    private AgentChatSessionEntity runningSession() {
        return AgentChatSessionEntity.builder()
            .id(7L)
            .agentSessionId("agent-1")
            .ownerKey("user-1")
            .runId("run-1")
            .runStatus(AgentRunStatus.RUNNING)
            .build();
    }

    private AgentChatMessageEntity steeringMessage(AgentChatSessionEntity session, String content) {
        return AgentChatMessageEntity.builder()
            .session(session)
            .role(AgentChatMessageEntity.MessageRole.USER)
            .content(content)
            .sequenceNum(1)
            .steering(true)
            .steeringConsumed(false)
            .steeringRunId("run-1")
            .build();
    }
}
