package interview.guide.modules.agent.service;

import interview.guide.modules.agent.model.AgentRunStatus;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 应用重启后的 Agent 任务接管器。
 *
 * <p>只接管持久化为 RUNNING 且超过安全年龄的任务；接管动作由会话级悲观锁保护，
 * 因此多实例部署时同一任务只会被一个实例重新执行。</p>
 */
@Slf4j
@Service
public class AgentRunRecoveryService {
    private final AgentChatSessionRepository sessionRepository;
    private final AgentConversationService conversationService;
    private final InterviewAgentService agentService;
    private final AgentRetryProperties retryProperties;
    private final AgentActionService actionService;
    private final AgentRunStateService runStateService;

    /** 兼容旧测试和迁移期轻量调用方。 */
    public AgentRunRecoveryService(
        AgentChatSessionRepository sessionRepository,
        AgentConversationService conversationService,
        InterviewAgentService agentService,
        AgentRetryProperties retryProperties
    ) {
        this(sessionRepository, conversationService, agentService, retryProperties, null, null);
    }

    /** 兼容迁移期旧调用方；恢复边界服务缺失时沿用原有行为。 */
    public AgentRunRecoveryService(
        AgentChatSessionRepository sessionRepository,
        AgentConversationService conversationService,
        InterviewAgentService agentService,
        AgentRetryProperties retryProperties,
        AgentActionService actionService
    ) {
        this(sessionRepository, conversationService, agentService, retryProperties, actionService, null);
    }

    @Autowired
    public AgentRunRecoveryService(
        AgentChatSessionRepository sessionRepository,
        AgentConversationService conversationService,
        InterviewAgentService agentService,
        AgentRetryProperties retryProperties,
        AgentActionService actionService,
        AgentRunStateService runStateService
    ) {
        this.sessionRepository = sessionRepository;
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.retryProperties = retryProperties;
        this.actionService = actionService;
        this.runStateService = runStateService;
    }

    @Scheduled(fixedDelayString = "${app.ai.agent.recovery-scan-interval-ms:10000}")
    public void recoverInterruptedRuns() {
        if (!retryProperties.isRecoveryEnabled()) {
            return;
        }
        LocalDateTime staleBefore = LocalDateTime.now()
            .minusSeconds(retryProperties.getRecoveryMinAgeSeconds());
        sessionRepository.findByRunStatusAndRunStartedAtBefore(AgentRunStatus.RUNNING, staleBefore)
            .stream()
            .limit(16)
            .forEach(session -> {
                if (agentService.isLocallyActive(
                    session.getInterviewSessionId(), session.getAgentSessionId()
                )) {
                    return;
                }
                try {
                    AgentRunStateService.RecoveryDecision recoveryDecision = runStateService == null
                        ? AgentRunStateService.RecoveryDecision.SAFE_RETRY
                        : runStateService.recoveryDecision(session.getRunId());
                    if (recoveryDecision == AgentRunStateService.RecoveryDecision.DEFER
                        || recoveryDecision == AgentRunStateService.RecoveryDecision.TERMINAL) {
                        log.warn("恢复边界不可安全接管，暂不重放 Agent 任务: sessionId={}, runId={}, decision={}",
                            session.getInterviewSessionId(), session.getRunId(), recoveryDecision);
                        return;
                    }
                    if (recoveryDecision == AgentRunStateService.RecoveryDecision.BLOCK_UNCERTAIN_TOOL) {
                        boolean blocked = session.getAgentSessionId() == null
                            ? conversationService.failUncertainRecovery(
                                session.getInterviewSessionId(), null,
                                "上一次工具执行结果未知，已停止自动恢复。请先确认业务结果，再重新发起请求。")
                            : conversationService.failUncertainRecovery(
                                session.getInterviewSessionId(), session.getAgentSessionId(),
                                "上一次工具执行结果未知，已停止自动恢复。请先确认业务结果，再重新发起请求。");
                        if (blocked) {
                            log.warn("已阻止不确定工具任务自动重放: sessionId={}, runId={}",
                                session.getInterviewSessionId(), session.getRunId());
                        }
                        return;
                    }
                    AgentConversationService.ApprovalResumeCandidate candidate = session.getAgentSessionId() == null
                        ? conversationService.findApprovalResumeCandidate(
                            null, session.getInterviewSessionId(), staleBefore
                        )
                        : conversationService.findApprovalResumeCandidate(
                            session.getAgentSessionId(), session.getInterviewSessionId(), staleBefore
                        );
                    if (candidate == null
                        && recoveryDecision == AgentRunStateService.RecoveryDecision.RESUME_APPROVAL) {
                        log.warn("审批续跑边界存在但动作候选不可用，暂不重放原始请求: sessionId={}, runId={}",
                            session.getInterviewSessionId(), session.getRunId());
                        return;
                    }
                    if (candidate != null) {
                        if (actionService == null) {
                            log.warn("审批续跑动作服务未配置，暂不接管原任务: sessionId={}, actionId={}",
                                session.getInterviewSessionId(), candidate.actionId());
                            return;
                        }
                        AgentActionResponse action = actionService.loadApprovalResumeAction(
                            candidate.actionId(), candidate.ownerKey()
                        );
                        if (action == null) {
                            log.warn("审批续跑动作结果不可用，暂不接管原任务: sessionId={}, actionId={}",
                                session.getInterviewSessionId(), candidate.actionId());
                            return;
                        }
                        AgentConversationService.ApprovalResumeRequest approvalResume =
                            session.getAgentSessionId() == null
                                ? conversationService.claimApprovalResume(
                                    null, session.getInterviewSessionId(), staleBefore, candidate.runId()
                                )
                                : conversationService.claimApprovalResume(
                                    session.getAgentSessionId(), session.getInterviewSessionId(), staleBefore,
                                    candidate.runId()
                                );
                        if (approvalResume == null) {
                            return;
                        }
                        AgentConversationService.ApprovalResumeRequest enriched = approvalResume.withAction(action);
                        log.info("接管审批续跑任务: sessionId={}, runId={}, actionId={}",
                            enriched.interviewSessionId(), enriched.runId(), enriched.actionId());
                        agentService.resumeApprovedRun(enriched);
                        return;
                    }
                    if (recoveryDecision == AgentRunStateService.RecoveryDecision.WAIT_FOR_USER) {
                        log.warn("Agent 仍在等待用户确认，暂不重放原始请求: sessionId={}, runId={}",
                            session.getInterviewSessionId(), session.getRunId());
                        return;
                    }
                    AgentConversationService.RecoveredRun recovered = session.getAgentSessionId() == null
                        ? conversationService.claimRecoverableRun(
                            session.getInterviewSessionId(), staleBefore
                        )
                        : conversationService.claimRecoverableRun(
                            session.getAgentSessionId(), session.getInterviewSessionId(), staleBefore
                        );
                    if (recovered != null) {
                        log.info("接管应用重启遗留 Agent 任务: sessionId={}, oldRunId={}",
                            recovered.interviewSessionId(), session.getRunId());
                        agentService.recoverRun(recovered);
                    }
                } catch (Throwable error) {
                    log.warn("Agent 启动恢复扫描失败: sessionId={}", session.getInterviewSessionId(), error);
                }
            });
    }
}
