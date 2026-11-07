package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAuditEventEntity;
import interview.guide.modules.agent.model.AgentAuditEventResponse;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.repository.AgentAuditEventRepository;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAuditServiceTest {

    @Test
    void recordsOnlyBoundedLowSensitivityFields() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);

        service.recordQuietly(
            " user-1 ", "interview-1", "agent-1", "run-1", "MEMORY_UPDATED",
            "memory-item", "item-1", "success"
        );

        var captor = org.mockito.ArgumentCaptor.forClass(AgentAuditEventEntity.class);
        verify(repository).save(captor.capture());
        AgentAuditEventEntity event = captor.getValue();
        assertThat(event.getOwnerKey()).isEqualTo("user-1");
        assertThat(event.getAction()).isEqualTo("MEMORY_UPDATED");
        assertThat(event.getResourceType()).isEqualTo("memory-item");
        assertThat(event.getCreatedAt()).isNotNull();
    }

    @Test
    void auditFailureDoesNotEscapeToAgentFlow() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenThrow(new IllegalStateException("audit unavailable"));

        new AgentAuditService(repository).recordQuietly(
            "user-1", null, null, null, "RUN_STARTED", "agent-run", "run-1", "success"
        );
    }

    @Test
    void redactsNonStableAuditValuesBeforePersistence() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);

        service.recordQuietly(
            "user-1", null, null, null,
            "PROMPT_INJECTION_DETECTED", "agent-input", "message", "原始攻击载荷\nsecret"
        );

        var captor = org.mockito.ArgumentCaptor.forClass(AgentAuditEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("PROMPT_INJECTION_DETECTED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("agent-input");
        assertThat(captor.getValue().getResourceId()).isEqualTo("message");
        assertThat(captor.getValue().getResult()).isEqualTo("redacted");
        assertThat(captor.getValue().getResult()).doesNotContain("secret");
    }

    @Test
    void recordsPromptSecurityDecisionWithoutPayload() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);

        service.recordPromptInjectionDetected("user-1", "interview-1", "agent-1", "run-1", "message");

        var captor = org.mockito.ArgumentCaptor.forClass(AgentAuditEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("PROMPT_INJECTION_DETECTED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("agent-input");
        assertThat(captor.getValue().getResourceId()).isEqualTo("message");
        assertThat(captor.getValue().getResult()).isEqualTo("sanitized");
    }

    @Test
    void recordsContextSecurityDecisionWithStableResourceOnly() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);

        service.recordContextInjectionDetected(
            "user-1", "interview-1", "agent-1", "context-asset", "asset-1"
        );

        var captor = org.mockito.ArgumentCaptor.forClass(AgentAuditEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("CONTEXT_INJECTION_DETECTED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("context-asset");
        assertThat(captor.getValue().getResourceId()).isEqualTo("asset-1");
        assertThat(captor.getValue().getResult()).isEqualTo("sanitized");
    }

    @Test
    void defersAuditUntilBusinessTransactionCommits() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.recordQuietly(
                "user-1", "interview-1", "agent-1", "run-1", "RUN_COMPLETED",
                "agent-run", "run-1", "success"
            );
            verify(repository, never()).save(any(AgentAuditEventEntity.class));

            TransactionSynchronization synchronization = TransactionSynchronizationManager
                .getSynchronizations().getFirst();
            synchronization.afterCommit();
            verify(repository).save(any(AgentAuditEventEntity.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void listsOnlyCurrentOwnerEventsAndHonorsSmallLimit() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.findTop100ByOwnerKeyOrderByCreatedAtDesc("user-1"))
            .thenReturn(List.of(
                AgentAuditEventEntity.builder()
                    .interviewSessionId("interview-1")
                    .agentSessionId("agent-1")
                    .action("RUN_COMPLETED")
                    .resourceType("agent-run")
                    .resourceId("run-1")
                    .result("COMPLETED")
                    .createdAt(LocalDateTime.now())
                    .build(),
                AgentAuditEventEntity.builder()
                    .action("MEMORY_UPDATED")
                    .resourceType("memory-item")
                    .resourceId("item-1")
                    .createdAt(LocalDateTime.now().minusMinutes(1))
                    .build()
            ));

        var events = new AgentAuditService(repository).listForOwner(" user-1 ", 1);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().action()).isEqualTo("RUN_COMPLETED");
        verify(repository).findTop100ByOwnerKeyOrderByCreatedAtDesc("user-1");
    }

    @Test
    void enrichesEventsWithInterviewAndAgentSessionNames() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        InterviewSessionRepository interviewRepository = mock(InterviewSessionRepository.class);
        AgentChatSessionRepository agentRepository = mock(AgentChatSessionRepository.class);
        AgentAuditEventEntity event = AgentAuditEventEntity.builder()
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .action("RUN_COMPLETED")
            .resourceType("agent-run")
            .result("success")
            .createdAt(LocalDateTime.now())
            .build();
        when(repository.findTop100ByOwnerKeyOrderByCreatedAtDesc("user-1"))
            .thenReturn(List.of(event));
        InterviewSessionEntity interview = new InterviewSessionEntity();
        interview.setSessionId("interview-1");
        interview.setTitle("Java 后端高并发面试");
        when(interviewRepository.findAllBySessionIdInAndOwnerId(Set.of("interview-1"), "user-1"))
            .thenReturn(List.of(interview));
        AgentChatSessionEntity agentSession = AgentChatSessionEntity.builder()
            .agentSessionId("agent-1")
            .ownerKey("user-1")
            .title("第一轮复盘")
            .build();
        when(agentRepository.findAllByAgentSessionIdInAndOwnerKey(Set.of("agent-1"), "user-1"))
            .thenReturn(List.of(agentSession));

        var events = new AgentAuditService(repository, null, interviewRepository, agentRepository)
            .listForOwner("user-1", 20);

        assertThat(events).singleElement().satisfies(item -> {
            assertThat(item.interviewTitle()).isEqualTo("Java 后端高并发面试");
            assertThat(item.agentSessionTitle()).isEqualTo("第一轮复盘");
        });
    }

    @Test
    void recordsRunTraceFromTrackerWithoutPersistingPromptData() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        when(repository.save(any(AgentAuditEventEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentAuditService service = new AgentAuditService(repository);
        AgentMetrics metrics = new AgentMetrics(null, false);
        AgentMetrics.RunTracker tracker = metrics.startRun(3, 3);
        metrics.setAuditContext(tracker, "user-1", "interview-1", "agent-1", "run-1");

        service.recordRunTrace(tracker, "MODEL_COMPLETED", "agent-model", "provider-a", "success");

        var captor = org.mockito.ArgumentCaptor.forClass(AgentAuditEventEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getRunId()).isEqualTo("run-1");
        assertThat(captor.getValue().getAction()).isEqualTo("MODEL_COMPLETED");
        assertThat(captor.getValue().getResourceId()).isEqualTo("provider-a");
    }

    @Test
    void listsTraceScopedToOwnerSessionAndRunInExecutionOrder() {
        AgentAuditEventRepository repository = mock(AgentAuditEventRepository.class);
        LocalDateTime first = LocalDateTime.now().minusSeconds(2);
        LocalDateTime second = LocalDateTime.now().minusSeconds(1);
        when(repository.findTop100ByOwnerKeyAndAgentSessionIdAndRunIdOrderByCreatedAtAsc(
            "user-1", "agent-1", "run-1"
        )).thenReturn(List.of(
            AgentAuditEventEntity.builder().agentSessionId("agent-1").runId("run-1")
                .action("ROUTE_SELECTED").resourceType("agent-route").result("selected")
                .createdAt(first).build(),
            AgentAuditEventEntity.builder().agentSessionId("agent-1").runId("run-1")
                .action("MODEL_COMPLETED").resourceType("agent-model").result("success")
                .createdAt(second).build()
        ));

        var events = new AgentAuditService(repository).listTrace("user-1", "agent-1", "run-1", 100);

        assertThat(events).extracting(AgentAuditEventResponse::action)
            .containsExactly("ROUTE_SELECTED", "MODEL_COMPLETED");
        verify(repository).findTop100ByOwnerKeyAndAgentSessionIdAndRunIdOrderByCreatedAtAsc(
            "user-1", "agent-1", "run-1"
        );
    }
}
