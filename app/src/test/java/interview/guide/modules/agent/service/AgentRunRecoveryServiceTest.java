package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.model.AgentActionStatus;
import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunRecoveryServiceTest {

    @Test
    void onlyClaimsPersistedRunningTasksOlderThanRecoveryFence() {
        AgentChatSessionRepository repository = mock(AgentChatSessionRepository.class);
        AgentConversationService conversation = mock(AgentConversationService.class);
        InterviewAgentService agent = mock(InterviewAgentService.class);
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setRecoveryMinAgeSeconds(30);
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId("session-1")
            .runId("old-run")
            .runStatus(AgentRunStatus.RUNNING)
            .runStartedAt(LocalDateTime.now().minusMinutes(2))
            .build();
        when(repository.findByRunStatusAndRunStartedAtBefore(eq(AgentRunStatus.RUNNING), any()))
            .thenReturn(List.of(session));
        when(conversation.claimRecoverableRun(eq("session-1"), any()))
            .thenReturn(new AgentConversationService.RecoveredRun(
                "session-1", "review", "provider", "memory", "请恢复", 10L, "new-run"
            ));

        new AgentRunRecoveryService(repository, conversation, agent, properties).recoverInterruptedRuns();

        verify(conversation).claimRecoverableRun(eq("session-1"), any());
        verify(agent).recoverRun(any(AgentConversationService.RecoveredRun.class));
    }

    @Test
    void canDisableRecoveryWithoutTouchingRepository() {
        AgentChatSessionRepository repository = mock(AgentChatSessionRepository.class);
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setRecoveryEnabled(false);

        new AgentRunRecoveryService(
            repository, mock(AgentConversationService.class), mock(InterviewAgentService.class), properties
        ).recoverInterruptedRuns();

        verify(repository, never()).findByRunStatusAndRunStartedAtBefore(any(), any());
    }

    @Test
    void resumesCommittedApprovalInsteadOfReplayingOriginalUserInstruction() {
        AgentChatSessionRepository repository = mock(AgentChatSessionRepository.class);
        AgentConversationService conversation = mock(AgentConversationService.class);
        InterviewAgentService agent = mock(InterviewAgentService.class);
        AgentActionService actions = mock(AgentActionService.class);
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setRecoveryMinAgeSeconds(30);
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .ownerKey("user-1")
            .runId("run-1")
            .runStatus(AgentRunStatus.RUNNING)
            .runStartedAt(LocalDateTime.now().minusMinutes(2))
            .build();
        AgentConversationService.ApprovalResumeRequest pending =
            new AgentConversationService.ApprovalResumeRequest(
                "interview-1", "agent-1", "general", "provider", "user-1", "user-1",
                "帮我安排明天12点的面试", 23L, "run-1", "用户已确认", "action-1",
                null, null, null
            );
        AgentActionResponse action = org.mockito.Mockito.mock(AgentActionResponse.class);
        when(action.actionId()).thenReturn("action-1");
        when(action.title()).thenReturn("安排面试日程");
        when(action.status()).thenReturn(AgentActionStatus.SUCCEEDED);
        when(action.resultSummary()).thenReturn("日程已创建");
        when(repository.findByRunStatusAndRunStartedAtBefore(eq(AgentRunStatus.RUNNING), any()))
            .thenReturn(List.of(session));
        when(conversation.findApprovalResumeCandidate(eq("agent-1"), eq("interview-1"), any()))
            .thenReturn(new AgentConversationService.ApprovalResumeCandidate(
                "run-1", "action-1", "user-1"
            ));
        when(conversation.claimApprovalResume(eq("agent-1"), eq("interview-1"), any(), eq("run-1")))
            .thenReturn(pending);
        when(actions.loadApprovalResumeAction("action-1", "user-1"))
            .thenReturn(action);

        new AgentRunRecoveryService(repository, conversation, agent, properties, actions)
            .recoverInterruptedRuns();

        verify(agent).resumeApprovedRun(org.mockito.ArgumentMatchers.argThat(
            request -> "action-1".equals(request.actionId())
                && "安排面试日程".equals(request.actionTitle())
                && "SUCCEEDED".equals(request.actionStatus())
        ));
        verify(conversation, never()).claimRecoverableRun(any(), any(), any());
        verify(agent, never()).recoverRun(any(AgentConversationService.RecoveredRun.class));
    }

    @Test
    void blocksRecoveryWhenToolResultIsUnknownInsteadOfReplayingTheRun() {
        AgentChatSessionRepository repository = mock(AgentChatSessionRepository.class);
        AgentConversationService conversation = mock(AgentConversationService.class);
        InterviewAgentService agent = mock(InterviewAgentService.class);
        AgentRunStateService runState = mock(AgentRunStateService.class);
        AgentRetryProperties properties = new AgentRetryProperties();
        properties.setRecoveryMinAgeSeconds(30);
        AgentChatSessionEntity session = AgentChatSessionEntity.builder()
            .interviewSessionId("interview-1")
            .agentSessionId("agent-1")
            .runId("run-1")
            .runStatus(AgentRunStatus.RUNNING)
            .runStartedAt(LocalDateTime.now().minusMinutes(2))
            .build();
        when(repository.findByRunStatusAndRunStartedAtBefore(eq(AgentRunStatus.RUNNING), any()))
            .thenReturn(List.of(session));
        when(runState.recoveryDecision("run-1"))
            .thenReturn(AgentRunStateService.RecoveryDecision.BLOCK_UNCERTAIN_TOOL);
        when(conversation.failUncertainRecovery(
            eq("interview-1"), eq("agent-1"), any()
        )).thenReturn(true);

        new AgentRunRecoveryService(repository, conversation, agent, properties, null, runState)
            .recoverInterruptedRuns();

        verify(conversation).failUncertainRecovery(eq("interview-1"), eq("agent-1"), any());
        verify(conversation, never()).claimRecoverableRun(any(), any(), any());
        verify(agent, never()).recoverRun(any(AgentConversationService.RecoveredRun.class));
    }
}
