package interview.guide.modules.agent.service;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.agent.model.AgentMessageRequest;
import interview.guide.modules.agent.model.AgentHistoryMessage;
import interview.guide.modules.agent.model.AgentSource;
import interview.guide.modules.agent.model.AgentMemorySnapshot;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagQueryExecution;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextAssetStatus;
import interview.guide.modules.agent.model.AgentSessionContextEntity;
import interview.guide.modules.agent.repository.AgentContextAssetRepository;
import interview.guide.modules.agent.repository.AgentSessionContextRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.auth.AuthUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 面试教练 Agent 的只读上下文编排。
 *
 * <p>负责把已有的面试报告、Skill references、长期记忆和知识库检索结果整理成 Agent Loop
 * 所需的上下文；不改变既有面试和 RAG 会话。</p>
 */
@Slf4j
@Service
public class AgentContextService {
    private static final int MAX_REPORT_CHARS = 14_000;
    private static final int MAX_RAG_CONTEXT_CHARS = 8_000;
    private static final String RAG_TRUNCATION_NOTICE = "[该来源片段已截断]";
    private static final int MAX_RETRIEVAL_QUERY_CHARS = 1_800;
    private static final int MAX_RETRIEVAL_HISTORY_MESSAGES = 6;
    private static final int MAX_RETRIEVAL_HISTORY_CHARS = 2_400;
    private static final int MAX_WEAK_ANSWERS = 4;
    private static final int WEAK_SCORE_THRESHOLD = 70;

    private final InterviewHistoryService interviewHistoryService;
    private final InterviewPersistenceService interviewPersistenceService;
    private final InterviewSkillService interviewSkillService;
    private final KnowledgeBaseQueryService knowledgeBaseQueryService;
    private final PromptSanitizer promptSanitizer;
    private final ObjectMapper objectMapper;
    private final AgentMemoryService agentMemoryService;
    private final AgentSessionContextRepository sessionContextRepository;
    private final AgentContextAssetRepository contextAssetRepository;
    private final ResumeRepository resumeRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AgentContextSummaryService contextSummaryService;
    private final AgentAuditService agentAuditService;
    private AgentContextBindingService contextBindingService;

    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper
    ) {
        this(
            interviewHistoryService, null, interviewSkillService, knowledgeBaseQueryService,
            promptSanitizer, objectMapper, null, null, null, null, null, null, null
        );
    }

    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewPersistenceService interviewPersistenceService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper,
        AgentMemoryService agentMemoryService,
        AgentSessionContextRepository sessionContextRepository,
        AgentContextAssetRepository contextAssetRepository,
        ResumeRepository resumeRepository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        AgentContextSummaryService contextSummaryService
    ) {
        this(
            interviewHistoryService, interviewPersistenceService, interviewSkillService,
            knowledgeBaseQueryService, promptSanitizer, objectMapper, agentMemoryService,
            sessionContextRepository, contextAssetRepository, resumeRepository,
            knowledgeBaseRepository, contextSummaryService, null
        );
    }

    @Autowired
    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewPersistenceService interviewPersistenceService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper,
        AgentMemoryService agentMemoryService,
        AgentSessionContextRepository sessionContextRepository,
        AgentContextAssetRepository contextAssetRepository,
        ResumeRepository resumeRepository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        AgentContextSummaryService contextSummaryService,
        AgentAuditService agentAuditService
    ) {
        this.interviewHistoryService = interviewHistoryService;
        this.interviewPersistenceService = interviewPersistenceService;
        this.interviewSkillService = interviewSkillService;
        this.knowledgeBaseQueryService = knowledgeBaseQueryService;
        this.promptSanitizer = promptSanitizer;
        this.objectMapper = objectMapper;
        this.agentMemoryService = agentMemoryService;
        this.sessionContextRepository = sessionContextRepository;
        this.contextAssetRepository = contextAssetRepository;
        this.resumeRepository = resumeRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.contextSummaryService = contextSummaryService;
        this.agentAuditService = agentAuditService;
    }

    /** 可选注入，保留无 Spring 容器的上下文单元测试构造方式。 */
    @Autowired(required = false)
    void setContextBindingService(AgentContextBindingService contextBindingService) {
        this.contextBindingService = contextBindingService;
    }

    /** 兼容当前单元测试和迁移期内部调用方。 */
    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper,
        AgentMemoryService agentMemoryService
    ) {
        this(
            interviewHistoryService, null, interviewSkillService, knowledgeBaseQueryService,
            promptSanitizer, objectMapper, agentMemoryService, null, null, null, null, null
        );
    }

    public AgentContext build(
        String sessionId,
        AgentMessageRequest request,
        List<AgentHistoryMessage> effectiveHistory
    ) {
        return buildForDecision(sessionId, request, effectiveHistory, null);
    }

    /**
     * 按本轮意图选择主报告、Skill 和 RAG 上下文。
     *
     * <p>旧的三参数入口保留迁移期行为；Agent 主流程会传入分类结果，避免普通问答
     * 因旧会话目标或面试会话 ID 触发完整复盘上下文。显式绑定的会话资料仍然按用户
     * 选择读取，不会因为本轮是 GENERAL 就被静默丢弃。</p>
     */
    public AgentContext buildForDecision(
        String sessionId,
        AgentMessageRequest request,
        List<AgentHistoryMessage> effectiveHistory,
        AgentIntentRouter.Decision intentDecision
    ) {
        // 面试会话 ID 只代表数据归属，不代表本轮自动授权读取报告。
        // 普通问题由 InterviewAgentService 归一化为 general；这里再次防守，
        // 避免旧调用方直接传入 sessionId 时重新引入“自动复盘”。
        if (request == null || "general".equalsIgnoreCase(request.goal())) {
            return buildGeneralContext(request, effectiveHistory);
        }
        String trustedOwner = ownerKey(request);
        if (trustedOwner == null || trustedOwner.isBlank()) {
            // memoryKey 属于请求体，不是认证凭证，不能作为跨用户资源的归属依据。
            // HTTP 入口会在进入上下文编排前写入 AuthUserContext；缺少身份时只能运行无私有资料的通用模式。
            return buildGeneralContext(request, effectiveHistory);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return buildGeneralContext(request, effectiveHistory);
        }
        ContextSelection selection = ContextSelection.from(intentDecision, request);
        if (!selection.includeInterviewReport()) {
            return buildGeneralContext(request, effectiveHistory);
        }
        InterviewDetailDTO detail = loadInterviewDetailForOwner(sessionId, trustedOwner);
        String rawReport = serializeReport(detail);
        auditContextInjection(
            trustedOwner, sessionId, request == null ? null : request.agentSessionId(),
            "interview-report", sessionId, rawReport
        );
        String report = promptSanitizer.sanitizeForContext(rawReport);
        List<AgentSource> sources = new ArrayList<>();

        // 旧的“从面试详情进入 Agent”链路继续以报告为主，但允许输入区追加已审核的简历/文件资料。
        String attachedSupplement = loadAttachedContext(
            request, buildGeneralRetrievalQuery(request, effectiveHistory), effectiveHistory, sources, sessionId
        );
        if (!attachedSupplement.isBlank()) {
            report = truncateToBudget(report + "\n\n" + attachedSupplement, MAX_REPORT_CHARS);
        }

        String safeSkillId = sanitizeSkillId(detail.skillId());
        String skillReference = selection.includeSkill()
            ? loadSkillReference(safeSkillId, sources, trustedOwner,
                request == null ? null : request.agentSessionId(), sessionId)
            : "暂无匹配的 Skill references";
        String retrievalQuery = buildRetrievalQuery(detail, request);
        String ragContext = selection.includeRag()
            ? loadRagContext(
                detail, retrievalQuery, sources, buildRetrievalHistory(effectiveHistory),
                trustedOwner, request == null ? null : request.agentSessionId(), sessionId
            )
            : "暂无知识库检索上下文";
        AgentMemorySnapshot memory = loadMemory(request);

        return new AgentContext(
            report,
            safeSkillId,
            skillReference,
            ragContext,
            retrievalQuery,
            List.copyOf(sources),
            promptSanitizer.sanitizeForContext(memory.toPrompt(safeSkillId)),
            extractWeaknesses(detail),
            loadSessionSummary(request)
        );
    }

    /**
     * 通用 Agent 不自动加载任何面试报告；面试上下文由后续的 attach 工具按需提供。
     */
    private AgentContext buildGeneralContext(
        AgentMessageRequest request, List<AgentHistoryMessage> effectiveHistory
    ) {
        AgentMemorySnapshot memory = loadMemory(request);
        String retrievalQuery = buildGeneralRetrievalQuery(request, effectiveHistory);
        List<AgentSource> sources = new ArrayList<>();
        String report = loadAttachedContext(request, retrievalQuery, effectiveHistory, sources, null);
        return new AgentContext(
            report,
            "",
            "暂无匹配的 Skill references",
            "暂无知识库检索上下文",
            retrievalQuery,
            List.copyOf(sources),
            promptSanitizer.sanitizeForContext(memory.toPrompt(null)),
            List.of(),
            loadSessionSummary(request)
        );
    }

    /**
     * 读取会话已审核的上下文资源。每类资源都只作为资料，不提升为指令；资源本身的
     * prompt 注入风险会在最终 Prompt 的固定边界和 PromptSanitizer 中再次处理。
     */
    private String loadAttachedContext(
        AgentMessageRequest request,
        String retrievalQuery,
        List<AgentHistoryMessage> effectiveHistory,
        List<AgentSource> sources,
        String primaryInterviewSessionId
    ) {
        String agentSessionId = request == null ? null : request.agentSessionId();
        if (ownerKey(request) == null || ownerKey(request).isBlank()
            || sessionContextRepository == null || agentSessionId == null || agentSessionId.isBlank()) {
            return primaryInterviewSessionId == null
                ? "当前 Agent 会话未绑定任何资料。不要推断未提供的经历、面试表现或知识库结论。"
                : "";
        }
        List<AgentSessionContextEntity> contexts = sessionContextRepository
            .findByAgentSessionIdAndOwnerKeyAndActiveTrueOrderByAttachedAtDesc(
                agentSessionId, ownerKey(request)
            );
        if (contexts.isEmpty()) {
            return primaryInterviewSessionId == null
                ? "当前 Agent 会话未绑定任何资料。不要推断未提供的经历、面试表现或知识库结论。"
                : "";
        }
        StringBuilder report = new StringBuilder();
        for (AgentSessionContextEntity binding : contexts) {
            if (report.length() >= MAX_REPORT_CHARS) {
                break;
            }
            String type = binding.getContextType();
            String resourceId = binding.getResourceId();
            if ((resourceId == null || resourceId.isBlank()) && "INTERVIEW_REPORT".equals(type)) {
                resourceId = binding.getInterviewSessionId();
            }
            if ("INTERVIEW_REPORT".equals(type)
                && primaryInterviewSessionId != null
                && primaryInterviewSessionId.equals(resourceId)) {
                continue;
            }
            if (resourceId == null || resourceId.isBlank()) continue;
            switch (type) {
                case "INTERVIEW_REPORT" -> appendInterviewContext(
                    report, resourceId, ownerKey(request), agentSessionId, sources
                );
                case "RESUME" -> appendResumeContext(report, resourceId, request, sources);
                case "FILE" -> appendFileContext(report, resourceId, request, sources);
                case "KNOWLEDGE_BASE" -> appendKnowledgeContext(
                    report, resourceId, retrievalQuery, effectiveHistory, sources, ownerKey(request), agentSessionId
                );
                default -> log.warn("忽略未知 Agent 上下文类型: type={}, resourceId={}", type, resourceId);
            }
        }
        if (report.isEmpty()) {
            consumeTurnContexts(agentSessionId, ownerKey(request));
            return primaryInterviewSessionId == null
                ? "已绑定资料但没有读取到可用内容。不要补写未提供的事实。"
                : "";
        }
        consumeTurnContexts(agentSessionId, ownerKey(request));
        return report.toString();
    }

    private void consumeTurnContexts(String agentSessionId, String ownerId) {
        if (contextBindingService == null || agentSessionId == null || agentSessionId.isBlank()
            || ownerId == null || ownerId.isBlank()) {
            return;
        }
        contextBindingService.consumeTurnContexts(agentSessionId, ownerId);
    }

    private void appendInterviewContext(
        StringBuilder report, String sessionId, String ownerId, String agentSessionId,
        List<AgentSource> sources
    ) {
        try {
            if (interviewHistoryService == null || interviewPersistenceService == null
                || ownerId == null || ownerId.isBlank()
                || interviewPersistenceService.findBySessionIdForOwner(sessionId, ownerId).isEmpty()) {
                return;
            }
            InterviewDetailDTO detail = loadInterviewDetailForOwner(sessionId, ownerId);
            String rawReport = serializeReport(detail);
            auditContextInjection(ownerId, sessionId, agentSessionId,
                "interview-report", sessionId, rawReport);
            String safeSessionLabel = safeContextLabel(sessionId, "面试报告");
            boolean appended = appendBounded(report,
                "\n[面试报告：" + safeSessionLabel + "]\n"
                    + promptSanitizer.sanitizeForContext(rawReport) + '\n');
            if (appended) {
                sources.add(new AgentSource("interview", "面试报告 " + safeSessionLabel, null, "INTERVIEW"));
            }
        } catch (RuntimeException error) {
            log.warn("通用 Agent 读取面试报告失败: sessionId={}", sessionId, error);
        }
    }

    private void appendResumeContext(
        StringBuilder report, String resourceId, AgentMessageRequest request, List<AgentSource> sources
    ) {
        if (resumeRepository == null) return;
        parseLong(resourceId).flatMap(id -> resumeRepository.findByIdAndOwnerId(id, ownerKey(request)))
            .ifPresent(resume -> {
                String resumeText = resume.getResumeText() == null ? "" : resume.getResumeText();
                auditContextInjection(
                    ownerKey(request), null, request == null ? null : request.agentSessionId(),
                    "resume", resourceId, resumeText
                );
                String safeFilename = promptSanitizer.sanitizeForContext(resume.getOriginalFilename());
                String safeResumeText = truncateToBudget(
                    promptSanitizer.sanitizeForContext(resumeText), MAX_REPORT_CHARS
                );
                boolean appended = appendBounded(report,
                    "\n[简历：" + (safeFilename == null ? "未命名简历" : safeFilename) + "]\n"
                        + safeResumeText + '\n');
                if (appended) {
                    sources.add(new AgentSource(
                        "resume", safeFilename == null ? "未命名简历" : safeFilename,
                        null, "RESUME-" + resourceId
                    ));
                }
            });
    }

    private void appendFileContext(
        StringBuilder report, String resourceId, AgentMessageRequest request, List<AgentSource> sources
    ) {
        if (contextAssetRepository == null) return;
        contextAssetRepository.findByAssetIdAndOwnerId(resourceId, ownerKey(request))
            .filter(asset -> asset.getReviewStatus() == AgentContextAssetStatus.APPROVED_FOR_CONTEXT
                || asset.getReviewStatus() == AgentContextAssetStatus.KB_IMPORT_COMPLETED)
            .filter(asset -> asset.getExpiresAt() == null
                || asset.getExpiresAt().isAfter(LocalDateTime.now()))
            .ifPresent(asset -> {
                String extractedText = asset.getExtractedText() == null ? "" : asset.getExtractedText();
                auditContextInjection(
                    ownerKey(request), null, request == null ? null : request.agentSessionId(),
                    "context-asset", resourceId, extractedText
                );
                String safeFilename = promptSanitizer.sanitizeForContext(asset.getOriginalFilename());
                String safeExtractedText = truncateToBudget(
                    promptSanitizer.sanitizeForContext(extractedText), MAX_REPORT_CHARS
                );
                boolean appended = appendBounded(report,
                    "\n[已审核文件：" + (safeFilename == null ? "未命名文件" : safeFilename) + "]\n"
                        + safeExtractedText + '\n');
                if (appended) {
                    sources.add(new AgentSource(
                        "file", safeFilename == null ? "未命名文件" : safeFilename,
                        null, "FILE-" + resourceId
                    ));
                }
            });
    }

    private void appendKnowledgeContext(
        StringBuilder report,
        String resourceId,
        String retrievalQuery,
        List<AgentHistoryMessage> effectiveHistory,
        List<AgentSource> sources,
        String ownerId,
        String agentSessionId
    ) {
        if (knowledgeBaseRepository == null || retrievalQuery.isBlank()) return;
        parseLong(resourceId).flatMap(id -> knowledgeBaseRepository.findAccessibleById(id, ownerId))
            .ifPresent(kb -> {
            try {
                RagQueryExecution execution = knowledgeBaseQueryService.retrieveOnly(
                    List.of(kb.getId()), retrievalQuery, buildRetrievalHistory(effectiveHistory)
                );
                appendRetrievedDocs(report, execution, sources, ownerId, agentSessionId, null, resourceId);
            } catch (RuntimeException error) {
                log.warn("通用 Agent 读取知识库失败: kbId={}", resourceId, error);
            }
        });
    }

    private void appendRetrievedDocs(
        StringBuilder report, RagQueryExecution execution, List<AgentSource> sources,
        String ownerId, String agentSessionId, String interviewSessionId, String knowledgeBaseId
    ) {
        if (execution == null || execution.retrievedDocs().isEmpty()) return;
        int index = 0;
        for (RagQueryExecution.RetrievedDoc document : execution.retrievedDocs()) {
            if (report.length() >= MAX_RAG_CONTEXT_CHARS) break;
            index++;
            String reference = "R" + index;
            String title = promptSanitizer.sanitizeForContext(describe(document.metadata()));
            title = title == null || title.isBlank() ? "知识库片段" : truncate(title, 256);
            String text = promptSanitizer.sanitizeForContext(
                document.text() == null ? "" : document.text()
            );
            text = text == null ? "" : truncateToBudget(text, MAX_RAG_CONTEXT_CHARS);
            String rawText = document.text() == null ? "" : document.text();
            auditContextInjection(
                ownerId, interviewSessionId, agentSessionId,
                "knowledge-base", knowledgeBaseId + ":R" + index, rawText
            );
            int remaining = MAX_RAG_CONTEXT_CHARS - report.length();
            int budget = Math.max(0, remaining - title.length() - reference.length() - 24);
            if (budget <= 0) break;
            report.append("\n[").append(reference).append(" 来源：").append(title).append("]\n")
                .append(text, 0, Math.min(text.length(), budget)).append('\n');
            sources.add(new AgentSource("rag", title, document.score(), reference));
        }
    }

    /** 绑定资料必须边拼装边限额，不能先把多份大文件全部累积后再截断。 */
    private boolean appendBounded(StringBuilder target, String value) {
        if (target == null || value == null || value.isEmpty()
            || target.length() >= MAX_REPORT_CHARS) {
            return false;
        }
        int remaining = MAX_REPORT_CHARS - target.length();
        target.append(value, 0, Math.min(remaining, value.length()));
        return true;
    }

    private java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return java.util.Optional.empty();
        }
    }

    private String ownerKey(AgentMessageRequest request) {
        return AuthUserContext.currentUserId().orElse(null);
    }

    private record ContextSelection(
        boolean includeInterviewReport,
        boolean includeSkill,
        boolean includeRag
    ) {
        private static ContextSelection from(
            AgentIntentRouter.Decision decision, AgentMessageRequest request
        ) {
            if (decision == null) {
                boolean legacyReview = request != null
                    && !"general".equalsIgnoreCase(request.goal());
                return new ContextSelection(legacyReview, legacyReview, legacyReview);
            }
            java.util.Set<AgentIntentRouter.Capability> capabilities = decision.capabilities();
            return new ContextSelection(
                decision.route() == AgentIntentRouter.Route.INTERVIEW_REVIEW
                    || capabilities.contains(AgentIntentRouter.Capability.INTERVIEW_REPORT),
                capabilities.contains(AgentIntentRouter.Capability.SKILL),
                capabilities.contains(AgentIntentRouter.Capability.RAG)
            );
        }
    }

    /**
     * Agent 上下文读取必须显式携带服务端 owner。简化构造器只供迁移期单元测试和旧内部
     * 调用方使用，生产 Bean 始终注入 persistence service 并走严格归属查询。
     */
    private InterviewDetailDTO loadInterviewDetailForOwner(String sessionId, String ownerId) {
        if (interviewPersistenceService == null) {
            if (ownerId == null || !ownerId.equals(AuthUserContext.currentUserId().orElse(null))) {
                throw new SecurityException("Agent 报告读取缺少可信用户身份");
            }
            return interviewHistoryService.getInterviewDetail(sessionId);
        }
        return interviewHistoryService.getInterviewDetailForOwner(sessionId, ownerId);
    }

    private String loadSessionSummary(AgentMessageRequest request) {
        if (contextSummaryService == null || request == null) {
            return "暂无会话摘要";
        }
        return contextSummaryService.loadForPrompt(
            request.agentSessionId(), ownerKey(request)
        );
    }

    /**
     * 上下文编排只负责读取长期记忆，不能因为用户发起一次普通对话或查看一份报告
     * 就隐式修改跨会话数据。记忆写入统一通过明确的 Agent 动作草稿和用户确认完成。
     */
    private AgentMemorySnapshot loadMemory(AgentMessageRequest request) {
        String trustedOwner = ownerKey(request);
        if (agentMemoryService == null || trustedOwner == null || trustedOwner.isBlank()) {
            return AgentMemorySnapshot.empty(null);
        }
        return agentMemoryService.load(trustedOwner);
    }

    private String buildGeneralRetrievalQuery(
        AgentMessageRequest request, List<AgentHistoryMessage> effectiveHistory
    ) {
        StringBuilder query = new StringBuilder();
        query.append("目标：").append(promptSanitizer.sanitizeForContext(request.goal())).append('\n');
        query.append("用户问题：").append(promptSanitizer.sanitizeForContext(request.message())).append('\n');
        return truncate(query.toString(), MAX_RETRIEVAL_QUERY_CHARS);
    }

    private List<String> extractWeaknesses(InterviewDetailDTO detail) {
        List<String> weaknesses = new ArrayList<>();
        if (detail.improvements() != null) {
            detail.improvements().stream()
                .filter(item -> item != null && !item.isBlank())
                .limit(MAX_WEAK_ANSWERS)
                .forEach(item -> weaknesses.add(promptSanitizer.sanitizeForContext(item)));
        }
        if (detail.answers() != null) {
            detail.answers().stream()
                .filter(answer -> answer.score() != null && answer.score() < WEAK_SCORE_THRESHOLD)
                .limit(MAX_WEAK_ANSWERS)
                .forEach(answer -> weaknesses.add(
                    promptSanitizer.sanitizeForContext(answer.category() + "：" + answer.question())
                ));
        }
        return weaknesses.stream().filter(item -> item != null && !item.isBlank()).distinct().limit(8).toList();
    }

    private String loadSkillReference(
        String skillId,
        List<AgentSource> sources,
        String ownerId,
        String agentSessionId,
        String interviewSessionId
    ) {
        if (skillId == null || skillId.isBlank()) {
            return "暂无匹配的 Skill references";
        }
        try {
            String reference = interviewSkillService.buildEvaluationReferenceSectionSafe(skillId);
            if (reference != null && !reference.isBlank() && !"未配置 references。".equals(reference)) {
                auditContextInjection(
                    ownerId, interviewSessionId, agentSessionId,
                    "skill-reference", skillId, reference
                );
                sources.add(new AgentSource("skill", "Skill: " + skillId, null, "skill-reference"));
                return truncate(promptSanitizer.sanitizeForContext(reference), 8_000);
            }
        } catch (Exception error) {
            log.warn("Agent 加载 Skill references 失败: skillId={}, error={}", skillId,
                error.getClass().getSimpleName());
        }
        return "暂无匹配的 Skill references";
    }

    /** Skill 标识会进入 Loop 观察和来源展示，也必须按不可信元数据处理。 */
    private String sanitizeSkillId(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String safe = promptSanitizer.sanitizeForContext(value);
        if (safe == null || safe.isBlank()) {
            return "";
        }
        safe = safe.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").trim();
        return truncate(safe, 128);
    }

    /** 资源标识可能来自持久化绑定，进入模型资料标签前不能保留原始控制文本。 */
    private String safeContextLabel(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String safe = promptSanitizer.sanitizeForContext(value);
        if (safe == null || safe.isBlank()) {
            return fallback;
        }
        return truncate(safe.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").trim(), 128);
    }

    private String loadRagContext(
        InterviewDetailDTO detail,
        String retrievalQuery,
        List<AgentSource> sources,
        List<Message> history,
        String ownerId,
        String agentSessionId,
        String interviewSessionId
    ) {
        if (detail.knowledgeBaseId() == null || retrievalQuery.isBlank()) {
            return "暂无知识库检索上下文";
        }

        try {
            RagQueryExecution execution = knowledgeBaseQueryService.retrieveOnly(
                List.of(detail.knowledgeBaseId()), retrievalQuery, history
            );
            if (execution.retrievedDocs().isEmpty()) {
                return "本次未检索到达到阈值的知识库片段";
            }

            StringBuilder context = new StringBuilder();
            for (int index = 0; index < execution.retrievedDocs().size(); index++) {
                RagQueryExecution.RetrievedDoc document = execution.retrievedDocs().get(index);
                String reference = "R" + (index + 1);
                String title = promptSanitizer.sanitizeForContext(describe(document.metadata()));
                String text = promptSanitizer.sanitizeForContext(
                    document.text() == null ? "" : document.text()
                );
                String rawText = document.text() == null ? "" : document.text();
                auditContextInjection(
                    ownerId, interviewSessionId, agentSessionId,
                    "knowledge-base", detail.knowledgeBaseId() + ":" + reference, rawText
                );
                String prefix = "[" + reference + "] 来源：" + title + '\n';
                int remaining = MAX_RAG_CONTEXT_CHARS - context.length();
                if (remaining <= prefix.length()) {
                    break;
                }
                int textBudget = remaining - prefix.length() - 2;
                boolean needsTruncation = text.length() > textBudget;
                if (needsTruncation) {
                    textBudget -= RAG_TRUNCATION_NOTICE.length() + 1;
                }
                String includedText = text.length() <= textBudget
                    ? text
                    : text.substring(0, Math.max(0, textBudget));
                if (includedText.isBlank()) {
                    break;
                }
                sources.add(new AgentSource("rag", title, document.score(), reference));
                context.append(prefix).append(includedText).append("\n\n");
                if (includedText.length() < text.length()) {
                    context.append(RAG_TRUNCATION_NOTICE).append('\n');
                    break;
                }
            }
            return truncate(context.toString(), MAX_RAG_CONTEXT_CHARS);
        } catch (Exception error) {
            log.warn("Agent RAG 检索失败，降级为面试报告上下文: sessionId={}, kbId={}, error={}",
                detail.sessionId(), detail.knowledgeBaseId(), error.getClass().getSimpleName());
            return "知识库检索暂时不可用，本次回答不要声称使用了知识库依据";
        }
    }

    private void auditContextInjection(
        String ownerId,
        String interviewSessionId,
        String agentSessionId,
        String resourceType,
        String resourceId,
        String rawText
    ) {
        if (agentAuditService == null || !promptSanitizer.detectInjectionAttempt(rawText)) {
            return;
        }
        agentAuditService.recordContextInjectionDetected(
            ownerId, interviewSessionId, agentSessionId, resourceType, resourceId
        );
    }

    /**
     * RAG 查询改写只需要最近几轮对话，不复用完整 Agent 历史，避免检索请求膨胀。
     * 每条消息先净化，再按总字符预算从最近消息向前保留。
     */
    private List<Message> buildRetrievalHistory(List<AgentHistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>();
        int remaining = MAX_RETRIEVAL_HISTORY_CHARS;
        int start = Math.max(0, history.size() - MAX_RETRIEVAL_HISTORY_MESSAGES);
        for (int index = history.size() - 1; index >= start && remaining > 0; index--) {
            AgentHistoryMessage item = history.get(index);
            if (item == null || item.role() == null || item.content() == null) {
                continue;
            }
            String safeContent = promptSanitizer.sanitizeForContext(item.content().trim());
            String label = "agent-retrieval-history-record";
            int boundaryOverhead = promptSanitizer.wrapWithStableDelimiters(label, "").length();
            String roleLabel = "历史消息角色："
                + ("assistant".equals(item.role()) ? "assistant" : "user") + "\n";
            int contentBudget = remaining - boundaryOverhead - roleLabel.length();
            if (contentBudget <= 0) {
                break;
            }
            if (safeContent.length() > contentBudget) {
                safeContent = safeContent.substring(safeContent.length() - contentBudget);
            }
            String content = roleLabel + safeContent;
            content = promptSanitizer.wrapWithStableDelimiters(label, content);
            if (content.isBlank()) {
                continue;
            }
            // RAG query rewrite 只需要对话语义，不需要把历史 assistant 输出提升为
            // assistant 消息层级。统一使用 UserMessage，防止持久化历史伪造角色或指令。
            result.add(new UserMessage(content));
            remaining -= content.length();
        }
        Collections.reverse(result);
        return List.copyOf(result);
    }

    private String buildRetrievalQuery(InterviewDetailDTO detail, AgentMessageRequest request) {
        StringBuilder query = new StringBuilder();
        query.append("目标：").append(promptSanitizer.sanitizeForContext(request.goal())).append('\n');
        query.append("用户问题：").append(promptSanitizer.sanitizeForContext(request.message())).append('\n');

        if (detail.improvements() != null && !detail.improvements().isEmpty()) {
            query.append("报告改进项：");
            detail.improvements().stream()
                .limit(MAX_WEAK_ANSWERS)
                .forEach(item -> query.append(promptSanitizer.sanitizeForContext(item)).append("；"));
            query.append('\n');
        }

        if (detail.answers() != null) {
            detail.answers().stream()
                .filter(answer -> answer.score() != null && answer.score() < WEAK_SCORE_THRESHOLD)
                .limit(MAX_WEAK_ANSWERS)
                .forEach(answer -> query.append("薄弱回答（")
                    .append(promptSanitizer.sanitizeForContext(answer.category())).append("，")
                    .append(answer.score()).append("分）：")
                    .append(promptSanitizer.sanitizeForContext(answer.question())).append('；'));
        }
        return truncate(query.toString(), MAX_RETRIEVAL_QUERY_CHARS);
    }

    private String serializeReport(InterviewDetailDTO detail) {
        try {
            return truncate(objectMapper.writeValueAsString(detail), MAX_REPORT_CHARS);
        } catch (JacksonException error) {
            throw new IllegalStateException("面试报告整理失败", error);
        }
    }

    private String describe(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "知识库片段";
        }
        String source = text(metadata.get("source_name"));
        if (source.isBlank()) {
            source = text(metadata.get("source_filename"));
        }
        return source.isBlank() ? "知识库片段" : source;
    }

    private String text(Object value) {
        return value == null ? "" : value.toString().replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "\n[内容已截断]";
    }

    private String truncateToBudget(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        String marker = "\n[内容已截断]";
        int contentChars = Math.max(0, maxChars - marker.length());
        return value.substring(0, contentChars) + marker;
    }

    public record AgentContext(
        String interviewReport,
        String skillId,
        String skillReference,
        String ragContext,
        String retrievalQuery,
        List<AgentSource> sources,
        String longTermMemory,
        List<String> weaknesses,
        String sessionSummary
    ) {
        public AgentContext(
            String interviewReport,
            String skillId,
            String skillReference,
            String ragContext,
            String retrievalQuery,
            List<AgentSource> sources
        ) {
            this(interviewReport, skillId, skillReference, ragContext, retrievalQuery, sources,
                "暂无长期记忆", List.of(), "暂无会话摘要");
        }

        /** 兼容 Loop 工具和迁移期调用方传入长期记忆、薄弱点的旧构造方式。 */
        public AgentContext(
            String interviewReport,
            String skillId,
            String skillReference,
            String ragContext,
            String retrievalQuery,
            List<AgentSource> sources,
            String longTermMemory,
            List<String> weaknesses
        ) {
            this(interviewReport, skillId, skillReference, ragContext, retrievalQuery, sources,
                longTermMemory, weaknesses, "暂无会话摘要");
        }

        public AgentContext {
            longTermMemory = longTermMemory == null ? "暂无长期记忆" : longTermMemory;
            weaknesses = weaknesses == null ? List.of() : List.copyOf(weaknesses);
            sources = sources == null ? List.of() : List.copyOf(sources);
            sessionSummary = sessionSummary == null ? "暂无会话摘要" : sessionSummary;
        }
    }
}
