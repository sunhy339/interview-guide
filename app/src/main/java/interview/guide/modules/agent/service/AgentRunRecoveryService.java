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

    /** 兼容旧测试和迁移期轻量调用方。 */
    public AgentRunRecoveryService(
        AgentChatSessionRepository sessionRepository,
        AgentConversationService conversationService,
        InterviewAgentService agentService,
        AgentRetryProperties retryProperties
    ) {
        this(sessionRepository, conversationService, agentService, retryProperties, null);
    }

    @Autowired
    public AgentRunRecoveryService(
        AgentChatSessionRepository sessionRepository,
        AgentConversationService conversationService,
        InterviewAgentService agentService,
        AgentRetryProperties retryProperties,
        AgentActionService actionService
    ) {
        this.sessionRepository = sessionRepository;
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.retryProperties = retryProperties;
        this.actionService = actionService;
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
                    AgentConversationService.ApprovalResumeCandidate candidate = session.getAgentSessionId() == null
                        ? conversationService.findApprovalResumeCandidate(
                            null, session.getInterviewSessionId(), staleBefore
                        )
                        : conversationService.findApprovalResumeCandidate(
                            session.getAgentSessionId(), session.getInterviewSessionId(), staleBefore
                        );
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
