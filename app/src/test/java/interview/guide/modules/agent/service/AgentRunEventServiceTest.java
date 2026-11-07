package interview.guide.modules.agent.service;

import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.model.AgentRunEventEntity;
import interview.guide.modules.agent.model.AgentRunEventResponse;
import interview.guide.modules.agent.repository.AgentRunEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRunEventServiceTest {

    @Mock private AgentRunEventRepository repository;

    @Test
    void persistsOnlyStableIdsAndHashesForModelAndToolEvents() {
        when(repository.save(any(AgentRunEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentRunEventService service = new AgentRunEventService(repository);
        AgentMetrics.RunTracker tracker = tracker("run-1");

        service.append(
            tracker, "TOOL_STARTED", "turn-1", "tool-1", "schedule_list",
            "{\"secret\":\"do-not-store\"}", "结果正文不落库", "started"
        );

        ArgumentCaptor<AgentRunEventEntity> captor = ArgumentCaptor.forClass(AgentRunEventEntity.class);
        verify(repository).save(captor.capture());
        AgentRunEventEntity saved = captor.getValue();
        assertThat(saved.getRunId()).isEqualTo("run-1");
        assertThat(saved.getEventType()).isEqualTo("TOOL_STARTED");
        assertThat(saved.getToolCallId()).isEqualTo("tool-1");
        assertThat(saved.getArgumentsHash()).hasSize(64);
        assertThat(saved.getResultHash()).hasSize(64);
        assertThat(saved.toString()).doesNotContain("do-not-store", "结果正文");
    }

    @Test
    void listsEventsWithinOwnerAndSessionBoundary() {
        AgentRunEventEntity event = AgentRunEventEntity.builder()
            .runId("run-1").agentSessionId("agent-1").eventType("MODEL_TURN_STARTED")
            .status("normal").build();
        when(repository.findTop200ByOwnerKeyAndAgentSessionIdAndRunIdOrderByIdAsc(
            "owner-1", "agent-1", "run-1"
        )).thenReturn(List.of(event));

        List<AgentRunEventResponse> result = new AgentRunEventService(repository)
            .listForOwner("owner-1", "agent-1", "run-1", 20);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().eventType()).isEqualTo("MODEL_TURN_STARTED");
        verify(repository).findTop200ByOwnerKeyAndAgentSessionIdAndRunIdOrderByIdAsc(
            "owner-1", "agent-1", "run-1"
        );
    }

    private AgentMetrics.RunTracker tracker(String runId) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        AgentMetrics metrics = new AgentMetrics(provider, false);
        AgentMetrics.RunTracker tracker = metrics.startRun(4, 4);
        metrics.setAuditContext(tracker, "owner-1", "interview-1", "agent-1", runId);
        return tracker;
    }
}
