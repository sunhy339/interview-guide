package interview.guide.modules.agent;

import interview.guide.common.annotation.RateLimit;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentConversationResponse;
import interview.guide.modules.agent.model.AgentConversationSummary;
import interview.guide.modules.agent.model.AgentMemoryResponse;
import interview.guide.modules.agent.model.AgentAuditEventResponse;
import interview.guide.modules.agent.model.UpdateAgentMemoryItemRequest;
import interview.guide.modules.agent.model.AgentStreamEvent;
import interview.guide.modules.agent.model.CreateAgentConversationRequest;
import interview.guide.modules.agent.model.RenameAgentConversationRequest;
import interview.guide.modules.agent.model.AgentActionResponse;
import interview.guide.modules.agent.model.CreateAgentDraftActionRequest;
import interview.guide.modules.agent.model.CreateAgentMemoryDraftRequest;
import interview.guide.modules.agent.model.CreateAgentTitleDraftRequest;
import interview.guide.modules.agent.model.CreateAgentScheduleDraftRequest;
import interview.guide.modules.agent.model.UpdateAgentActionDraftRequest;
import interview.guide.modules.agent.model.AgentTrainingTaskResponse;
import interview.guide.modules.agent.model.AgentTrainingTaskStatus;
import interview.guide.modules.agent.model.AgentUsageSummaryResponse;
import interview.guide.modules.agent.model.AgentUsageRunResponse;
import interview.guide.modules.agent.model.AgentContextBindingResponse;
import interview.guide.modules.agent.model.AttachAgentContextRequest;
import interview.guide.modules.agent.model.AgentInterviewSearchResult;
import interview.guide.modules.agent.model.AgentContextAssetResponse;
import interview.guide.modules.agent.model.AttachAgentResourceRequest;
import interview.guide.modules.agent.model.UpdateAgentTrainingTaskRequest;
import interview.guide.modules.agent.service.AgentConversationService;
import interview.guide.modules.agent.service.AgentActionService;
import interview.guide.modules.agent.service.AgentMemoryService;
import interview.guide.modules.agent.service.AgentAuditService;
import interview.guide.modules.agent.service.InterviewAgentService;
import interview.guide.modules.agent.service.AgentTrainingTaskService;
import interview.guide.modules.agent.service.AgentUsageService;
import interview.guide.modules.agent.service.AgentContextAssetService;
import interview.guide.modules.agent.service.AgentContextBindingService;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.auth.AuthIdentityService;
import interview.guide.common.result.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.support.WebExchangeBindException;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Collections;
import java.util.stream.Collectors;

/**
 * AI Agent 接口。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "AI 面试教练", description = "基于面试报告的 Agent 复盘与训练对话")
public class AgentController {
    private final InterviewAgentService agentService;
    private final AgentConversationService conversationService;
    private final AgentMemoryService memoryService;
    private final AgentAuditService auditService;
    private final AgentActionService actionService;
    private final AgentTrainingTaskService trainingTaskService;
    private final AgentUsageService usageService;
    private final InterviewHistoryService interviewHistoryService;
    private final AuthIdentityService authIdentityService;
    private final ObjectMapper objectMapper;
    private final interview.guide.modules.agent.service.AgentInterviewContextService interviewContextService;
    private final AgentContextAssetService contextAssetService;
    private final AgentContextBindingService contextBindingService;

    /** 创建不绑定面试报告的通用 Agent 会话。 */
    @PostMapping("/api/agent/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationSummary> createGeneralConversation(
        @Valid @RequestBody(required = false) CreateAgentConversationRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.createGeneralConversation(
            request == null ? null : request.title(),
            identity,
            request == null ? null : request.idempotencyKey()
        ));
    }

    /** 搜索当前用户自己的面试元数据，供 Agent 选择上下文时消歧。 */
    @GetMapping("/api/agent/interviews/search")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentInterviewSearchResult>> searchInterviewsForAgent(
        @RequestParam(value = "q", required = false) String query,
        @RequestParam(value = "limit", defaultValue = "8") int limit,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(interviewContextService.search(
            authIdentityService.requireIdentity(ownerKey), query, limit
        ));
    }

    /** 查询当前用户未归档的通用 Agent 会话。 */
    @GetMapping("/api/agent/sessions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentConversationSummary>> listGeneralConversations(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(conversationService.listGeneralConversations(
            authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 上传 Agent 私有素材；只做技术检查和解析，默认停留在待审核状态。 */
    @PostMapping(value = "/api/agent/context-assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Result<AgentContextAssetResponse> uploadContextAsset(
        @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextAssetService.upload(
            file, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 查询当前用户的 Agent 私有素材审核列表。 */
    @GetMapping("/api/agent/context-assets")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentContextAssetResponse>> listContextAssets(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextAssetService.list(authIdentityService.requireIdentity(ownerKey)));
    }

    @GetMapping("/api/agent/context-assets/{assetId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentContextAssetResponse> getContextAsset(
        @PathVariable String assetId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextAssetService.get(
            assetId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 用户明确审核通过后，素材才可以绑定到 Agent 会话。 */
    @PostMapping("/api/agent/context-assets/{assetId}/approve")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentContextAssetResponse> approveContextAsset(
        @PathVariable String assetId,
        @RequestParam(value = "confirmSecurity", defaultValue = "false") boolean confirmSecurity,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextAssetService.approve(
            assetId, authIdentityService.requireIdentity(ownerKey), confirmSecurity
        ));
    }

    /** 用户明确提出将素材纳入 RAG 的意图；真正导入仍由后续确认流程执行。 */
    @PostMapping("/api/agent/context-assets/{assetId}/knowledge-base-request")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentContextAssetResponse> requestKnowledgeBaseImport(
        @PathVariable String assetId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextAssetService.requestKnowledgeBaseImport(
            assetId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 用户再次确认后，才把 Agent 素材写入现有知识库并开始异步向量化。 */
    @PostMapping("/api/agent/context-assets/{assetId}/knowledge-base/confirm")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentContextAssetResponse> confirmKnowledgeBaseImport(
        @PathVariable String assetId,
        @RequestParam(value = "name", required = false) String name,
        @RequestParam(value = "category", required = false) String category,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextAssetService.confirmKnowledgeBaseImport(
            assetId, authIdentityService.requireIdentity(ownerKey), name, category
        ));
    }

    /** 拒绝并删除尚未进入知识库的 Agent 私有素材。 */
    @DeleteMapping("/api/agent/context-assets/{assetId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<Void> rejectContextAsset(
        @PathVariable String assetId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        contextAssetService.reject(assetId, authIdentityService.requireIdentity(ownerKey));
        return Result.success(null);
    }

    /** 读取通用 Agent 会话。 */
    @GetMapping("/api/agent/sessions/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentConversationResponse> getGeneralConversation(
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(conversationService.getGeneralConversation(
            agentSessionId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    @PatchMapping("/api/agent/sessions/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationSummary> renameGeneralConversation(
        @PathVariable String agentSessionId,
        @Valid @RequestBody RenameAgentConversationRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(conversationService.renameConversation(
            null, agentSessionId, request.title(), authIdentityService.requireIdentity(ownerKey)
        ));
    }

    @DeleteMapping("/api/agent/sessions/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> archiveGeneralConversation(
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        conversationService.archiveConversation(
            null, agentSessionId, authIdentityService.requireIdentity(ownerKey)
        );
        return Result.success(null);
    }

    @PostMapping("/api/agent/sessions/{agentSessionId}/cancel")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationResponse> cancelGeneralRun(
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(null, agentSessionId, identity);
        agentService.cancel(agentSessionId);
        conversationService.cancelRun(null, agentSessionId, identity);
        return Result.success(conversationService.getConversation(null, agentSessionId, identity));
    }

    /** 查询通用 Agent 会话当前绑定的面试上下文。 */
    @GetMapping("/api/agent/sessions/{agentSessionId}/context")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentContextBindingResponse>> getGeneralContext(
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextBindingService.list(
            agentSessionId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 将用户明确选择的面试报告绑定到通用 Agent 会话。 */
    @PostMapping("/api/agent/sessions/{agentSessionId}/context/interview")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentContextBindingResponse> attachGeneralInterviewContext(
        @PathVariable String agentSessionId,
        @Valid @RequestBody AttachAgentContextRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(AgentContextBindingResponse.from(
            conversationService.attachInterviewContext(
                agentSessionId, request.interviewSessionId(), identity, request.sourceMessageIdAsLong()
            )
        ));
    }

    /** 将已审核的简历、面试报告、上传文件或知识库绑定到 Agent 会话。 */
    @PostMapping("/api/agent/sessions/{agentSessionId}/contexts")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentContextBindingResponse> attachGeneralResourceContext(
        @PathVariable String agentSessionId,
        @Valid @RequestBody AttachAgentResourceRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(contextBindingService.attach(
            agentSessionId, request, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 解绑通用 Agent 会话的当前面试上下文。 */
    @DeleteMapping("/api/agent/sessions/{agentSessionId}/context")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<Void> clearGeneralContext(
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        contextBindingService.clear(agentSessionId, authIdentityService.requireIdentity(ownerKey));
        return Result.success(null);
    }

    /** 通用 Agent 会话流式消息入口；路径中的线程 ID优先于请求体。 */
    @PostMapping(value = "/api/agent/sessions/{agentSessionId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamGeneralMessage(
        @PathVariable String agentSessionId,
        @Valid @RequestBody AgentMessageRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return agentService.stream(null, request.withAgentSessionId(agentSessionId), identity)
            .map(event -> ServerSentEvent.<AgentStreamEvent>builder()
                .event("agent-" + event.type())
                .data(event)
                .build());
    }

    /** 查询当前登录用户跨 Agent 对话的累计 Token、耗时和费用。 */
    @GetMapping("/api/agent/usage/summary")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentUsageSummaryResponse> getUsageSummary(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(usageService.summarize(authIdentityService.requireIdentity(ownerKey)));
    }

    /** 查询当前账号最近的 Agent 运行明细，包含来源面试和 Agent 会话名称。 */
    @GetMapping("/api/agent/usage/runs")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentUsageRunResponse>> getUsageRuns(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey,
        @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        return Result.success(usageService.listRuns(
            authIdentityService.requireIdentity(ownerKey), limit
        ));
    }

    @GetMapping("/api/agent/interview-sessions/{sessionId}/conversation")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentConversationResponse> getConversation(
        @PathVariable String sessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey,
        @RequestParam(value = "agentSessionId", required = false) String agentSessionId
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(conversationService.getConversation(sessionId, agentSessionId, identity));
    }

    @GetMapping("/api/agent/interview-sessions/{sessionId}/conversations")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentConversationSummary>> listConversations(
        @PathVariable String sessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.listConversations(sessionId, identity));
    }

    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationSummary> createConversation(
        @PathVariable String sessionId,
        @Valid @RequestBody(required = false) CreateAgentConversationRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.createConversation(
            sessionId,
            request == null ? null : request.title(),
            identity,
            request == null ? null : request.idempotencyKey()
        ));
    }

    @PatchMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationSummary> renameConversation(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody RenameAgentConversationRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(conversationService.renameConversation(
            sessionId, agentSessionId, request.title(), identity
        ));
    }

    @DeleteMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> archiveConversation(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.archiveConversation(sessionId, agentSessionId, identity);
        return Result.success(null);
    }

    @PostMapping("/api/agent/interview-sessions/{sessionId}/cancel")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentConversationResponse> cancelRun(
        @PathVariable String sessionId,
        @RequestParam(value = "agentSessionId", required = false) String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        agentService.cancel(agentSessionId == null || agentSessionId.isBlank() ? sessionId : agentSessionId);
        conversationService.cancelRun(sessionId, agentSessionId, identity);
        return Result.success(conversationService.getConversation(sessionId, agentSessionId, identity));
    }

    @DeleteMapping("/api/agent/memory")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<Void> clearMemory(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        memoryService.clear(authIdentityService.requireIdentity(ownerKey));
        return Result.success(null);
    }

    @GetMapping("/api/agent/memory")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentMemoryResponse> getMemory(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(AgentMemoryResponse.from(
            memoryService.load(authIdentityService.requireIdentity(ownerKey))
        ));
    }

    /** 查询当前登录用户最近的低敏 Agent 操作事件。 */
    @GetMapping("/api/agent/audit/events")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentAuditEventResponse>> listAuditEvents(
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey,
        @RequestParam(value = "limit", defaultValue = "30") int limit
    ) {
        return Result.success(auditService.listForOwner(
            authIdentityService.requireIdentity(ownerKey), limit
        ));
    }

    /** 查询当前面试下的 Agent 动作草稿，不读取其他用户的动作。 */
    @GetMapping("/api/agent/interview-sessions/{sessionId}/actions")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentActionResponse>> listActions(
        @PathVariable String sessionId,
        @RequestParam(value = "agentSessionId", required = false) String agentSessionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.listForOwner(sessionId, agentSessionId, identity));
    }

    /** 只生成待确认的训练计划草稿，不修改原始面试或简历数据。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/training-plan-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> proposeTrainingPlanDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentDraftActionRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeTrainingPlanDraft(
            sessionId, agentSessionId, identity, request.content(), request.idempotencyKey()
        ));
    }

    /** 只生成待确认的练习题草稿，不修改题库或原始面试数据。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/practice-questions-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> proposePracticeQuestionsDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentDraftActionRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposePracticeQuestionsDraft(
            sessionId, agentSessionId, identity, request.content(), request.idempotencyKey()
        ));
    }

    /** 只生成待确认的训练任务草稿，确认后才创建任务。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/training-task-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> proposeTrainingTaskDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentDraftActionRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeTrainingTaskDraft(
            sessionId, agentSessionId, identity, request.content(), request.idempotencyKey()
        ));
    }

    /** 只生成待确认的面试报告导出动作。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/interview-report-export-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> proposeInterviewReportExportDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @RequestBody(required = false) CreateAgentDraftActionRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeInterviewReportExportDraft(
            sessionId, agentSessionId, identity,
            request == null ? null : request.idempotencyKey()
        ));
    }

    /** 只生成待确认的简历优化编辑版草稿；确认后保存到简历编辑器，不覆盖原始上传文件。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/resume-edit-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentActionResponse> proposeResumeEditDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentDraftActionRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeResumeEditDraft(
            sessionId, agentSessionId, identity, request.content(), request.idempotencyKey()
        ));
    }

    /** 只生成待确认的复习日程草稿；确认后写入面试日程。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/interview-schedule-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public Result<AgentActionResponse> proposeInterviewScheduleDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentScheduleDraftRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeInterviewScheduleDraft(
            sessionId, agentSessionId, identity, request.companyName(), request.position(),
            request.interviewTime(), request.interviewType(), request.meetingLink(),
            request.roundNumber(), request.interviewer(), request.notes(), request.idempotencyKey()
        ));
    }

    /** 只生成待确认的长期记忆草稿，确认后才写入结构化记忆。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/memory-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> proposeMemoryDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentMemoryDraftRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeMemoryDraft(
            sessionId, agentSessionId, identity, request.content(), request.category(),
            request.jobScope(), request.idempotencyKey()
        ));
    }

    /** 只生成待确认的面试名称草稿，确认后才修改面试记录名称。 */
    @PostMapping("/api/agent/interview-sessions/{sessionId}/conversations/{agentSessionId}/actions/interview-title-draft")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> proposeInterviewTitleDraft(
        @PathVariable String sessionId,
        @PathVariable String agentSessionId,
        @Valid @RequestBody CreateAgentTitleDraftRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        conversationService.assertAccess(sessionId, agentSessionId, identity);
        return Result.success(actionService.proposeInterviewTitleDraft(
            sessionId, agentSessionId, identity, request.content(), request.idempotencyKey()
        ));
    }

    @PostMapping("/api/agent/actions/{actionId}/confirm")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> confirmAction(
        @PathVariable String actionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(actionService.confirm(
            actionId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 仅允许当前用户使用已确认的报告导出动作下载 PDF。 */
    @GetMapping("/api/agent/actions/{actionId}/interview-report")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    public ResponseEntity<byte[]> exportConfirmedInterviewReport(
        @PathVariable String actionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        String sessionId = actionService.loadConfirmedReportSessionId(actionId, identity);
        byte[] pdfBytes = interviewHistoryService.exportInterviewPdfForOwner(sessionId, identity);
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=interview-report.pdf")
            .contentType(MediaType.APPLICATION_PDF)
            .body(pdfBytes);
    }

    /** 重试执行失败的 Agent 动作，服务端最多允许三次实际执行。 */
    @PostMapping("/api/agent/actions/{actionId}/retry")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> retryAction(
        @PathVariable String actionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(actionService.retry(
            actionId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    @PatchMapping("/api/agent/actions/{actionId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> updateActionDraft(
        @PathVariable String actionId,
        @Valid @RequestBody UpdateAgentActionDraftRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(actionService.updateDraft(
            actionId, authIdentityService.requireIdentity(ownerKey), request.content()
        ));
    }

    @PostMapping("/api/agent/actions/{actionId}/cancel")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentActionResponse> cancelAction(
        @PathVariable String actionId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(actionService.cancel(
            actionId, authIdentityService.requireIdentity(ownerKey)
        ));
    }

    /** 查询当前用户的 Agent 训练任务。 */
    @GetMapping("/api/agent/training-tasks")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<List<AgentTrainingTaskResponse>> listTrainingTasks(
        @RequestParam(value = "status", required = false) AgentTrainingTaskStatus status,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(trainingTaskService.listForOwner(
            authIdentityService.requireIdentity(ownerKey), status
        ));
    }

    /** 更新训练任务进度；已完成或已取消任务不可回退。 */
    @PatchMapping("/api/agent/training-tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 30)
    public Result<AgentTrainingTaskResponse> updateTrainingTask(
        @PathVariable String taskId,
        @Valid @RequestBody UpdateAgentTrainingTaskRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        return Result.success(trainingTaskService.updateStatus(
            taskId, authIdentityService.requireIdentity(ownerKey), request.status()
        ));
    }

    @PatchMapping("/api/agent/memory/items/{itemId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentMemoryResponse> updateMemoryItem(
        @PathVariable String itemId,
        @Valid @RequestBody UpdateAgentMemoryItemRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(AgentMemoryResponse.from(
            memoryService.updateItem(identity, itemId, request.content(), request.jobScope())
        ));
    }

    @DeleteMapping("/api/agent/memory/items/{itemId}")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 20)
    public Result<AgentMemoryResponse> deleteMemoryItem(
        @PathVariable String itemId,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        return Result.success(AgentMemoryResponse.from(memoryService.deleteItem(identity, itemId)));
    }

    /**
     * 基于指定面试报告执行一次 Agent 任务，并流式返回工作流事件和模型回答。
     */
    @PostMapping(value = "/api/agent/interview-sessions/{sessionId}/messages/stream",
        produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<ServerSentEvent<AgentStreamEvent>> streamMessage(
        @PathVariable String sessionId,
        @Valid @RequestBody AgentMessageRequest request,
        @RequestHeader(value = "X-User-Id", required = false) String ownerKey
    ) {
        String identity = authIdentityService.requireIdentity(ownerKey);
        log.info("收到面试教练 Agent 请求: sessionId={}, goal={}, messageLength={}",
            sessionId, request.goal(), request.message().length());
        return agentService.stream(sessionId, request, identity)
            .map(event -> ServerSentEvent.<AgentStreamEvent>builder()
                // Keep the transport event name distinct from the generic SSE error event.
                .event("agent-" + event.type())
                .data(event)
                .build());
    }

    /**
     * Agent 是 SSE 接口，参数校验失败时也必须返回 SSE 事件，避免全局 JSON 异常响应与
     * text/event-stream 的 Accept 头冲突，导致浏览器只能看到 HTTP 400。
     */
    @ExceptionHandler({WebExchangeBindException.class, MethodArgumentNotValidException.class})
    public ResponseEntity<String> handleValidationException(
        Exception error
    ) {
        List<FieldError> fieldErrors;
        if (error instanceof WebExchangeBindException bindException) {
            fieldErrors = bindException.getFieldErrors();
        } else if (error instanceof MethodArgumentNotValidException argumentNotValidException) {
            fieldErrors = argumentNotValidException.getBindingResult().getFieldErrors();
        } else {
            fieldErrors = List.of();
        }

        String message = fieldErrors.stream()
            .map(fieldError -> fieldError.getDefaultMessage() == null
                ? "请求参数不合法"
                : fieldError.getDefaultMessage())
            .distinct()
            .collect(Collectors.joining("；"));
        String errorMessage = message.isBlank() ? "请求参数不合法" : message;
        try {
            String eventData = objectMapper.writeValueAsString(AgentStreamEvent.error(errorMessage));
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: agent-error\ndata: " + eventData + "\n\n");
        } catch (JsonProcessingException serializationError) {
            log.warn("无法序列化 Agent 参数校验 SSE 错误", serializationError);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body("event: agent-error\ndata: {\"type\":\"error\",\"message\":\"请求参数不合法\"}\n\n");
        }
    }
}
