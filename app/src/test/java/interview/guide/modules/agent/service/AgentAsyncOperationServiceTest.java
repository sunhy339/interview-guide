package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentAsyncOperationEntity;
import interview.guide.modules.agent.model.AgentAsyncOperationStatus;
import interview.guide.modules.agent.repository.AgentAsyncOperationRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentAsyncOperationServiceTest {

    @Mock private AgentAsyncOperationRepository repository;
    @Mock private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock private AgentRunEventService runEventService;

    @Test
    void registersSubmittedOperationAndLinksItToTheRun() {
        when(repository.findByOwnerKeyAndRunIdAndKnowledgeBaseIdAndOperation(
            "owner-1", "run-1", 42L, AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE
        )).thenReturn(Optional.empty());
        when(repository.save(any(AgentAsyncOperationEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeBaseRepository.findById(42L)).thenReturn(Optional.of(knowledgeBase(VectorStatus.PENDING)));

        AgentAsyncOperationEntity result = service().registerKnowledgeBaseVectorization(
            " owner-1 ", "agent-1", "run-1", "asset-1", 42L
        );

        assertThat(result.getOperationId()).isNotBlank();
        assertThat(result.getOwnerKey()).isEqualTo("owner-1");
        assertThat(result.getAgentSessionId()).isEqualTo("agent-1");
        assertThat(result.getRunId()).isEqualTo("run-1");
        assertThat(result.getKnowledgeBaseId()).isEqualTo(42L);
        assertThat(result.getStatus()).isEqualTo(AgentAsyncOperationStatus.SUBMITTED);
        verify(runEventService).appendForContext(
            "owner-1", "agent-1", "run-1", "ASYNC_OPERATION_SUBMITTED", "asset-1",
            AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE, "async_job_submitted", "SUBMITTED"
        );
    }

    @Test
    void doesNotCreateDuplicateOperationForTheSameRunAndKnowledgeBase() {
        AgentAsyncOperationEntity existing = AgentAsyncOperationEntity.builder()
            .operationId("operation-1")
            .ownerKey("owner-1")
            .runId("run-1")
            .knowledgeBaseId(42L)
            .operation(AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE)
            .status(AgentAsyncOperationStatus.SUBMITTED)
            .build();
        when(repository.findByOwnerKeyAndRunIdAndKnowledgeBaseIdAndOperation(
            "owner-1", "run-1", 42L, AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE
        )).thenReturn(Optional.of(existing));

        AgentAsyncOperationEntity result = service().registerKnowledgeBaseVectorization(
            "owner-1", "agent-1", "run-1", "asset-1", 42L
        );

        assertThat(result).isSameAs(existing);
        verify(repository, never()).save(any(AgentAsyncOperationEntity.class));
        verify(runEventService, never()).appendForContext(
            any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void convergesToCompletedWhenTheQueueTaskFinishedBeforeRegistration() {
        when(repository.findByOwnerKeyAndRunIdAndKnowledgeBaseIdAndOperation(
            "owner-1", "run-1", 42L, AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE
        )).thenReturn(Optional.empty());
        when(repository.save(any(AgentAsyncOperationEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledgeBaseRepository.findById(42L)).thenReturn(Optional.of(knowledgeBase(VectorStatus.COMPLETED)));

        AgentAsyncOperationEntity result = service().registerKnowledgeBaseVectorization(
            "owner-1", "agent-1", "run-1", "asset-1", 42L
        );

        assertThat(result.getStatus()).isEqualTo(AgentAsyncOperationStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        verify(runEventService).appendForContext(
            "owner-1", "agent-1", "run-1", "ASYNC_OPERATION_COMPLETED", "asset-1",
            AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE, "vector_index_ready", "COMPLETED"
        );
    }

    @Test
    void reflectsProcessingRetryAndFailureTransitionsWithoutChangingTerminalState() {
        AgentAsyncOperationEntity operation = AgentAsyncOperationEntity.builder()
            .operationId("operation-1")
            .ownerKey("owner-1")
            .agentSessionId("agent-1")
            .runId("run-1")
            .assetId("asset-1")
            .knowledgeBaseId(42L)
            .operation(AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE)
            .status(AgentAsyncOperationStatus.SUBMITTED)
            .attempt(0)
            .build();
        when(repository.findByKnowledgeBaseIdAndOperation(42L, AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE))
            .thenReturn(List.of(operation));
        when(repository.save(any(AgentAsyncOperationEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        AgentAsyncOperationService service = service();
        service.markProcessing(42L);
        assertThat(operation.getStatus()).isEqualTo(AgentAsyncOperationStatus.PROCESSING);
        service.markRetrying(42L, "provider timeout");
        assertThat(operation.getStatus()).isEqualTo(AgentAsyncOperationStatus.RETRYING);
        service.markCompleted(42L);
        assertThat(operation.getStatus()).isEqualTo(AgentAsyncOperationStatus.COMPLETED);
        service.markFailed(42L, "late failure must be ignored");
        assertThat(operation.getStatus()).isEqualTo(AgentAsyncOperationStatus.COMPLETED);

        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        verify(runEventService, org.mockito.Mockito.times(3)).appendForContext(
            org.mockito.ArgumentMatchers.eq("owner-1"), org.mockito.ArgumentMatchers.eq("agent-1"),
            org.mockito.ArgumentMatchers.eq("run-1"), eventType.capture(), org.mockito.ArgumentMatchers.eq("asset-1"),
            org.mockito.ArgumentMatchers.eq(AgentAsyncOperationService.KNOWLEDGE_BASE_VECTORIZE),
            any(), any()
        );
        assertThat(eventType.getAllValues()).containsExactly(
            "ASYNC_OPERATION_PROCESSING", "ASYNC_OPERATION_RETRYING", "ASYNC_OPERATION_COMPLETED"
        );
    }

    private AgentAsyncOperationService service() {
        return new AgentAsyncOperationService(repository, knowledgeBaseRepository, runEventService);
    }

    private KnowledgeBaseEntity knowledgeBase(VectorStatus status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setVectorStatus(status);
        return entity;
    }
}
