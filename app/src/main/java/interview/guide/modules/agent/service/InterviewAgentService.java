package interview.guide.modules.agent.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.model.AgentLoopTraceItem;
import interview.guide.modules.agent.metrics.AgentMetrics;
import interview.guide.modules.agent.metrics.AgentRunBudgetExceededException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * 面试教练 Agent 编排服务。
 *
 * <p>采用有边界的 Agent Loop：读取上下文 -> 规划 -> 通过只读 Skill/RAG 工具观察 ->
 * 流式执行最终回答。对话记录由独立的 Agent 会话服务持久化，不改变既有面试和 RAG 会话。</p>
 */
@Slf4j
@Service
public class InterviewAgentService {
    /** 重试次数和等待策略由 AgentRetryProperties 控制，并通过 SSE 持续告知前端。 */
    /** 保护 SSE、内存和数据库；正常面试复盘远低于此上限。 */
    private static final int MAX_AGENT_OUTPUT_CHARS = 32_000;
    private static final int CONTEXT_RETRY_HISTORY_CHARS = 8_000;
    private static final int CONTEXT_RETRY_MESSAGE_CHARS = 4_000;
    private static final int CONTEXT_RETRY_REPORT_CHARS = 6_000;
    private static final int CONTEXT_RETRY_SKILL_CHARS = 3_000;
    private static final int CONTEXT_RETRY_RAG_CHARS = 4_000;
    private static final String OUTPUT_TRUNCATION_NOTICE = "\n\n[回答已达到展示上限，后续内容已截断]";
    private static final String EXPLICIT_ACTION_SYSTEM_PROMPT = """
        你是受控的面试日程操作 Agent。
        当前用户明确要求执行一项日程操作。必须先调用当前提供的唯一受控工具，不能只输出
        Markdown 假设操作已完成，也不能直接写入正式日程。工具参数只能来自当前用户消息；
        缺少必填信息时不要伪造，先让用户补充。工具返回待确认草稿后，再用简洁中文说明结果。
        """;

    private final AgentContextService agentContextService;
    private final AgentConversationService agentConversationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final PromptSanitizer promptSanitizer;
    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate fixedContextPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final AgentMetrics agentMetrics;
    private final AgentProgressWriter agentProgressWriter;
    private final AgentRetryProperties retryProperties;
    private final AgentLoopPlanner agentLoopPlanner;
    private final AgentAuditService agentAuditService;
    private final AgentIntentRouter agentIntentRouter;
    private final AgentContextToolCallbackFactory agentContextToolCallbackFactory;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private AgentRunStateService runStateService;
    private final Set<String> activeSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, Sinks.One<Void>> cancellationSignals = new ConcurrentHashMap<>();
    private final Map<String, AgentTaskHandle> activeTasks = new ConcurrentHashMap<>();

    public InterviewAgentService(
        AgentContextService agentContextService,
        AgentConversationService agentConversationService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader,
        AgentMetrics agentMetrics,
        AgentProgressWriter agentProgressWriter,
        AgentRetryProperties retryProperties
    ) throws IOException {
        this(
            agentContextService, agentConversationService, llmProviderRegistry, promptSanitizer,
            resourceLoader, agentMetrics, agentProgressWriter, retryProperties,
            null, null, null, null, null
        );
    }

    public InterviewAgentService(
        AgentContextService agentContextService,
        AgentConversationService agentConversationService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader,
        AgentMetrics agentMetrics,
        AgentProgressWriter agentProgressWriter,
        AgentRetryProperties retryProperties,
        AgentLoopPlanner agentLoopPlanner
    ) throws IOException {
        this(
            agentContextService, agentConversationService, llmProviderRegistry, promptSanitizer,
            resourceLoader, agentMetrics, agentProgressWriter, retryProperties,
            agentLoopPlanner, null, null, null, null
        );
    }

    @Autowired
    public InterviewAgentService(
        AgentContextService agentContextService,
        AgentConversationService agentConversationService,
        LlmProviderRegistry llmProviderRegistry,
        PromptSanitizer promptSanitizer,
        ResourceLoader resourceLoader,
        AgentMetrics agentMetrics,
        AgentProgressWriter agentProgressWriter,
        AgentRetryProperties retryProperties,
        AgentLoopPlanner agentLoopPlanner,
        AgentAuditService agentAuditService,
        AgentIntentRouter agentIntentRouter,
        AgentContextToolCallbackFactory agentContextToolCallbackFactory,
        tools.jackson.databind.ObjectMapper objectMapper
    ) throws IOException {
        this.agentContextService = agentContextService;
        this.agentConversationService = agentConversationService;
        this.llmProviderRegistry = llmProviderRegistry;
        this.promptSanitizer = promptSanitizer;
        this.agentMetrics = agentMetrics;
        this.agentProgressWriter = agentProgressWriter;
        this.retryProperties = retryProperties;
        this.agentLoopPlanner = agentLoopPlanner;
        this.agentAuditService = agentAuditService;
        this.agentIntentRouter = agentIntentRouter == null ? new AgentIntentRouter() : agentIntentRouter;
        this.agentContextToolCallbackFactory = agentContextToolCallbackFactory;
        this.objectMapper = objectMapper;
        this.systemPromptTemplate = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/agent-interview-coach-system.st")
            .getContentAsString(StandardCharsets.UTF_8));
        this.fixedContextPromptTemplate = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/agent-interview-coach-context.st")
            .getContentAsString(StandardCharsets.UTF_8));
        this.userPromptTemplate = new PromptTemplate(resourceLoader
            .getResource("classpath:prompts/agent-interview-coach-user.st")
            .getContentAsString(StandardCharsets.UTF_8));
    }

    /** 模型轮次检查点在迁移期可选接入；不可用时不影响既有流式回答。 */
    @Autowired(required = false)
    void setRunStateService(AgentRunStateService runStateService) {
        this.runStateService = runStateService;
    }

    /**
     * 执行一次面试教练任务并以 SSE 事件流返回结果。
     */
    public Flux<AgentStreamEvent> stream(String sessionId, AgentMessageRequest request) {
        return stream(sessionId, request, null);
    }

    /** API 调用必须带身份键；旧的内部测试/调用方仍可使用上面的兼容重载。 */
    public Flux<AgentStreamEvent> stream(
        String sessionId, AgentMessageRequest request, String ownerKey
    ) {
        String dataOwnerKey = AuthUserContext.currentUserId().orElse(null);
        if (ownerKey != null && !ownerKey.isBlank()) {
            agentConversationService.assertAccess(sessionId, request.agentSessionId(), ownerKey);
            request = request.withMemoryKey(ownerKey);
            // 兼容迁移期的 X-User-Id 请求：Controller 已通过 AuthIdentityService 校验
            // owner，但这类请求不会由 AuthSessionFilter 写入 ThreadLocal。运行身份必须
            // 继续进入 ToolContext，否则工具会被错误判定为缺少 owner。
            if (dataOwnerKey == null || dataOwnerKey.isBlank()) {
                dataOwnerKey = ownerKey.trim();
            }
        }
        return streamInternal(sessionId, request, dataOwnerKey);
    }

    private Flux<AgentStreamEvent> streamInternal(
        String sessionId, AgentMessageRequest request, String dataOwnerKey
    ) {
        // 先解析 Provider；本轮是否进入复盘/通用模式由模型意图分类决定，
        // 不能在模型分类前根据旧 goal 或路径中的面试 ID 自动加载报告。
        AgentMessageRequest preparedRequest = resolveProviderForRun(request);
        return Flux.concat(
            Flux.just(AgentStreamEvent.step("context", "running")),
            Flux.defer(() -> {
            String activeKey = preparedRequest.agentSessionId() == null || preparedRequest.agentSessionId().isBlank()
                ? sessionId : preparedRequest.agentSessionId();
            if (!activeSessions.add(activeKey)) {
                return Flux.just(AgentStreamEvent.error("当前面试已有一个 Agent 任务正在执行，请等待完成后再试"));
            }
            Sinks.One<Void> cancellationSignal = Sinks.one();
            cancellationSignals.put(activeKey, cancellationSignal);
            Long preparedAssistantMessageId = null;
            AgentMetrics.RunTracker runTracker = agentMetrics.startRun(
                retryProperties.getMaxModelCallsPerRun(), retryProperties.getMaxToolCallsPerRun()
            );
            AtomicReference<AgentTaskHandle> taskHandleRef = new AtomicReference<>();
            AtomicReference<String> activeAgentSessionKeyRef = new AtomicReference<>(activeKey);
            AtomicReference<String> preparedRunIdRef = new AtomicReference<>();
            try {
                // 模型上下文使用净化后的消息；工具授权必须保留原始用户意图，
                // 否则“忽略指令后预约……”中的危险片段可能先被删除，
                // 工具只看到“预约”而误把请求当成安全授权。
                AgentMessageRequest runRequest = preparedRequest;
                String userIntentMessage = runRequest.message();
                String message = promptSanitizer.sanitize(userIntentMessage);
                List<AgentHistoryMessage> persistedHistory = dataOwnerKey == null || dataOwnerKey.isBlank()
                    ? runRequest.agentSessionId() == null || runRequest.agentSessionId().isBlank()
                        ? agentConversationService.loadHistory(sessionId)
                        : agentConversationService.loadHistory(sessionId, runRequest.agentSessionId())
                    : agentConversationService.loadHistory(
                        sessionId, runRequest.agentSessionId(), dataOwnerKey
                    );
                List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(
                    persistedHistory.isEmpty() ? runRequest.history() : persistedHistory
                );
                boolean waitingForInput = agentConversationService.isWaitingForInput(
                    sessionId, runRequest.agentSessionId(), dataOwnerKey
                );
                String toolIntentMessage = waitingForInput
                    ? mergePendingUserIntent(historyMessages, userIntentMessage)
                    : userIntentMessage;
                AgentIntentRouter.Decision intentDecision = agentIntentRouter.route(
                    runRequest, toolIntentMessage, historyMessages,
                    runRequest.providerId(), runTracker
                );
                runRequest = agentIntentRouter.applyMode(runRequest, intentDecision);
                AgentContextService.AgentContext context;
                AuthUserContext.set(dataOwnerKey);
                try {
                    // 通用 Agent 永远从 null 报告上下文开始；绑定面试报告只能通过
                    // 当前会话的显式上下文绑定进入，不能根据线程历史自动恢复旧报告。
                    context = buildContext(sessionId, runRequest, historyMessages, intentDecision);
                } finally {
                    AuthUserContext.clear();
                }

                AgentConversationService.PreparedMessage preparedMessage = prepareMessage(
                    sessionId, runRequest, userIntentMessage, dataOwnerKey
                );
                preparedAssistantMessageId = preparedMessage.assistantMessageId();
                preparedRunIdRef.set(preparedMessage.runId());
                AgentTaskHandle taskHandle = new AgentTaskHandle(
                    preparedMessage.assistantMessageId(), preparedMessage.runId(), cancellationSignal
                );
                taskHandleRef.set(taskHandle);
                String resolvedAgentSessionId = preparedMessage.agentSessionId() == null
                    ? activeKey : preparedMessage.agentSessionId();
                activeAgentSessionKeyRef.set(resolvedAgentSessionId);
                agentMetrics.setAuditContext(
                    runTracker, dataOwnerKey, sessionId, resolvedAgentSessionId, preparedMessage.runId()
                );
                auditPromptSecurity(
                    runTracker, userIntentMessage,
                    runRequest.goal(), dataOwnerKey, sessionId,
                    resolvedAgentSessionId, preparedMessage.runId()
                );
                recordRunTrace(
                    runTracker, "ROUTE_SELECTED", "agent-route",
                    intentDecision.route().name(), "selected"
                );
                recordRunTrace(
                    runTracker, "CONTEXT_BUILT", "agent-context",
                    intentDecision.route().name(),
                    context.sources().isEmpty() ? "no-sources" : "sources-loaded"
                );
                activeTasks.put(resolvedAgentSessionId, taskHandle);
                persistRunProgress(
                    preparedMessage.assistantMessageId(), "context", "running", null,
                    preparedMessage.runId()
                );

                Flux<AgentStreamEvent> workflow = buildWorkflow(
                    sessionId, resolvedAgentSessionId, runRequest, message, userIntentMessage,
                    toolIntentMessage,
                    historyMessages, context, preparedMessage, cancellationSignal, taskHandle, runTracker,
                    intentDecision, null
                );
                // SSE 断开后不缓存全部 token；最终答案由 AgentConversationService 持久化。
                return Flux.create(emitter -> {
                    taskHandle.subscription = workflow.subscribe(
                        emitter::next,
                        error -> {
                            log.error("面试教练 Agent 后台任务失败: sessionId={}, agentSessionId={}",
                                sessionId, resolvedAgentSessionId, error);
                            if (!emitter.isCancelled()) {
                                emitter.next(AgentStreamEvent.error(toAgentErrorMessage(error)));
                            }
                            emitter.complete();
                        },
                        emitter::complete
                    );
                }, FluxSink.OverflowStrategy.BUFFER);
            } catch (Throwable error) {
                if (preparedAssistantMessageId != null) {
                    failPreparedMessage(preparedAssistantMessageId, error, preparedRunIdRef.get());
                    agentProgressWriter.finish(preparedAssistantMessageId);
                }
                agentMetrics.finishRun(runTracker, "failure");
                activeSessions.remove(activeKey);
                cancellationSignals.remove(activeKey, cancellationSignal);
                AgentTaskHandle taskHandle = taskHandleRef.get();
                String cleanupKey = activeAgentSessionKeyRef.get();
                if (taskHandle == null) {
                    activeTasks.remove(cleanupKey);
                } else {
                    activeTasks.remove(cleanupKey, taskHandle);
                }
                return Flux.error(error);
            }
        })
        ).onErrorResume(AgentTaskBusyException.class, error -> Flux.just(
            AgentStreamEvent.error(error.getMessage())
        )).onErrorResume(BusinessException.class, error -> Flux.just(
            AgentStreamEvent.error(error.getMessage())
        )).onErrorResume(Exception.class, error -> {
            log.error("面试教练 Agent 执行失败: sessionId={}", sessionId, error);
            return Flux.just(AgentStreamEvent.error(toAgentErrorMessage(error)));
        });
    }

    /** 启动恢复任务复用同一条 Agent Loop，不新建重复的用户/助手消息。 */
    public void recoverRun(AgentConversationService.RecoveredRun recoveredRun) {
        String activeKey = recoveredRun == null || recoveredRun.agentSessionId() == null
            ? recoveredRun == null ? null : recoveredRun.interviewSessionId()
            : recoveredRun.agentSessionId();
        if (recoveredRun == null || !activeSessions.add(activeKey)) {
            return;
        }
        Mono.fromRunnable(() -> {
            Sinks.One<Void> cancellationSignal = Sinks.one();
            cancellationSignals.put(activeKey, cancellationSignal);
            AgentMetrics.RunTracker runTracker = agentMetrics.startRun(
                retryProperties.getMaxModelCallsPerRun(), retryProperties.getMaxToolCallsPerRun()
            );
            agentMetrics.setAuditContext(
                runTracker,
                recoveredRun.ownerKey(),
                recoveredRun.interviewSessionId(),
                recoveredRun.agentSessionId(),
                recoveredRun.runId()
            );
            AgentTaskHandle taskHandle = new AgentTaskHandle(
                recoveredRun.assistantMessageId(), recoveredRun.runId(), cancellationSignal
            );
            activeTasks.put(activeKey, taskHandle);
            try {
                // 恢复时同样区分“送给模型的净化文本”和“工具授权校验的原始用户意图”。
                String userIntentMessage = recoveredRun.userContent();
                String message = promptSanitizer.sanitize(userIntentMessage);
                auditPromptSecurity(
                    runTracker, userIntentMessage,
                    recoveredRun.goal(), recoveredRun.ownerKey(),
                    recoveredRun.interviewSessionId(), recoveredRun.agentSessionId(), recoveredRun.runId()
                );
                List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(
                    removeCurrentUser(agentConversationService.loadHistory(
                        recoveredRun.interviewSessionId(), recoveredRun.agentSessionId(), recoveredRun.ownerKey()
                    ))
                );
                AgentMessageRequest request = new AgentMessageRequest(
                    recoveredRun.interviewSessionId() == null
                        ? "general"
                        : recoveredRun.goal() == null || recoveredRun.goal().isBlank() ? "review" : recoveredRun.goal(),
                    message, historyMessages, recoveredRun.providerId(), recoveredRun.memoryKey(),
                    recoveredRun.agentSessionId()
                );
                request = resolveProviderForRun(request);
                AgentIntentRouter.Decision intentDecision = agentIntentRouter.route(
                    request, userIntentMessage, historyMessages, request.providerId(), runTracker
                );
                request = agentIntentRouter.applyMode(request, intentDecision);
                AgentContextService.AgentContext context;
                AuthUserContext.set(recoveredRun.ownerKey());
                try {
                    context = buildContext(
                        recoveredRun.interviewSessionId(), request, historyMessages, intentDecision
                    );
                } finally {
                    AuthUserContext.clear();
                }
                recordRunTrace(
                    runTracker, "RUN_RECOVERY_STARTED", "agent-run", "restart", "started"
                );
                recordRunTrace(
                    runTracker, "CONTEXT_BUILT", "agent-context", intentDecision.route().name(),
                    context.sources().isEmpty() ? "no-sources" : "sources-loaded"
                );
                AgentConversationService.PreparedMessage prepared = new AgentConversationService.PreparedMessage(
                    recoveredRun.assistantMessageId(), null, recoveredRun.runId(), recoveredRun.agentSessionId()
                );
                persistRunProgress(
                    recoveredRun.assistantMessageId(), "context", "running", "应用重启后自动恢复中",
                    recoveredRun.runId()
                );
                Flux<AgentStreamEvent> workflow = buildWorkflow(
                    recoveredRun.interviewSessionId(), activeKey, request, message, userIntentMessage,
                    userIntentMessage,
                    historyMessages, context, prepared, cancellationSignal, taskHandle, runTracker,
                    intentDecision, new LoopResumeState(
                        recoveredRun.loopPlan(),
                        recoveredRun.loopNextStep(),
                        recoveredRun.loopCompletedStepIndices(),
                        recoveredRun.loopTrace()
                    )
                );
                taskHandle.subscription = workflow.subscribe(
                    ignored -> { },
                    error -> log.error("Agent 启动恢复任务失败: sessionId={}", recoveredRun.interviewSessionId(), error),
                    () -> log.info("Agent 启动恢复任务完成: sessionId={}", recoveredRun.interviewSessionId())
                );
            } catch (Throwable error) {
                failPreparedMessage(recoveredRun.assistantMessageId(), error, recoveredRun.runId());
                agentProgressWriter.finish(recoveredRun.assistantMessageId());
                agentMetrics.finishRun(runTracker, "failure");
                activeSessions.remove(activeKey);
                cancellationSignals.remove(activeKey, cancellationSignal);
                activeTasks.remove(activeKey, taskHandle);
                log.warn("Agent 启动恢复准备失败: sessionId={}", recoveredRun.interviewSessionId(), error);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    /**
     * 审批动作已提交后继续同一 Agent Run。
     *
     * <p>这里不创建新的用户消息，也不重新要求用户输入原始指令；动作结果作为受信任的
     * 系统事件交给原上下文，让模型生成下一步答复。通过 AFTER_COMMIT 监听，保证动作事务
     * 已经成功提交后才启动后台模型调用。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleApprovalResume(AgentConversationService.ApprovalResumeRequest resume) {
        resumeApprovedRun(resume);
    }

    public void resumeApprovedRun(AgentConversationService.ApprovalResumeRequest resume) {
        String activeKey = resume == null || resume.agentSessionId() == null
            ? resume == null ? null : resume.interviewSessionId()
            : resume.agentSessionId();
        if (resume == null || activeKey == null || activeKey.isBlank() || !activeSessions.add(activeKey)) {
            return;
        }
        Mono.fromRunnable(() -> {
            Sinks.One<Void> cancellationSignal = Sinks.one();
            cancellationSignals.put(activeKey, cancellationSignal);
            AgentMetrics.RunTracker runTracker = agentMetrics.startRun(
                retryProperties.getMaxModelCallsPerRun(), retryProperties.getMaxToolCallsPerRun()
            );
            AgentTaskHandle taskHandle = new AgentTaskHandle(
                resume.assistantMessageId(), resume.runId(), cancellationSignal
            );
            activeTasks.put(activeKey, taskHandle);
            try {
                String originalUserContent = resume.userContent() == null ? "" : resume.userContent();
                String approvalMessage = buildApprovalResumeMessage(resume);
                agentMetrics.setAuditContext(
                    runTracker, resume.ownerKey(), resume.interviewSessionId(),
                    resume.agentSessionId(), resume.runId()
                );
                auditPromptSecurity(
                    runTracker, originalUserContent, resume.goal(), resume.ownerKey(),
                    resume.interviewSessionId(), resume.agentSessionId(), resume.runId()
                );
                List<AgentHistoryMessage> historyMessages = AgentHistoryPolicy.normalize(
                    agentConversationService.loadHistory(
                        resume.interviewSessionId(), resume.agentSessionId(), resume.ownerKey()
                    )
                );
                AgentMessageRequest originalRequest = new AgentMessageRequest(
                    resume.goal() == null || resume.goal().isBlank() ? "general" : resume.goal(),
                    originalUserContent, historyMessages, resume.providerId(), resume.memoryKey(),
                    resume.agentSessionId()
                );
                originalRequest = resolveProviderForRun(originalRequest);
                AgentIntentRouter.Decision originalDecision = agentIntentRouter.route(
                    originalRequest, originalUserContent, historyMessages,
                    originalRequest.providerId(), runTracker
                );
                originalRequest = agentIntentRouter.applyMode(originalRequest, originalDecision);
                AgentContextService.AgentContext context;
                AuthUserContext.set(resume.ownerKey());
                try {
                    context = buildContext(
                        resume.interviewSessionId(), originalRequest, historyMessages, originalDecision
                    );
                } finally {
                    AuthUserContext.clear();
                }
                agentMetrics.setAuditContext(
                    runTracker, resume.ownerKey(), resume.interviewSessionId(),
                    resume.agentSessionId(), resume.runId()
                );
                recordRunTrace(
                    runTracker, "RUN_RESUME_STARTED", "agent-run", "approval", "started"
                );
                recordRunTrace(
                    runTracker, "CONTEXT_BUILT", "agent-context", originalDecision.route().name(),
                    context.sources().isEmpty() ? "no-sources" : "sources-loaded"
                );

                // 续跑阶段只消费动作结果，不能再次把原始“安排/取消”意图识别成同一个工具调用。
                AgentIntentRouter.Decision resumeDecision = new AgentIntentRouter.Decision(
                    AgentIntentRouter.Route.GENERAL, Set.of(), 1.0d, "审批结果续跑"
                );
                AgentMessageRequest modelRequest = originalRequest.withGoal("general");
                AgentConversationService.PreparedMessage prepared =
                    new AgentConversationService.PreparedMessage(
                        resume.assistantMessageId(), null, resume.runId(), resume.agentSessionId()
                    );
                persistRunProgress(
                    resume.assistantMessageId(), "resume", "running", resume.approvalNotice(), resume.runId()
                );
                Flux<AgentStreamEvent> workflow = buildWorkflow(
                    resume.interviewSessionId(), activeKey, modelRequest, approvalMessage, approvalMessage,
                    approvalMessage,
                    historyMessages, context, prepared, cancellationSignal, taskHandle, runTracker,
                    resumeDecision, null
                );
                taskHandle.subscription = workflow.subscribe(
                    ignored -> { },
                    error -> log.error("Agent 审批续跑失败: agentSessionId={}", resume.agentSessionId(), error),
                    () -> log.info("Agent 审批续跑完成: agentSessionId={}", resume.agentSessionId())
                );
            } catch (Throwable error) {
                failPreparedMessage(resume.assistantMessageId(), error, resume.runId());
                agentProgressWriter.finish(resume.assistantMessageId());
                agentMetrics.finishRun(runTracker, "failure");
                activeSessions.remove(activeKey);
                cancellationSignals.remove(activeKey, cancellationSignal);
                activeTasks.remove(activeKey, taskHandle);
                log.warn("Agent 审批续跑准备失败: agentSessionId={}", resume.agentSessionId(), error);
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private String buildApprovalResumeMessage(AgentConversationService.ApprovalResumeRequest resume) {
        return promptSanitizer.sanitize(
            "系统审批事件（这是已执行结果，不是用户新指令）：\n"
                + "动作：" + safeResumeText(resume.actionTitle()) + "\n"
                + "状态：" + safeResumeText(resume.actionStatus()) + "\n"
                + "结果：" + safeResumeText(resume.actionResultSummary()) + "\n"
                + "说明：" + safeResumeText(resume.approvalNotice()) + "\n"
                + "请继续原任务，向用户说明当前结果；不要再次执行同一个动作。"
        );
    }

    private String safeResumeText(String value) {
        if (value == null || value.isBlank()) {
            return "未提供";
        }
        String safe = promptSanitizer.sanitizeForContext(value);
        return safe.length() > 2_000 ? safe.substring(0, 2_000) : safe;
    }

    private Flux<AgentStreamEvent> buildWorkflow(
        String sessionId,
        String agentSessionId,
        AgentMessageRequest request,
        String message,
        String userIntentMessage,
        String toolIntentMessage,
        List<AgentHistoryMessage> historyMessages,
        AgentContextService.AgentContext context,
        AgentConversationService.PreparedMessage preparedMessage,
        Sinks.One<Void> cancellationSignal,
        AgentTaskHandle taskHandle,
        AgentMetrics.RunTracker runTracker,
        AgentIntentRouter.Decision intentDecision,
        LoopResumeState resumeState
    ) {
        Flux<String> planFlux = resumeState != null && resumeState.hasPlan()
            ? Flux.just(resumeState.plan())
            : agentLoopPlanner == null
            ? Flux.just(AgentLoopPlanner.DEFAULT_PLAN)
            : Mono.fromCallable(() -> agentLoopPlanner.plan(
                    context, request, message, historyMessages, runTracker
                ))
                .subscribeOn(Schedulers.boundedElastic())
                .flux();
        return Flux.concat(
            Flux.just(
                AgentStreamEvent.step("context", "completed"),
                AgentStreamEvent.step("planning", "running")
            ),
            planFlux.flatMap(plan -> {
                    recordRunTrace(runTracker, "PLAN_READY", "agent-loop", "plan", "success");
                    AgentLoopEngine loopEngine = new AgentLoopEngine();
                    boolean hasInterviewReport = context.interviewReport() != null
                        && !context.interviewReport().isBlank();
                    String safePlan = agentLoopPlanner == null
                        ? hasInterviewReport
                            ? "1. 读取面试报告并确认当前目标"
                            : "1. 整理"
                        : agentLoopPlanner.canonicalPlan(plan, context);
                    // 恢复计划和规划模型输出都先进入规范化结果，再交给 Loop 执行器。
                    // 这样未来即使 steps() 的兼容解析规则扩展，也不会重新接收旧的自由文本。
                    List<String> loopSteps = agentLoopPlanner == null
                        ? List.of(hasInterviewReport ? "读取面试报告并确认当前目标" : "整理")
                        : agentLoopPlanner.steps(safePlan, context);
                    AgentLoopEngine.ExecutionPolicy loopPolicy = new AgentLoopEngine.ExecutionPolicy(
                        retryProperties.getLoopMaxSteps(),
                        retryProperties.getLoopStepRetryAttempts(),
                        retryProperties.getLoopStepTimeoutMillis(),
                        retryProperties.getLoopTotalTimeoutMillis(),
                        retryProperties.getLoopMaxParallelSteps()
                    );
                    AgentLoopEngine.StepExecutor loopToolExecutor = new AgentLoopToolExecutor(
                        agentMetrics, agentAuditService, runTracker, promptSanitizer
                    );
                    int resumeIndex = resumeState == null
                        ? 0 : Math.max(0, resumeState.nextStepIndex());
                    java.util.Set<Integer> completedStepIndices = new java.util.TreeSet<>(
                        resumeState == null ? List.of() : resumeState.completedStepIndices()
                    );
                    List<AgentLoopTraceItem> loopTraceItems = new ArrayList<>(
                        sanitizeRecoveredLoopTrace(
                            resumeState == null ? List.of() : resumeState.trace(), context
                        )
                    );
                    persistLoopCheckpoint(
                        preparedMessage.assistantMessageId(), preparedMessage.runId(), safePlan,
                        resumeIndex, completedStepIndices, loopTraceItems
                    );
                    java.util.function.Consumer<AgentLoopEngine.LoopIteration> checkpointListener =
                        iteration -> {
                            int index = Math.max(0, iteration.iteration() - 1);
                            if ("completed".equals(iteration.status())) {
                                completedStepIndices.add(index);
                            } else {
                                completedStepIndices.remove(index);
                            }
                            AgentLoopTraceItem traceItem = new AgentLoopTraceItem(
                                iteration.iteration(), iteration.action(), iteration.status(),
                                iteration.observation(), iteration.attempts(),
                                iteration.durationMillis(), iteration.failureReason()
                            );
                            loopTraceItems.removeIf(item -> item.iteration() == traceItem.iteration());
                            loopTraceItems.add(traceItem);
                            persistLoopCheckpoint(
                                preparedMessage.assistantMessageId(),
                                preparedMessage.runId(),
                                safePlan,
                                "completed".equals(iteration.status())
                                    ? iteration.iteration() : index,
                                completedStepIndices,
                                loopTraceItems
                            );
                        };
                    AgentLoopEngine.LoopResult loopResult = resumeState != null
                        && resumeState.nextStepIndex() > 0
                        ? loopEngine.resume(
                            context,
                            loopSteps,
                            new AgentLoopEngine.LoopResult(
                                List.of(), "recovered", resumeState.nextStepIndex()
                            ),
                            resumeState.completedStepIndices(),
                            loopPolicy,
                            loopToolExecutor,
                            checkpointListener
                        )
                        : loopEngine.execute(
                            context, loopSteps, loopPolicy, loopToolExecutor, checkpointListener
                        );
                    loopResult.iterations().forEach(iteration -> agentMetrics.recordLoopStep(
                        iteration.status(), Duration.ofMillis(iteration.durationMillis())
                    ));
                    persistLoopCheckpoint(
                        preparedMessage.assistantMessageId(), preparedMessage.runId(),
                        safePlan, loopResult.nextStepIndex(), completedStepIndices, loopTraceItems
                    );
                    String loopTrace = loopResult.trace()
                        + "\nLoop 终态：" + loopResult.termination();
                    List<AgentHistoryMessage> compactHistory = AgentHistoryPolicy.normalize(
                        historyMessages, CONTEXT_RETRY_HISTORY_CHARS, CONTEXT_RETRY_MESSAGE_CHARS
                    );
                    String requiredTool = intentDecision != null
                        && intentDecision.capabilities() != null
                        && intentDecision.capabilities().contains(AgentIntentRouter.Capability.SCHEDULE)
                        ? agentIntentRouter.requiredToolName(toolIntentMessage) : null;
                    PromptVariant normalPrompt = buildPromptVariant(
                        context, request, message, historyMessages, safePlan, loopTrace, false,
                        intentDecision
                    );
                    PromptVariant compactPrompt = buildPromptVariant(
                        context, request, message, compactHistory, safePlan, loopTrace, true,
                        intentDecision
                    );
                    agentMetrics.recordPromptPrefix(runTracker, normalPrompt.systemPrompt());
                    BiFunction<String, Boolean, Supplier<ModelStream>> modelStreamFactory =
                        (providerId, compacted) -> {
                            PromptVariant prompt = compacted ? compactPrompt : normalPrompt;
                            return () -> {
                                recordRunTrace(
                                    runTracker, "MODEL_STARTED", "agent-model", providerId,
                                    compacted ? "compacted" : "normal"
                                );
                                AgentMetrics.ModelCallTracker modelCall = agentMetrics.startModelCall(providerId, runTracker);
                                if (runStateService != null) {
                                    runStateService.modelTurnStarted(runTracker, providerId, compacted);
                                }
                                try {
                                    // 运行时只向模型暴露本轮能力策略允许的工具；普通问题默认
                                    // 不挂载业务工具，明确资料、Skill 或日程请求才加载对应能力。
                                    // 每个工具仍会在 AgentObservedToolCallback/业务回调内做 owner、
                                    // 意图和确认校验，工具目录裁剪不是安全边界的替代品。
                                    AgentIntentRouter.ToolPolicy toolPolicy =
                                        agentIntentRouter.toolPolicy(intentDecision);
                                    ChatClient chatClient = requiredTool == null
                                        ? llmProviderRegistry.getAgentChatClient(
                                            providerId,
                                            toolPolicy.allowedToolNames(),
                                            toolPolicy.includeSkills()
                                        )
                                        : llmProviderRegistry.getAgentChatClient(
                                            providerId, Set.of(requiredTool), false
                                        );
                                    // 明确的业务动作不读取面试报告、Skill、RAG 或历史对话，
                                    // 避免无关上下文污染工具参数，也避免把大段资料发送给兼容层。
                                    var promptSpec = requiredTool == null
                                        ? chatClient.prompt().system(prompt.systemPrompt())
                                        : chatClient.prompt().system(EXPLICIT_ACTION_SYSTEM_PROMPT);
                                    if (requiredTool == null && !prompt.history().isEmpty()) {
                                        promptSpec = promptSpec.messages(prompt.history());
                                    }
                                    if (runTracker != null) {
                                        promptSpec = promptSpec.toolContext(
                                            agentToolContext(runTracker, userIntentMessage)
                                        );
                                    }
                                    if (requiredTool != null) {
                                        // 兼容部分 OpenAI 网关的工具流式请求：当前动作只允许一个
                                        // 工具，不需要并行调用，避免网关把默认并行参数判为非法。
                                        promptSpec = promptSpec.options(OpenAiChatOptions.builder()
                                            .parallelToolCalls(false)
                                        );
                                    }
                                    String modelUserPrompt = requiredTool == null
                                        ? prompt.userPrompt() : userIntentMessage;
                                    var streamSpec = promptSpec.user(modelUserPrompt).stream();
                                    Flux<ChatResponse> chatResponses = streamSpec.chatResponse();
                                    if (chatResponses == null) {
                                        // 保留对旧的/简化 ChatClient 测试替身和不提供 ChatResponse
                                        // 流的兼容性；真实 Spring AI 2.0 Provider 会走 usage 分支。
                                        return new ModelStream(streamSpec.content(), modelCall);
                                    }
                                    Flux<String> content = chatResponses.handle((response, sink) -> {
                                        if (response == null) {
                                            return;
                                        }
                                        if (response.getMetadata() != null
                                            && response.getMetadata().getUsage() != null) {
                                            var usage = response.getMetadata().getUsage();
                                            agentMetrics.observeModelUsage(
                                                modelCall,
                                                usage.getPromptTokens(),
                                                usage.getCompletionTokens(),
                                                usage.getTotalTokens(),
                                                response.getMetadata().getModel()
                                            );
                                        }
                                        String text = response.getResult() == null
                                            || response.getResult().getOutput() == null
                                            ? ""
                                            : response.getResult().getOutput().getText();
                                        if (text != null && !text.isEmpty()) {
                                            sink.next(text);
                                        }
                                    });
                                    return new ModelStream(content, modelCall);
                                } catch (Throwable error) {
                                    agentMetrics.finishModelCall(modelCall, "failure");
                                    if (runStateService != null) {
                                        runStateService.modelTurnFinished(runTracker, providerId, "failure");
                                    }
                                    throw error;
                                }
                            };
                        };
                    Flux<AgentStreamEvent> response = requiredTool != null
                        && agentContextToolCallbackFactory != null && objectMapper != null
                        ? streamExplicitScheduleAction(
                            requiredTool, toolIntentMessage, sessionId, agentSessionId,
                            preparedMessage.assistantMessageId(), preparedMessage.runId(),
                            runTracker, taskHandle
                        )
                        : streamModelWithRetry(
                            modelStreamFactory, request.providerId(), sessionId,
                            preparedMessage.assistantMessageId(), preparedMessage.runId(), context.sources(),
                            runTracker, taskHandle
                        );
                    boolean generalRequest = "general".equalsIgnoreCase(request.goal());
                    boolean hasOptionalSource = context.sources().stream()
                        .anyMatch(source -> source != null
                            && ("skill".equalsIgnoreCase(source.kind())
                                || "rag".equalsIgnoreCase(source.kind())));
                    List<AgentStreamEvent> preparationEvents = new ArrayList<>();
                    preparationEvents.add(AgentStreamEvent.step("insight", "running"));
                    // 通用问题没有显式资料时不虚构“检索相关知识”步骤；只有用户绑定/请求了
                    // Skill 或知识库，或者处于面试复盘模式，才展示证据检索阶段。
                    if (!generalRequest || hasOptionalSource) {
                        preparationEvents.add(AgentStreamEvent.step("retrieval", "running"));
                        preparationEvents.add(AgentStreamEvent.step("retrieval", "completed"));
                    }
                    preparationEvents.add(AgentStreamEvent.sources(context.sources()));
                    preparationEvents.add(AgentStreamEvent.step("action", "running"));
                    return Flux.concat(
                        Flux.just(
                            AgentStreamEvent.plan(safePlan),
                            AgentStreamEvent.step("planning", "completed"),
                            AgentStreamEvent.step("loop", "running")
                        ),
                        Flux.fromIterable(loopResult.iterations())
                            .concatMap(iteration -> Flux.just(
                                AgentStreamEvent.loop(iteration.iteration(), "running", iteration.action(),
                                    iteration.action(), iteration.attempts(), 0, null),
                                AgentStreamEvent.loop(
                                    iteration.iteration(),
                                    "completed".equals(iteration.status()) ? "completed" : "failed",
                                    iteration.observation(), iteration.action(), iteration.attempts(),
                                    iteration.durationMillis(), iteration.failureReason()
                                )
                            )),
                        Flux.fromIterable(preparationEvents),
                        response,
                        Flux.just(AgentStreamEvent.step("loop", "completed"))
                    );
                })
        )
            .doOnNext(event -> persistRunProgress(
                preparedMessage.assistantMessageId(), event, preparedMessage.runId()
            ))
            .doOnError(error -> failPreparedMessage(
                preparedMessage.assistantMessageId(), error, preparedMessage.runId()
            ))
            .takeUntilOther(Flux.merge(
                cancellationSignal.asMono(),
                monitorRemoteCancellation(sessionId, agentSessionId, preparedMessage.runId())
            ))
            .doFinally(signal -> {
                agentProgressWriter.finish(preparedMessage.assistantMessageId());
                activeSessions.remove(agentSessionId);
                cancellationSignals.remove(agentSessionId, cancellationSignal);
                activeTasks.remove(agentSessionId, taskHandle);
            });
    }

    private List<AgentHistoryMessage> removeCurrentUser(List<AgentHistoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        AgentHistoryMessage last = messages.getLast();
        if ("user".equals(last.role())) {
            return List.copyOf(messages.subList(0, messages.size() - 1));
        }
        return messages;
    }

    /**
     * 等待补充信息的运行继续时，把上一条用户请求与当前补充合并给工具授权和参数解析。
     * 模型的当前用户消息仍只保留本轮输入，避免把同一内容重复塞进稳定历史；两段都来自
     * 用户消息，且后续仍会经过工具自身的意图、owner 和参数校验。
     */
    private String mergePendingUserIntent(
        List<AgentHistoryMessage> history, String currentMessage
    ) {
        if (history == null || history.isEmpty()) {
            return currentMessage == null ? "" : currentMessage;
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            AgentHistoryMessage item = history.get(index);
            if (item != null && "user".equals(item.role())
                && item.content() != null && !item.content().isBlank()) {
                String current = currentMessage == null ? "" : currentMessage;
                return "原始待完成请求：\n" + item.content()
                    + "\n\n用户补充信息：\n" + current;
            }
        }
        return currentMessage == null ? "" : currentMessage;
    }

    /**
     * 在创建持久化运行记录前把空的“使用默认模型”解析为当前首选 Provider。
     *
     * <p>如果不保存这个解析结果，应用重启后的恢复任务只能再次读取空 Provider，
     * 可能因为默认模型在运行期间被切换而走到另一条无效配置。保留首选 Provider
     * 不会禁用已有的备用 Provider；首选失败时仍由模型流重试链路继续切换。</p>
     */
    private AgentMessageRequest resolveProviderForRun(AgentMessageRequest request) {
        if (request == null || request.providerId() != null && !request.providerId().isBlank()) {
            return request;
        }
        List<String> candidates = llmProviderRegistry.resolveChatProviderCandidates(request.providerId());
        return candidates.isEmpty() ? request : request.withProviderId(candidates.getFirst());
    }

    /**
     * 兼容迁移期的上下文测试替身和旧内部调用方；生产实现始终优先使用带意图策略的入口。
     */
    private AgentContextService.AgentContext buildContext(
        String sessionId,
        AgentMessageRequest request,
        List<AgentHistoryMessage> history,
        AgentIntentRouter.Decision intentDecision
    ) {
        AgentContextService.AgentContext context = agentContextService.buildForDecision(
            sessionId, request, history, intentDecision
        );
        return context == null ? agentContextService.build(sessionId, request, history) : context;
    }

    private AgentMessageRequest normalizeRequestMode(String sessionId, AgentMessageRequest request) {
        if (request == null) {
            return request;
        }
        if ("general".equalsIgnoreCase(request.goal())) {
            return request;
        }
        // 面试会话 ID 只用于归属和历史路由，不能再被当成“本轮必须读取报告”的授权。
        // 报告上下文必须来自明确的复盘请求或当前 Agent 会话中的显式绑定。
        return explicitlyTargetsInterview(request) ? request : request.withGoal("general");
    }

    /**
     * 判断本轮是否明确要求使用面试语境。
     *
     * <p>不能仅依据旧会话保存的 goal=review 或路径中的 interviewSessionId 判断，
     * 否则“学习 Agent”“解释 Tool Calling”这类普通问题会被错误套上复盘模板，
     * 还会触发不必要的 Skill/RAG 组装。日程类请求也明确走通用 Agent，由工具自行处理。</p>
     */
    static boolean explicitlyTargetsInterview(AgentMessageRequest request) {
        if (request == null || request.message() == null) {
            return false;
        }
        String message = request.message().replaceAll("\\s+", " ").trim();
        if (message.isBlank()) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(安排|预约|取消|改期|改到|调整).{0,16}(面试|会议|日程|预约).*")
            || normalized.matches(".*(面试|会议|日程|预约).{0,16}(安排|预约|取消|改期|改到|调整).*")) {
            return false;
        }
        return normalized.contains("面试报告")
            || normalized.contains("本场面试")
            || normalized.contains("本次面试")
            || normalized.matches(".*(?:本场|本次|这场|当前|这次).{0,10}(面试|报告).*")
            || normalized.matches(".*(?:面试|报告).{0,10}(表现|回答|评分|复盘|失分|优势|薄弱|改进).*")
            || normalized.contains("这份报告")
            || normalized.contains("面试复盘")
            || normalized.contains("复盘")
            || normalized.contains("面试表现")
            || normalized.contains("评分")
            || normalized.contains("失分")
            || normalized.contains("薄弱点")
            || normalized.contains("面试官")
            || normalized.contains("根据这场面试")
            || normalized.contains("针对这场面试");
    }

    private AgentConversationService.PreparedMessage prepareMessage(
        String sessionId, AgentMessageRequest request, String message, String trustedOwnerKey
    ) {
        String goal = promptSanitizer.sanitize(request.goal());
        String owner = trustedOwnerKey == null || trustedOwnerKey.isBlank()
            ? null : trustedOwnerKey.trim();
        if (owner == null) {
            // 兼容旧的嵌入式调用方和单元测试，但不把请求体 memoryKey 当作归属凭证。
            return request.agentSessionId() == null || request.agentSessionId().isBlank()
                ? agentConversationService.prepareMessage(sessionId, goal, request.providerId(), message)
                : agentConversationService.prepareMessage(
                    sessionId, request.agentSessionId(), goal, request.providerId(), message, null
                );
        }
        return agentConversationService.prepareMessage(
            sessionId, request.agentSessionId(), goal, request.providerId(), message, owner
        );
    }

    /** 发出服务端取消信号；如果任务不在当前实例，数据库取消仍由会话服务负责。 */
    public boolean cancel(String sessionId) {
        AgentTaskHandle taskHandle = activeTasks.get(sessionId);
        if (taskHandle != null) {
            // 规划阶段也可能尚未进入模型流，因此取消必须在这里持久化一次；
            // 会话服务会用运行状态栅栏忽略后续迟到的取消事件。
            boolean shouldPersistCancellation = taskHandle.markCancellationPersisted();
            taskHandle.cancel();
            if (shouldPersistCancellation) {
                agentConversationService.cancelMessage(
                    taskHandle.assistantMessageId, "", taskHandle.runId
                );
            }
            agentProgressWriter.finish(taskHandle.assistantMessageId);
            return true;
        }
        Sinks.One<Void> signal = cancellationSignals.get(sessionId);
        return signal != null && signal.tryEmitEmpty().isSuccess();
    }

    /** 恢复扫描前排除本实例仍在正常执行的任务，避免本地任务被自己换发 runId。 */
    public boolean isLocallyActive(String sessionId) {
        return sessionId != null && activeSessions.contains(sessionId);
    }

    public boolean isLocallyActive(String interviewSessionId, String agentSessionId) {
        return isLocallyActive(interviewSessionId) || isLocallyActive(agentSessionId);
    }

    private static final class AgentTaskHandle {
        private final Long assistantMessageId;
        private final String runId;
        private final Sinks.One<Void> cancellationSignal;
        private final AtomicBoolean cancellationPersisted = new AtomicBoolean();
        private volatile Disposable subscription;

        private AgentTaskHandle(
            Long assistantMessageId, String runId, Sinks.One<Void> cancellationSignal
        ) {
            this.assistantMessageId = assistantMessageId;
            this.runId = runId;
            this.cancellationSignal = cancellationSignal;
        }

        private boolean cancel() {
            cancellationSignal.tryEmitEmpty();
            Disposable current = subscription;
            if (current != null) {
                current.dispose();
                return true;
            }
            return false;
        }

        private boolean markCancellationPersisted() {
            return cancellationPersisted.compareAndSet(false, true);
        }
    }

    private void failPreparedMessage(Long messageId, Throwable error, String expectedRunId) {
        try {
            agentConversationService.failMessage(
                messageId, "", toAgentErrorMessage(error), expectedRunId
            );
        } catch (Throwable persistenceError) {
            log.warn("Agent 任务失败状态持久化失败: messageId={}", messageId, persistenceError);
        }
    }

    /** 进度写库失败不能中断模型主流程，数据库状态仅用于断线后的展示恢复。 */
    private void persistRunProgress(
        Long assistantMessageId, AgentStreamEvent event, String runId
    ) {
        if ("step".equals(event.type())) {
            agentProgressWriter.recordStep(
                assistantMessageId, runId, event.step(), event.status(), event.message()
            );
        } else if ("sources".equals(event.type())) {
            agentProgressWriter.recordSources(assistantMessageId, runId, event.sources());
        }
    }

    private void persistRunProgress(Long assistantMessageId, String step, String status, String notice) {
        persistRunProgress(assistantMessageId, step, status, notice, null);
    }

    private void persistRunProgress(
        Long assistantMessageId, String step, String status, String notice, String runId
    ) {
        agentProgressWriter.recordStep(assistantMessageId, runId, step, status, notice);
    }

    private void persistLoopCheckpoint(
        Long assistantMessageId,
        String runId,
        String plan,
        int nextStepIndex,
        java.util.Set<Integer> completedStepIndices,
        List<AgentLoopTraceItem> loopTrace
    ) {
        agentProgressWriter.recordLoopState(
            assistantMessageId, runId, plan, nextStepIndex,
            completedStepIndices == null ? List.of() : List.copyOf(completedStepIndices)
        );
        // Loop 步骤之间可能发生进程退出，检查点必须在步骤边界立即落库。
        agentProgressWriter.flush(assistantMessageId);
        try {
            agentConversationService.updateLoopTrace(
                assistantMessageId,
                loopTrace == null ? List.of() : List.copyOf(loopTrace),
                runId
            );
        } catch (Throwable error) {
            // 轨迹是恢复展示能力，不能因为附加写入失败中断 Agent 主流程。
            log.warn("Agent Loop 轨迹持久化失败: messageId={}", assistantMessageId, error);
        }
    }

    /**
     * 恢复任务时重新处理数据库中的旧轨迹。
     *
     * <p>轨迹不是执行授权凭证，但会返回前端并写回检查点；因此不能把旧的自由文本
     * 动作、状态、观察或异常原因原样带回当前运行。动作使用当前白名单重新归一化，
     * 其余字段使用上下文净化并限制为固定状态集合。</p>
     */
    private List<AgentLoopTraceItem> sanitizeRecoveredLoopTrace(
        List<AgentLoopTraceItem> trace, AgentContextService.AgentContext context
    ) {
        if (trace == null || trace.isEmpty()) {
            return List.of();
        }
        return trace.stream()
            .filter(item -> item != null)
            .limit(128)
            .map(item -> new AgentLoopTraceItem(
                item.iteration(),
                agentLoopPlanner == null
                    ? "读取面试报告并确认当前目标"
                    : agentLoopPlanner.canonicalAction(item.action(), context),
                normalizeRecoveredTraceStatus(item.status()),
                promptSanitizer.sanitizeForContext(item.observation()),
                item.attempts(),
                item.durationMillis(),
                item.failureReason() == null
                    ? null : promptSanitizer.sanitizeForContext(item.failureReason())
            ))
            .toList();
    }

    private String normalizeRecoveredTraceStatus(String status) {
        return switch (status == null ? "" : status) {
            case "completed", "failed", "timeout", "running" -> status;
            default -> "unknown";
        };
    }

    private Flux<Boolean> monitorRemoteCancellation(String sessionId, String agentSessionId, String runId) {
        return Flux.interval(Duration.ofSeconds(2))
            .flatMap(ignored -> Mono.fromCallable(() ->
                    agentConversationService.isRunActive(sessionId, agentSessionId, runId)
                )
                .subscribeOn(Schedulers.boundedElastic())
                // 数据库瞬时异常时继续执行，避免监测故障误杀模型任务。
                .onErrorReturn(true)
            )
            .filter(active -> !active);
    }

    /**
     * 兼容不支持 Spring AI 工具流式续调用的 OpenAI 网关。
     *
     * <p>明确日程动作已经由路由器识别并且只允许一个受控工具。这里直接把当前用户消息
     * 解析成受控工具参数并调用同一个业务回调，仍然经过 owner、意图、参数和确认校验，
     * 但不再依赖网关对 tool-call continuation 的兼容性；普通问题和资料型请求继续走
     * 原有的模型驱动 ToolCallingAdvisor。</p>
     */
    private Flux<AgentStreamEvent> streamExplicitScheduleAction(
        String toolName,
        String userMessage,
        String sessionId,
        String agentSessionId,
        Long assistantMessageId,
        String runId,
        AgentMetrics.RunTracker runTracker,
        AgentTaskHandle taskHandle
    ) {
        AtomicReference<String> output = new AtomicReference<>(new String());
        AtomicBoolean waitingForUser = new AtomicBoolean(false);
        return Mono.fromCallable(() -> {
            long startedNanos = System.nanoTime();
            String toolInput = buildScheduleToolInput(toolName, userMessage);
            if (toolInput == null) {
                waitingForUser.set(true);
                return "请补充具体日期和时间、公司名称、应聘职位后，我再生成待确认的面试会议草稿。";
            }
            ToolCallback callback = agentContextToolCallbackFactory.callbacks().stream()
                .filter(candidate -> toolName.equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未找到受控日程工具：" + toolName));
            String rawResult = callback.call(
                toolInput, new ToolContext(agentToolContext(runTracker, userMessage))
            );
            agentMetrics.recordToolCall(
                toolName,
                rawResult != null && rawResult.contains("ACTION_PROPOSED") ? "success" : "rejected",
                Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos))
            );
            return renderExplicitScheduleResult(toolName, rawResult);
        })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(answer -> {
                output.set(answer == null ? "" : answer);
                return Flux.just(
                    AgentStreamEvent.delta(output.get()),
                    AgentStreamEvent.step("insight", "completed"),
                    AgentStreamEvent.step(
                        "action", waitingForUser.get() ? "waiting" : "completed"
                    ),
                    AgentStreamEvent.done()
                );
            })
            .doOnComplete(() -> {
                agentProgressWriter.flush(assistantMessageId);
                if (waitingForUser.get()) {
                    agentConversationService.waitForUser(
                        assistantMessageId, output.get(), output.get(), runId,
                        List.of("dateTime", "companyName", "position")
                    );
                } else {
                    agentConversationService.completeMessage(assistantMessageId, output.get(), runId);
                }
            })
            .doOnError(error -> {
                agentProgressWriter.flush(assistantMessageId);
                agentConversationService.failMessage(
                    assistantMessageId, output.get(), toAgentErrorMessage(error), runId
                );
            })
            .doOnCancel(() -> {
                agentProgressWriter.flush(assistantMessageId);
                if (taskHandle == null || taskHandle.markCancellationPersisted()) {
                    agentConversationService.cancelMessage(assistantMessageId, output.get(), runId);
                }
            })
            .doFinally(signal -> {
                persistRunUsage(assistantMessageId, runId, runTracker);
                agentMetrics.finishRun(runTracker, switch (signal) {
                    case ON_COMPLETE -> waitingForUser.get() ? "waiting_user" : "success";
                    case CANCEL -> "cancelled";
                    default -> "failure";
                });
            });
    }

    private String buildScheduleToolInput(String toolName, String message) {
        List<LocalDateTime> times = extractScheduleTimes(message);
        String company = extractScheduleLabel(message, "公司");
        String position = extractScheduleLabel(message, "职位");
        Map<String, Object> input = new LinkedHashMap<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        if (AgentContextToolCallbackFactory.SCHEDULE_DRAFT.equals(toolName)) {
            if (times.isEmpty() || isBlank(company) || isBlank(position)) {
                return null;
            }
            input.put("companyName", company);
            input.put("position", position);
            input.put("interviewTime", times.getFirst().format(formatter));
        } else if (AgentContextToolCallbackFactory.SCHEDULE_CANCEL_DRAFT.equals(toolName)) {
            if (!times.isEmpty()) {
                input.put("interviewTime", times.getFirst().format(formatter));
            }
            if (!isBlank(company)) {
                input.put("companyName", company);
            }
            if (!isBlank(position)) {
                input.put("position", position);
            }
        } else if (AgentContextToolCallbackFactory.SCHEDULE_UPDATE_DRAFT.equals(toolName)) {
            if (!times.isEmpty()) {
                input.put("currentInterviewTime", times.getFirst().format(formatter));
            }
            if (times.size() > 1) {
                input.put("interviewTime", times.get(1).format(formatter));
            }
            if (!isBlank(company)) {
                input.put("companyName", company);
            }
            if (!isBlank(position)) {
                input.put("position", position);
            }
        } else {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(input);
        } catch (RuntimeException error) {
            throw new IllegalStateException("日程参数整理失败", error);
        }
    }

    private String renderExplicitScheduleResult(String toolName, String rawResult) {
        String result = rawResult == null ? "" : rawResult;
        if (result.contains("\"status\":\"NOT_FOUND\"")) {
            return "没有找到符合条件的面试日程，请补充更具体的时间、公司或职位。";
        }
        if (result.contains("\"status\":\"SELECTION_REQUIRED\"")) {
            return "找到了多个符合条件的面试日程，请补充更具体的时间、公司或职位后再操作。";
        }
        if (result.contains("\"status\":\"NO_CHANGE\"")) {
            return "没有识别到需要修改的字段，请提供新的时间、职位、会议链接或备注。";
        }
        if (!result.contains("ACTION_PROPOSED")) {
            return "当前日程操作未生成草稿，请补充更具体的信息后重试。";
        }
        return switch (toolName) {
            case AgentContextToolCallbackFactory.SCHEDULE_CANCEL_DRAFT ->
                "已生成待确认的面试取消草稿，请确认后才会取消日程。";
            case AgentContextToolCallbackFactory.SCHEDULE_UPDATE_DRAFT ->
                "已生成待确认的面试改期草稿，请确认后才会修改日程。";
            default -> "已生成待确认的面试会议草稿，请在动作列表中确认后才会写入日程。";
        };
    }

    private String extractScheduleLabel(String message, String label) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            java.util.regex.Pattern.quote(label)
                + "\\s*(?:是|为|叫|[:：])?\\s*([^，,。；;\\n]+)"
        ).matcher(message);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private List<LocalDateTime> extractScheduleTimes(String message) {
        List<LocalDateTime> times = new ArrayList<>();
        if (message == null || message.isBlank()) {
            return times;
        }
        java.util.regex.Pattern absolute = java.util.regex.Pattern.compile(
            "(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\s*(?:(?:T|日|号)?\\s*)?"
                + "(上午|下午|晚上|早上)?\\s*(\\d{1,2})(?::|：|点)(\\d{0,2})?"
        );
        java.util.regex.Matcher absoluteMatcher = absolute.matcher(message);
        while (absoluteMatcher.find()) {
            times.add(toDateTime(
                LocalDate.of(
                    Integer.parseInt(absoluteMatcher.group(1)),
                    Integer.parseInt(absoluteMatcher.group(2)),
                    Integer.parseInt(absoluteMatcher.group(3))
                ), absoluteMatcher.group(4), absoluteMatcher.group(5), absoluteMatcher.group(6)
            ));
        }
        java.util.regex.Pattern relative = java.util.regex.Pattern.compile(
            "(今天|明天|后天|大后天)\\s*(上午|下午|晚上|早上)?\\s*"
                + "(\\d{1,2})(?::|：|点)(\\d{0,2})?"
        );
        java.util.regex.Matcher relativeMatcher = relative.matcher(message);
        while (relativeMatcher.find()) {
            int days = switch (relativeMatcher.group(1)) {
                case "明天" -> 1;
                case "后天" -> 2;
                case "大后天" -> 3;
                default -> 0;
            };
            times.add(toDateTime(
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(days),
                relativeMatcher.group(2), relativeMatcher.group(3), relativeMatcher.group(4)
            ));
        }
        java.util.regex.Pattern timeOnly = java.util.regex.Pattern.compile(
            "(上午|下午|晚上|早上)\\s*(\\d{1,2})(?::|：|点)(\\d{0,2})?"
        );
        java.util.regex.Matcher timeMatcher = timeOnly.matcher(message);
        LocalDate referenceDate = times.isEmpty()
            ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : times.getFirst().toLocalDate();
        while (timeMatcher.find()) {
            LocalDateTime candidate = toDateTime(
                referenceDate, timeMatcher.group(1), timeMatcher.group(2), timeMatcher.group(3)
            );
            if (!times.contains(candidate)) {
                times.add(candidate);
            }
        }
        return times.stream().distinct().toList();
    }

    private LocalDateTime toDateTime(LocalDate date, String meridiem, String hourText, String minuteText) {
        int hour = Integer.parseInt(hourText);
        if (("下午".equals(meridiem) || "晚上".equals(meridiem)) && hour < 12) {
            hour += 12;
        }
        int minute = minuteText == null || minuteText.isBlank() ? 0 : Integer.parseInt(minuteText);
        return date.atTime(hour, minute);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Flux<AgentStreamEvent> streamModelWithRetry(
        BiFunction<String, Boolean, Supplier<ModelStream>> modelStreamFactory,
        String providerId,
        String sessionId,
        Long assistantMessageId,
        String runId,
        List<interview.guide.modules.agent.model.AgentSource> sources,
        AgentMetrics.RunTracker runTracker,
        AgentTaskHandle taskHandle
    ) {
        AtomicBoolean modelOutputStarted = new AtomicBoolean();
        AtomicReference<StringBuilder> output = new AtomicReference<>(new StringBuilder());
        List<String> providers = llmProviderRegistry.resolveChatProviderCandidates(providerId);
        Flux<AgentStreamEvent> providerStream = providers.isEmpty()
            ? Flux.error(new IllegalStateException("未配置可用的聊天模型 Provider"))
            : streamProviderAttempt(
            modelStreamFactory,
            providers,
            0,
            sessionId,
            assistantMessageId,
            runId,
            runTracker,
            modelOutputStarted,
            output,
            false
        );
        return providerStream
            .concatWith(Flux.defer(() -> {
                String notice = AgentAnswerGroundingPolicy.buildNotice(output.get().toString(), sources);
                String emittedNotice = appendOutputChunk(notice, output);
                if (emittedNotice.isBlank()) {
                    return Flux.empty();
                }
                return Flux.just(AgentStreamEvent.delta(emittedNotice));
            }))
            .concatWith(Flux.just(
                AgentStreamEvent.step("insight", "completed"),
                AgentStreamEvent.step("action", "completed"),
                AgentStreamEvent.done()
            ))
            .doOnComplete(() -> {
                agentProgressWriter.flush(assistantMessageId);
                agentConversationService.completeMessage(
                    assistantMessageId,
                    output.get().toString(),
                    runId
                );
            })
            .doOnError(error -> {
                agentProgressWriter.flush(assistantMessageId);
                agentConversationService.failMessage(
                    assistantMessageId,
                    output.get().toString(),
                    toAgentErrorMessage(error),
                    runId
                );
            })
            .doOnCancel(() -> {
                agentProgressWriter.flush(assistantMessageId);
                if (taskHandle == null || taskHandle.markCancellationPersisted()) {
                    agentConversationService.cancelMessage(
                        assistantMessageId, output.get().toString(), runId
                    );
                }
            })
            // 模型流自己的 doFinally 会先结束 ModelCallTracker；在这里再读取聚合快照，
            // 避免真实 SSE 在完成/断开时把 modelCalls 持久化成 0。
            .doFinally(signal -> {
                persistRunUsage(assistantMessageId, runId, runTracker);
                agentMetrics.finishRun(runTracker, switch (signal) {
                    case ON_COMPLETE -> "success";
                    case CANCEL -> "cancelled";
                    default -> "failure";
                });
            });
    }

    private void persistRunUsage(
        Long assistantMessageId, String runId, AgentMetrics.RunTracker runTracker
    ) {
        if (assistantMessageId == null || runTracker == null) {
            return;
        }
        try {
            agentConversationService.updateRunUsage(
                assistantMessageId, agentMetrics.snapshot(runTracker), runId
            );
        } catch (Throwable error) {
            // 用量展示是附加能力，不能因为指标/迁移问题阻断回答终态落库。
            log.warn("Agent 运行用量持久化失败: messageId={}", assistantMessageId, error);
        }
    }

    private Flux<AgentStreamEvent> streamProviderAttempt(
        BiFunction<String, Boolean, Supplier<ModelStream>> modelStreamFactory,
        List<String> providers,
        int providerIndex,
        String sessionId,
        Long assistantMessageId,
        String runId,
        AgentMetrics.RunTracker runTracker,
        AtomicBoolean modelOutputStarted,
        AtomicReference<StringBuilder> output,
        boolean compacted
    ) {
        String currentProvider = providers.get(providerIndex);
        String providerRole = providerIndex == 0 ? "primary" : "fallback";
        return Flux.defer(() -> streamModelAttempt(
                modelStreamFactory,
                modelStreamFactory.apply(currentProvider, compacted),
                currentProvider,
                sessionId,
                assistantMessageId,
                runId,
                runTracker,
                modelOutputStarted,
                output,
                0,
                compacted
            )).doOnComplete(() -> agentMetrics.recordProviderAttempt(providerRole, "success"))
            .doOnError(error -> agentMetrics.recordProviderAttempt(providerRole, "failure"))
            .onErrorResume(error -> {
            if (!modelOutputStarted.get()
                && isProviderSwitchableError(error)
                && providerIndex < providers.size() - 1) {
                String nextProvider = providers.get(providerIndex + 1);
                log.warn(
                    "Agent 当前 Provider 不可用，切换备用 Provider: current={}, next={}, sessionId={}",
                    currentProvider,
                    nextProvider,
                    sessionId
                );
                return Flux.concat(
                    Flux.just(AgentStreamEvent.waiting(
                        "insight",
                        "当前模型暂时不可用，正在切换备用模型（" + nextProvider + "）"
                    )),
                    streamProviderAttempt(
                        modelStreamFactory,
                        providers,
                        providerIndex + 1,
                        sessionId,
                        assistantMessageId,
                        runId,
                        runTracker,
                        modelOutputStarted,
                        output,
                        compacted
                    )
                );
            }
            return Flux.error(error);
        });
    }

    /**
     * 模型已接收请求但迟迟没有首个 token 时发送低频等待事件，避免反向代理把长等待
     * 误判为断线。收到首个增量、完成或失败后立即停止心跳，不会增加模型调用次数。
     */
    Flux<AgentStreamEvent> withModelHeartbeat(Flux<AgentStreamEvent> response) {
        Sinks.One<Void> stopSignal = Sinks.one();
        Duration interval = Duration.ofSeconds(retryProperties.getRetryHeartbeatIntervalSeconds());
        Flux<AgentStreamEvent> guardedResponse = response
            .doOnNext(event -> {
                if ("delta".equals(event.type()) || "done".equals(event.type())
                    || "error".equals(event.type())) {
                    stopSignal.tryEmitEmpty();
                }
            })
            .doOnError(error -> stopSignal.tryEmitEmpty())
            .doFinally(signal -> stopSignal.tryEmitEmpty());
        Flux<AgentStreamEvent> heartbeats = Flux.interval(interval)
            .takeUntilOther(stopSignal.asMono())
            .map(index -> AgentStreamEvent.waiting(
                "insight",
                "模型仍在生成回答，已等待约 " + ((index + 1) * interval.toSeconds()) + " 秒"
            ));
        return Flux.merge(guardedResponse, heartbeats);
    }

    private Flux<AgentStreamEvent> streamModelAttempt(
        BiFunction<String, Boolean, Supplier<ModelStream>> modelStreamFactory,
        Supplier<ModelStream> modelStream,
        String providerId,
        String sessionId,
        Long assistantMessageId,
        String runId,
        AgentMetrics.RunTracker runTracker,
        AtomicBoolean modelOutputStarted,
        AtomicReference<StringBuilder> output,
        int attempt,
        boolean compacted
    ) {
        return Flux.defer(() -> {
            ModelStream currentCall = modelStream.get();
            Flux<AgentStreamEvent> attemptStream = currentCall.content()
                .doOnNext(content -> {
                    modelOutputStarted.set(true);
                })
                .doOnComplete(() -> llmProviderRegistry.markChatProviderSuccess(providerId))
                .map(content -> appendOutputChunk(content, output))
                // 达到展示上限后立即取消上游模型流，避免继续消耗模型额度。
                .takeUntil(ignored -> output.get().length() >= MAX_AGENT_OUTPUT_CHARS)
                .filter(content -> !content.isEmpty())
                .map(AgentStreamEvent::delta);
            return withModelHeartbeat(attemptStream)
            .doOnComplete(() -> finishModelCallAndPersist(
                currentCall.tracker(), "success", providerId, assistantMessageId, runId, runTracker))
            .doOnError(error -> finishModelCallAndPersist(
                currentCall.tracker(), "failure", providerId, assistantMessageId, runId, runTracker))
            .doOnCancel(() -> finishModelCallAndPersist(
                currentCall.tracker(), "cancelled", providerId, assistantMessageId, runId, runTracker))
            .onErrorResume(error -> {
                if (!modelOutputStarted.get() && isContextLimitError(error) && !compacted) {
                    agentMetrics.recordRetry("context_limit");
                    recordRunTrace(
                        runTracker, "MODEL_RETRY", "agent-model", providerId, "context-limit"
                    );
                    return Flux.concat(
                        Flux.just(AgentStreamEvent.waiting(
                            "insight", "本次上下文较长，正在压缩较早对话后重试"
                        )),
                        streamProviderAttempt(
                            // Context-limit fallback starts from the current provider;
                            // its caller still retains the complete candidate chain.
                            modelStreamFactory,
                            providerCandidatesFromCurrent(providerId),
                            0,
                            sessionId,
                            assistantMessageId,
                            runId,
                            runTracker,
                            modelOutputStarted,
                            output,
                            true
                        )
                    );
                }
                if (isProviderSwitchableError(error) && !isContextLimitError(error)) {
                    llmProviderRegistry.markChatProviderFailure(providerId);
                }
                if (!modelOutputStarted.get()
                    && isTransientModelError(error)
                    && attempt < retryProperties.getModelRetryAttempts()) {
                    agentMetrics.recordRetry("transient");
                    recordRunTrace(
                        runTracker, "MODEL_RETRY", "agent-model", providerId, "transient"
                    );
                    Duration delay = retryDelay(attempt);
                    int retryNumber = attempt + 1;
                    log.warn(
                        "Agent 模型服务暂时繁忙，将在 {} 秒后进行第 {} 次重试: providerId={}, sessionId={}",
                        delay.toSeconds(), retryNumber, providerId, sessionId
                    );
                    return Flux.concat(
                        Flux.just(AgentStreamEvent.waiting(
                            "insight",
                            "模型服务暂时繁忙，正在等待资源（第 " + retryNumber + "/"
                                + retryProperties.getModelRetryAttempts() + " 次重试）"
                        )),
                        retryWaitingEvents(delay, retryNumber),
                        Flux.just(AgentStreamEvent.step("insight", "running")),
                        streamModelAttempt(
                            modelStreamFactory,
                            modelStreamFactory.apply(providerId, compacted),
                            providerId,
                            sessionId,
                            assistantMessageId,
                            runId,
                            runTracker,
                            modelOutputStarted,
                            output,
                            retryNumber,
                            compacted
                        )
                    );
                }
                return Flux.error(error);
            });
        });
    }

    private void finishModelCallAndPersist(
        AgentMetrics.ModelCallTracker tracker,
        String result,
        String providerId,
        Long assistantMessageId,
        String runId,
        AgentMetrics.RunTracker runTracker
    ) {
        agentMetrics.finishModelCall(tracker, result);
        if (runStateService != null) {
            runStateService.modelTurnFinished(runTracker, providerId, result);
        }
        recordRunTrace(runTracker, "MODEL_COMPLETED", "agent-model", providerId, result);
        // 必须在外层 completeMessage/failMessage 之前写入，避免活动消息栅栏关闭后被忽略。
        persistRunUsage(assistantMessageId, runId, runTracker);
    }

    private record ModelStream(
        Flux<String> content,
        AgentMetrics.ModelCallTracker tracker
    ) {
    }

    private List<String> providerCandidatesFromCurrent(String providerId) {
        List<String> candidates = llmProviderRegistry.resolveChatProviderCandidates(providerId);
        int index = candidates.indexOf(providerId);
        return index < 0 ? List.of(providerId) : List.copyOf(candidates.subList(index, candidates.size()));
    }

    /**
     * 在退避等待期间发送低频状态事件，同时严格保持原有退避总时长。
     *
     * <p>事件本身不触发重试，只有等待完整个 delay 后才会订阅下一次模型调用，
     * 因此不会因为心跳而增加模型请求次数。</p>
     */
    Flux<AgentStreamEvent> retryWaitingEvents(Duration delay, int retryNumber) {
        return retryWaitingEvents(
            delay,
            retryNumber,
            Duration.ofSeconds(retryProperties.getRetryHeartbeatIntervalSeconds())
        );
    }

    Flux<AgentStreamEvent> retryWaitingEvents(
        Duration delay,
        int retryNumber,
        Duration heartbeatInterval
    ) {
        long delayMillis = Math.max(0, delay.toMillis());
        long heartbeatMillis = Math.max(1, heartbeatInterval.toMillis());
        Duration effectiveHeartbeatInterval = Duration.ofMillis(heartbeatMillis);
        long heartbeatCount = delayMillis / heartbeatMillis;
        long remainingMillis = delayMillis - heartbeatCount * heartbeatMillis;

        Flux<AgentStreamEvent> heartbeats = heartbeatCount == 0
            ? Flux.empty()
            : Flux.interval(effectiveHeartbeatInterval)
                .take(heartbeatCount)
                .map(index -> {
                    long remainingSeconds = Math.max(
                        0, (delayMillis - (index + 1) * heartbeatMillis + 999) / 1000
                    );
                    String suffix = remainingSeconds == 0
                        ? "即将重试"
                        : "预计还需约 " + remainingSeconds + " 秒";
                    return AgentStreamEvent.waiting(
                        "insight",
                        "模型服务暂时繁忙，正在等待资源（第 " + retryNumber + "/"
                            + retryProperties.getModelRetryAttempts() + " 次重试，" + suffix + "）"
                    );
                });

        if (remainingMillis == 0) {
            return heartbeats;
        }
        return heartbeats.concatWith(
            Mono.delay(Duration.ofMillis(remainingMillis)).thenMany(Flux.empty())
        );
    }

    private Duration retryDelay(int attempt) {
        return Duration.ofSeconds(Math.min(
            retryProperties.getRetryMaxDelaySeconds(),
            2L << attempt
        ));
    }

    private String appendOutputChunk(String content, AtomicReference<StringBuilder> output) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder target = output.get();
        int contentLimit = MAX_AGENT_OUTPUT_CHARS - OUTPUT_TRUNCATION_NOTICE.length();
        if (target.length() >= contentLimit) {
            if (!target.toString().endsWith(OUTPUT_TRUNCATION_NOTICE)) {
                target.append(OUTPUT_TRUNCATION_NOTICE);
            }
            return "";
        }

        int remaining = contentLimit - target.length();
        if (content.length() <= remaining) {
            target.append(content);
            if (target.length() >= contentLimit) {
                target.append(OUTPUT_TRUNCATION_NOTICE);
                return content + OUTPUT_TRUNCATION_NOTICE;
            }
            return content;
        }

        String visible = content.substring(0, remaining);
        target.append(visible).append(OUTPUT_TRUNCATION_NOTICE);
        return visible + OUTPUT_TRUNCATION_NOTICE;
    }

    private PromptVariant buildPromptVariant(
        AgentContextService.AgentContext context,
        AgentMessageRequest request,
        String message,
        List<AgentHistoryMessage> history,
        String plan,
        String loopTrace,
        boolean compacted,
        AgentIntentRouter.Decision intentDecision
    ) {
        String report = compacted
            ? compactPromptText(context.interviewReport(), CONTEXT_RETRY_REPORT_CHARS)
            : context.interviewReport();
        String skill = compacted
            ? compactPromptText(context.skillReference(), CONTEXT_RETRY_SKILL_CHARS)
            : context.skillReference();
        String rag = compacted
            ? compactPromptText(context.ragContext(), CONTEXT_RETRY_RAG_CHARS)
            : context.ragContext();
        String memory = compacted
            ? compactPromptText(context.longTermMemory(), 2_000)
            : context.longTermMemory();
        String sessionSummary = compacted
            ? compactPromptText(context.sessionSummary(), 3_000)
            : context.sessionSummary();
        AgentPromptBudgetPolicy.BudgetedPrompt budgeted = AgentPromptBudgetPolicy.fit(
            report, skill, memory, rag, sessionSummary, plan, loopTrace, message,
            history, retryProperties.getContextBudgetTokens()
        );
        report = budgeted.report();
        skill = budgeted.skill();
        memory = budgeted.memory();
        rag = budgeted.rag();
        sessionSummary = budgeted.sessionSummary();
        plan = budgeted.plan();
        loopTrace = budgeted.loopTrace();
        message = budgeted.currentMessage();
        Map<String, Object> fixedContextVariables = Map.of(
            "context", promptSanitizer.wrapWithStableDelimiters(
                "interview-report", report),
            "skill", promptSanitizer.wrapWithStableDelimiters(
                "skill-reference", skill),
            "memory", promptSanitizer.wrapWithStableDelimiters(
                "long-term-memory", memory)
        );
        Map<String, Object> variables = Map.of(
            "goal", promptSanitizer.wrapWithDelimiters("agent-goal", request.goal()),
            "question", promptSanitizer.wrapWithDelimiters("user-message", message),
            "rag", promptSanitizer.wrapWithDelimiters("rag-context", rag),
            "plan", promptSanitizer.wrapWithStableDelimiters(
                "agent-loop-plan", plan + "\n\n已完成的 Loop 观察：\n" + loopTrace
            ),
            "summary", promptSanitizer.wrapWithDelimiters("session-summary", sessionSummary)
        );
        // system prompt 只保留版本化、不会随会话变化的策略和安全边界。
        // 报告、Skill、记忆等资料即使当前看起来固定，也可能因绑定或记忆更新而变化，
        // 放在 user 动态段可以让稳定前缀在多轮对话中真正保持一致。
        String systemPrompt = systemPromptTemplate.render()
            + PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION;
        String userPrompt = fixedContextPromptTemplate.render(fixedContextVariables)
            + "\n\n"
            + buildAnswerFormatInstruction(context, request, message, intentDecision)
            + "\n\n"
            + userPromptTemplate.render(variables);
        return new PromptVariant(systemPrompt, userPrompt, buildModelHistory(budgeted.history()));
    }

    private String buildAnswerFormatInstruction(
        AgentContextService.AgentContext context,
        AgentMessageRequest request,
        String message,
        AgentIntentRouter.Decision intentDecision
    ) {
        String goal = request == null ? "" : request.goal();
        String question = message == null ? "" : message;
        String combined = (goal + "\n" + question).toLowerCase(java.util.Locale.ROOT);
        String requiredTool = agentIntentRouter.requiredToolName(question);
        if (requiredTool != null && intentDecision != null
            && intentDecision.capabilities().contains(AgentIntentRouter.Capability.SCHEDULE)) {
            return """
                本轮是明确的面试日程操作请求，必须先调用受控工具生成待确认草稿，再向用户展示工具结果。
                不能只输出一段 Markdown 假设操作已经完成，也不能直接写入或修改正式日程。
                工具参数只能来自当前用户消息和可信上下文；缺少必填信息时不要伪造，先向用户询问缺失信息。
                """;
        }
        if ("general".equalsIgnoreCase(goal)) {
            return """
                本轮是通用 Agent 对话，不是面试复盘。
                1. 使用标准 Markdown，直接回答用户当前问题。
                2. 只能使用本轮显式提供或已绑定的资料，不要推断未提供的面试经历、分数或表现。
                3. 没有相关资料时直接说明资料不足，不要输出“本场面试复盘结论”等复盘模板。
                """;
        }
        boolean explicitlyNoRag = combined.contains("没有知识库")
            || combined.contains("没有检索") || combined.contains("无知识库")
            || combined.contains("无检索");
        boolean ragRequested = context.sources().stream()
            .anyMatch(source -> source != null && "rag".equalsIgnoreCase(source.kind()))
            || (!explicitlyNoRag && (combined.contains("知识库") || combined.contains("rag")));
        if (ragRequested) {
            return """
                本轮回复必须遵守以下可读性和证据边界：
                1. 使用标准 Markdown，每个标题独占一行，标题与正文之间空一行。
                2. 必须包含且只使用清晰的一级标题：## 结论、## 依据、## 建议。
                3. 依据部分只能写入本轮上下文中真实存在的资料；没有实际 RAG 来源时，明确写“本次没有检索到可用知识库资料”，不得生成 [R1] 等引用。
                4. 建议必须基于面试报告和真实资料，不能把未作答或占位分数当成能力结论。
                """;
        }
        return """
            本轮回复必须遵守以下可读性和证据边界：
            1. 使用标准 Markdown，每个标题独占一行，标题与正文之间空一行。
            2. 复盘类回复必须包含：## 结论、## 依据边界、## 重点、## 行动建议；每个标题后至少有一段正文或一个列表。
            3. 依据边界要区分面试报告、Skill 资料和知识库是否真实可用；不能把未作答或占位分数当成能力结论。
            4. 重点和行动建议要覆盖报告中实际出现的主要主题，不得编造报告没有出现的经历或结果。
            """;
    }

    private String compactPromptText(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        String marker = "\n[较早固定资料已压缩]\n";
        int available = Math.max(0, maxChars - marker.length());
        int headChars = (available + 1) / 2;
        int tailChars = available - headChars;
        return value.substring(0, headChars)
            + marker
            + value.substring(value.length() - tailChars);
    }

    String toAgentErrorMessage(Throwable error) {
        if (error instanceof AgentRunBudgetExceededException) {
            return "本次 Agent 任务已达到资源调用预算，系统已停止继续重试；请缩短问题或稍后重新发起任务";
        }
        if (isOutputTokenLimitError(error)) {
            return "当前模型 Provider 限制单次输出长度，系统会尝试切换可用模型；如果仍失败，请分段发送或切换其他模型。这不是面试消息字符限制";
        }
        if (isContextLimitError(error)) {
            return "本次上下文过长，模型无法处理；请清空部分对话、缩短问题，或分多次发送后重试";
        }
        if (isProviderAccountUnavailable(error)) {
            return "当前模型账号不可用，请在模型设置中检查 API Key、模型权限或切换其他模型";
        }
        if (isAuthenticationError(error)) {
            return "当前模型 API Key 或访问权限无效，请在模型设置中检查配置后重试";
        }
        if (isTimeoutError(error)) {
            return "模型响应超时，本次任务已结束；可以重试或切换其他模型";
        }
        if (isTransientModelError(error)) {
            return "当前模型服务繁忙，请稍后重试；也可以在模型设置中切换其他模型";
        }
        return "AI 面试教练暂时不可用，请稍后重试";
    }

    private boolean isContextLimitError(Throwable error) {
        return containsErrorMessage(error,
            "context length", "maximum context", "context window", "too many tokens",
            "prompt is too long", "input is too long", "token limit", "request entity too large", "413"
        );
    }

    private boolean isOutputTokenLimitError(Throwable error) {
        return containsErrorMessage(error,
            "max_tokens", "max completion tokens", "maximum output tokens",
            "output tokens", "must be between 0 and"
        );
    }

    private boolean isTimeoutError(Throwable error) {
        return containsErrorMessage(error, "timeout", "timed out", "deadline exceeded", "read timed out");
    }

    private boolean isAuthenticationError(Throwable error) {
        return containsErrorMessage(error,
            "unauthorized", "invalid api key", "invalid_api_key", "authentication failed", "401", "403"
        );
    }

    private boolean isTransientModelError(Throwable error) {
        return isTimeoutError(error) || containsErrorMessage(error,
            "overloaded", "temporarily unavailable", "service unavailable", "try again later",
            "concurrency limit", "rate limit", "too many requests", "429",
            "internal server error", "bad gateway", "gateway timeout", "upstream connect error",
            "connection reset", "connection refused", "500", "502", "503", "504"
        );
    }

    private boolean containsErrorMessage(Throwable error, String... fragments) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            for (String fragment : fragments) {
                if (normalized.contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isProviderSwitchableError(Throwable error) {
        if (isContextLimitError(error) || isOutputTokenLimitError(error)
            || isTransientModelError(error) || isProviderAccountUnavailable(error)) {
            return true;
        }
        return containsErrorMessage(error,
            "unknown llm provider", "unauthorized", "invalid api key", "invalid_api_key",
            "authentication failed", "401", "403"
        );
    }

    private boolean isProviderAccountUnavailable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("selected account") && normalized.contains("no longer available")) {
                return true;
            }
        }
        return false;
    }

    List<Message> buildModelHistory(List<AgentHistoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>();
        for (AgentHistoryMessage message : messages) {
            String role = "assistant".equals(message.role()) ? "assistant" : "user";
            // 历史记录是用户可编辑/导入的持久化数据，不能重新获得 assistant
            // 消息层级。统一降级为 UserMessage，并把原角色放进不可信资料边界，
            // 这样旧的助手输出也不能伪造当前轮的高优先级指令。
            String historyData = "历史消息角色：" + role + "\n"
                + message.content();
            String content = promptSanitizer.wrapWithStableDelimiters(
                "agent-history-record", historyData
            );
            result.add(new UserMessage(content));
        }
        return result;
    }

    private Map<String, Object> agentToolContext(
        AgentMetrics.RunTracker runTracker, String userMessage
    ) {
        return Map.of(
            AgentMetrics.RUN_TRACKER_TOOL_CONTEXT_KEY, runTracker,
            AgentMetrics.USER_MESSAGE_TOOL_CONTEXT_KEY, userMessage == null ? "" : userMessage
        );
    }

    /**
     * 当前用户输入仍然可以正常获得分析回答，但命中注入检测时必须留下低敏安全事件。
     * 工具授权继续使用原始消息并由工具层拒绝，不能因为模型侧净化后只剩下业务词就放行。
     */
    private void auditPromptSecurity(
        AgentMetrics.RunTracker runTracker,
        String message,
        String goal,
        String ownerKey,
        String interviewSessionId,
        String agentSessionId,
        String runId
    ) {
        if (agentAuditService == null || runTracker == null) {
            return;
        }
        if (promptSanitizer.detectInjectionAttempt(message)) {
            agentAuditService.recordPromptInjectionDetected(
                ownerKey, interviewSessionId, agentSessionId, runId, "message"
            );
        }
        if (promptSanitizer.detectInjectionAttempt(goal)) {
            agentAuditService.recordPromptInjectionDetected(
                ownerKey, interviewSessionId, agentSessionId, runId, "goal"
            );
        }
    }

    private void recordRunTrace(
        AgentMetrics.RunTracker runTracker,
        String action,
        String resourceType,
        String resourceId,
        String result
    ) {
        if (agentAuditService != null) {
            agentAuditService.recordRunTrace(runTracker, action, resourceType, resourceId, result);
        }
    }

    private record LoopResumeState(
        String plan,
        int nextStepIndex,
        List<Integer> completedStepIndices,
        List<AgentLoopTraceItem> trace
    ) {
        private LoopResumeState {
            completedStepIndices = completedStepIndices == null
                ? List.of() : List.copyOf(completedStepIndices);
            trace = trace == null ? List.of() : List.copyOf(trace);
        }

        private boolean hasPlan() {
            return plan != null && !plan.isBlank();
        }
    }

    private record PromptVariant(String systemPrompt, String userPrompt, List<Message> history) {
    }
}
