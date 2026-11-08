package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentProviderResponseTrace;
import interview.guide.modules.agent.model.AgentRecoveryBoundary;
import interview.guide.modules.agent.model.AgentRunStateEntity;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import interview.guide.modules.agent.repository.AgentRunStateRepository;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
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
        assertThat(started.getValue().getRecoveryBoundary())
            .isEqualTo(AgentRecoveryBoundary.CONTEXT);
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

    @Test
    void persistsModelTurnBoundaryWithoutPersistingPromptContent() {
        AgentRunStateEntity state = AgentRunStateEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .status(AgentRunStatus.RUNNING)
            .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(state));
        when(repository.save(any(AgentRunStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentMetrics.RunTracker tracker = tracker("run-1");
        AgentRunStateService service = new AgentRunStateService(repository);

        service.modelTurnStarted(tracker, "pixel", false);

        assertThat(state.getModelTurnIndex()).isEqualTo(1);
        assertThat(state.getModelTurnId()).startsWith("turn-");
        assertThat(state.getModelProvider()).isEqualTo("pixel");
        assertThat(state.getModelTurnStatus()).isEqualTo("STARTED");
        assertThat(state.getPhase()).isEqualTo("model");
        assertThat(state.getRecoveryBoundary())
            .isEqualTo(AgentRecoveryBoundary.MODEL_TURN_IN_PROGRESS);
        assertThat(state.getModelResultReference()).isNull();

        service.modelTurnStarted(tracker, "pixel", true, "turn-17");
        assertThat(state.getModelTurnIndex()).isEqualTo(2);
        assertThat(state.getModelTurnId()).isEqualTo("turn-17");

        service.modelTurnFinished(tracker, "pixel", "success");

        assertThat(state.getModelTurnStatus()).isEqualTo("SUCCEEDED");
        assertThat(state.getModelResultReference()).isEqualTo("status:succeeded");
        assertThat(state.getRecoveryBoundary())
            .isEqualTo(AgentRecoveryBoundary.MODEL_RESPONSE_OBSERVED);

        state.setModelTurnStatus("STARTED");
        service.markInterrupted("run-1");
        assertThat(state.getModelTurnStatus()).isEqualTo("INTERRUPTED");
        assertThat(state.getModelResultReference()).isEqualTo("status:interrupted");
        assertThat(state.getPhase()).isEqualTo("model_interrupted");
        assertThat(service.recoveryDecision("run-1"))
            .isEqualTo(AgentRunStateService.RecoveryDecision.SAFE_RETRY);
    }

    @Test
    void correlatesToolResultWithTheRunLocalToolCallId() {
        AgentRunStateEntity state = AgentRunStateEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .status(AgentRunStatus.RUNNING)
            .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(state));
        when(repository.save(any(AgentRunStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunStateService service = new AgentRunStateService(repository);
        AgentMetrics.RunTracker tracker = tracker("run-1");

        String toolCallId = service.recordToolCall(tracker, "schedule_list", "{}");
        service.recordToolResult(
            tracker, "knowledge_base_search", toolCallId,
            "<data-boundary-ab12cd34-agent-tool-result>"
                + "{\"contractVersion\":\"agent-rag.result.v1\",\"status\":\"RETRIEVED\"}"
                + "</data-boundary-ab12cd34-agent-tool-result>", false
        );

        assertThat(toolCallId).isEqualTo("tool-1");
        assertThat(state.getToolCallId()).isEqualTo("tool-1");
        assertThat(state.getToolName()).isEqualTo("knowledge_base_search");
        assertThat(state.getToolResultReference()).startsWith("sha256:");
        assertThat(state.getToolResultContractVersion()).isEqualTo("agent-rag.result.v1");
        assertThat(state.getToolResultStatus()).isEqualTo("RETRIEVED");
        assertThat(state.getPendingApproval()).isFalse();
        assertThat(state.getRecoveryBoundary())
            .isEqualTo(AgentRecoveryBoundary.TOOL_RESULT_OBSERVED);
    }

    @Test
    void blocksRecoveryWhenToolExecutionHasNoObservedResult() {
        AgentRunStateEntity state = AgentRunStateEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .status(AgentRunStatus.RUNNING)
            .phase("tool")
            .toolCallId("tool-1")
            .recoveryBoundary(AgentRecoveryBoundary.TOOL_EXECUTION_IN_PROGRESS)
            .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(state));
        when(repository.save(any(AgentRunStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunStateService service = new AgentRunStateService(repository);
        service.markInterrupted("run-1");

        assertThat(state.getRecoveryBoundary())
            .isEqualTo(AgentRecoveryBoundary.TOOL_EXECUTION_UNCERTAIN);
        assertThat(service.recoveryDecision("run-1"))
            .isEqualTo(AgentRunStateService.RecoveryDecision.BLOCK_UNCERTAIN_TOOL);
    }

    @Test
    void keepsApprovalResumeRecoverableAsItsOwnDecision() {
        AgentRunStateEntity state = AgentRunStateEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .status(AgentRunStatus.RUNNING)
            .recoveryBoundary(AgentRecoveryBoundary.APPROVAL_RESUME_PENDING)
            .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(state));

        assertThat(new AgentRunStateService(repository).recoveryDecision("run-1"))
            .isEqualTo(AgentRunStateService.RecoveryDecision.RESUME_APPROVAL);
    }

    @Test
    void defersRecoveryWhenBoundaryCannotBeRead() {
        when(repository.findByRunId("run-1"))
            .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(new AgentRunStateService(repository).recoveryDecision("run-1"))
            .isEqualTo(AgentRunStateService.RecoveryDecision.DEFER);
    }

    @Test
    void checkpointsProviderResponseMetadataWithoutTreatingItAsBusinessSuccess() {
        AgentRunStateEntity state = AgentRunStateEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .status(AgentRunStatus.RUNNING)
            .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(state));
        when(repository.save(any(AgentRunStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        AgentProviderResponseTrace trace = new AgentProviderResponseTrace();
        trace.observe(new org.springframework.ai.chat.model.ChatResponse(
            java.util.List.of(new org.springframework.ai.chat.model.Generation(
                new org.springframework.ai.chat.messages.AssistantMessage("回答")
            )),
            org.springframework.ai.chat.metadata.ChatResponseMetadata.builder()
                .id("response-1").build()
        ));

        AgentRunStateService service = new AgentRunStateService(repository);
        service.modelResponseObserved(tracker("run-1"), trace.snapshot());

        assertThat(state.getProviderResponseId()).isEqualTo("response-1");
        assertThat(state.getProviderResponseIdsHash()).hasSize(64);
        assertThat(state.getProviderResponseCount()).isEqualTo(1);
        assertThat(state.getModelTurnStatus()).isNull();
    }

    @Test
    void heartbeatExtendsLeaseAndLiveLeaseProtectsActiveRun() {
        AgentRunStateEntity state = AgentRunStateEntity.builder()
            .runId("run-1")
            .agentSessionId("agent-1")
            .status(AgentRunStatus.RUNNING)
            .leaseUntil(LocalDateTime.now().minusSeconds(1))
            .build();
        when(repository.findByRunId("run-1")).thenReturn(Optional.of(state));
        when(repository.save(any(AgentRunStateEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentRunStateService service = new AgentRunStateService(repository);
        AgentMetrics.RunTracker tracker = tracker("run-1");

        service.heartbeat(tracker);

        assertThat(state.getLeaseUntil()).isAfter(LocalDateTime.now());
        assertThat(service.hasLiveLease("run-1")).isTrue();
        verify(repository).save(state);
    }

    @Test
    void leaseReadFailureDoesNotAllowRecoveryToTakeOver() {
        when(repository.findByRunId("run-1"))
            .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(new AgentRunStateService(repository).hasLiveLease("run-1")).isTrue();
    }

    private AgentMetrics.RunTracker tracker(String runId) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        AgentMetrics metrics = new AgentMetrics(provider, false);
        AgentMetrics.RunTracker tracker = metrics.startRun(4, 4);
        metrics.setAuditContext(tracker, "user-1", "interview-1", "agent-1", runId);
        return tracker;
    }
}
