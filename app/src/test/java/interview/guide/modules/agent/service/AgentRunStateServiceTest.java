package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentRunStateEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentRunStateRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunStateServiceTest {

    @Mock private AgentRunStateRepository repository;

    @Test
    void startsAndFinishesAnIndependentCheckpointWithoutChangingSessionState() {
        when(repository.findByRunId("run-1")).thenReturn(Optional.empty());
        when(repository.save(any(AgentRunStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .runId("run-1")
            .ownerKey("user-1")
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .activeMessageId(11L)
            .runStatus(AgentRunStatus.RUNNING)
            .loopNextStep(2)
            .build();
        AgentRunStateService service = new AgentRunStateService(repository);

        service.start(session);

        ArgumentCaptor<AgentRunStateEntity> started = ArgumentCaptor.forClass(AgentRunStateEntity.class);
        verify(repository).save(started.capture());
        assertThat(started.getValue().getRunId()).isEqualTo("run-1");
        assertThat(started.getValue().getAgentSessionId()).isEqualTo("agent-1");
        assertThat(started.getValue().getStepIndex()).isEqualTo(2);
        assertThat(started.getValue().getStatus()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(started.getValue().getPendingApproval()).isFalse();
        assertThat(started.getValue().getHeartbeatAt()).isNotNull();
        assertThat(started.getValue().getLeaseUntil()).isNotNull();

        AgentRunStateEntity existing = started.getValue();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(existing));
        session.setRunStatus(AgentRunStatus.COMPLETED);
        session.setRunStep("action");
        service.finish(session);

        assertThat(existing.getStatus()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(existing.getPhase()).isEqualTo("action");
        assertThat(existing.getFinishedAt()).isNotNull();
    }

    @Test
    void ignoresIncompleteSessionIdentityDuringMigration() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .runId("run-1")
            .agentSessionId(null)
            .build();

        new AgentRunStateService(repository).start(session);

        verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void checkpointFailureDoesNotBreakTheMainAgentFlow() {
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .runStatus(AgentRunStatus.RUNNING)
            .build();
        when(repository.findByRunId("run-1"))
            .thenThrow(new IllegalStateException("migration_not_applied"));

        new AgentRunStateService(repository).checkpoint(session);

        assertThat(session.getRunStatus()).isEqualTo(AgentRunStatus.RUNNING);
    }
}
