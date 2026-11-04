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
import org.springframework.ai.chat.messages.AssistantMessage;
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

    public AgentContextService(
        InterviewHistoryService interviewHistoryService,
        InterviewSkillService interviewSkillService,
        KnowledgeBaseQueryService knowledgeBaseQueryService,
        PromptSanitizer promptSanitizer,
        ObjectMapper objectMapper
    ) {
        this(
            interviewHistoryService, null, interviewSkillService, knowledgeBaseQueryService,
            promptSanitizer, objectMapper, null, null, null, null, null, null
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
        AgentContextSummaryService contextSummaryService
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
        String trustedOwner = ownerKey(request);
        if (trustedOwner == null || trustedOwner.isBlank()) {
            // memoryKey 属于请求体，不是认证凭证，不能作为跨用户资源的归属依据。
            // HTTP 入口会在进入上下文编排前写入 AuthUserContext；缺少身份时只能运行无私有资料的通用模式。
            return buildGeneralContext(request, effectiveHistory);
        }
        if (sessionId == null || sessionId.isBlank()) {
            return buildGeneralContext(request, effectiveHistory);
        }
        InterviewDetailDTO detail = loadInterviewDetailForOwner(sessionId, trustedOwner);
        String report = promptSanitizer.sanitizeForContext(serializeReport(detail));
        List<AgentSource> sources = new ArrayList<>();

        // 旧的“从面试详情进入 Agent”链路继续以报告为主，但允许输入区追加已审核的简历/文件资料。
        String attachedSupplement = loadAttachedContext(
            request, buildGeneralRetrievalQuery(request, effectiveHistory), effectiveHistory, sources, sessionId
        );
        if (!attachedSupplement.isBlank()) {
            report = truncate(report + "\n\n" + attachedSupplement, MAX_REPORT_CHARS);
        }

        String skillReference = loadSkillReference(detail, sources);
        String retrievalQuery = buildRetrievalQuery(detail, request);
        String ragContext = loadRagContext(detail, retrievalQuery, sources, buildRetrievalHistory(effectiveHistory));
        AgentMemorySnapshot memory = loadAndUpdateMemory(detail, request);

        return new AgentContext(
            report,
            detail.skillId(),
            skillReference,
            ragContext,
            retrievalQuery,
            List.copyOf(sources),
            memory.toPrompt(detail.skillId()),
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
        AgentMemorySnapshot memory = loadAndUpdateMemory(null, request);
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
            memory.toPrompt(null),
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
            .findByAgentSessionIdAndActiveTrueOrderByAttachedAtDesc(agentSessionId);
        if (contexts.isEmpty()) {
            return primaryInterviewSessionId == null
                ? "当前 Agent 会话未绑定任何资料。不要推断未提供的经历、面试表现或知识库结论。"
                : "";
        }
        StringBuilder report = new StringBuilder();
        for (AgentSessionContextEntity binding : contexts) {
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
                    report, resourceId, ownerKey(request), sources
                );
                case "RESUME" -> appendResumeContext(report, resourceId, request, sources);
                case "FILE" -> appendFileContext(report, resourceId, request, sources);
                case "KNOWLEDGE_BASE" -> appendKnowledgeContext(
                    report, resourceId, retrievalQuery, effectiveHistory, sources, ownerKey(request)
                );
                default -> log.warn("忽略未知 Agent 上下文类型: type={}, resourceId={}", type, resourceId);
            }
        }
        if (report.isEmpty()) {
            return primaryInterviewSessionId == null
                ? "已绑定资料但没有读取到可用内容。不要补写未提供的事实。"
                : "";
        }
        return truncate(report.toString(), MAX_REPORT_CHARS);
    }

    private void appendInterviewContext(
        StringBuilder report, String sessionId, String ownerId, List<AgentSource> sources
    ) {
        try {
            if (interviewHistoryService == null || interviewPersistenceService == null
                || ownerId == null || ownerId.isBlank()
                || interviewPersistenceService.findBySessionIdForOwner(sessionId, ownerId).isEmpty()) {
                return;
            }
            InterviewDetailDTO detail = loadInterviewDetailForOwner(sessionId, ownerId);
            report.append("\n[面试报告：").append(sessionId).append("]\n")
                .append(promptSanitizer.sanitizeForContext(serializeReport(detail))).append('\n');
            sources.add(new AgentSource("interview", "面试报告 " + sessionId, null, "INTERVIEW"));
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
                report.append("\n[简历：")
                    .append(promptSanitizer.sanitizeForContext(resume.getOriginalFilename()))
                    .append("]\n")
                    .append(promptSanitizer.sanitizeForContext(
                        resume.getResumeText() == null ? "" : resume.getResumeText()
                    )).append('\n');
                sources.add(new AgentSource(
                    "resume", promptSanitizer.sanitizeForContext(resume.getOriginalFilename()),
                    null, "RESUME-" + resourceId
                ));
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
                String safeFilename = promptSanitizer.sanitizeForContext(asset.getOriginalFilename());
                report.append("\n[已审核文件：").append(safeFilename).append("]\n")
                    .append(promptSanitizer.sanitizeForContext(
                        asset.getExtractedText() == null ? "" : asset.getExtractedText()
                    )).append('\n');
                sources.add(new AgentSource("file", safeFilename, null, "FILE-" + resourceId));
            });
    }

    private void appendKnowledgeContext(
        StringBuilder report,
        String resourceId,
        String retrievalQuery,
        List<AgentHistoryMessage> effectiveHistory,
        List<AgentSource> sources,
        String ownerId
    ) {
        if (knowledgeBaseRepository == null || retrievalQuery.isBlank()) return;
        parseLong(resourceId).flatMap(id -> knowledgeBaseRepository.findAccessibleById(id, ownerId))
            .ifPresent(kb -> {
            try {
                RagQueryExecution execution = knowledgeBaseQueryService.retrieveOnly(
                    List.of(kb.getId()), retrievalQuery, buildRetrievalHistory(effectiveHistory)
                );
                appendRetrievedDocs(report, execution, sources);
            } catch (RuntimeException error) {
                log.warn("通用 Agent 读取知识库失败: kbId={}", resourceId, error);
            }
        });
    }

    private void appendRetrievedDocs(
        StringBuilder report, RagQueryExecution execution, List<AgentSource> sources
    ) {
        if (execution == null || execution.retrievedDocs().isEmpty()) return;
        int index = 0;
        for (RagQueryExecution.RetrievedDoc document : execution.retrievedDocs()) {
            if (report.length() >= MAX_RAG_CONTEXT_CHARS) break;
            index++;
            String reference = "R" + index;
            String title = promptSanitizer.sanitizeForContext(describe(document.metadata()));
            String text = promptSanitizer.sanitizeForContext(
                document.text() == null ? "" : document.text()
            );
            int remaining = MAX_RAG_CONTEXT_CHARS - report.length();
            int budget = Math.max(0, remaining - title.length() - reference.length() - 24);
            if (budget <= 0) break;
            report.append("\n[").append(reference).append(" 来源：").append(title).append("]\n")
                .append(text, 0, Math.min(text.length(), budget)).append('\n');
            sources.add(new AgentSource("rag", title, document.score(), reference));
        }
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

    private AgentMemorySnapshot loadAndUpdateMemory(InterviewDetailDTO detail, AgentMessageRequest request) {
        String trustedOwner = ownerKey(request);
        if (agentMemoryService == null || trustedOwner == null || trustedOwner.isBlank()) {
            return AgentMemorySnapshot.empty(null);
        }
        return agentMemoryService.remember(
            trustedOwner,
            detail == null ? null : detail.skillId(),
            detail == null ? List.of() : extractWeaknesses(detail),
            request.goal()
        );
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

    private String loadSkillReference(InterviewDetailDTO detail, List<AgentSource> sources) {
        if (detail.skillId() == null || detail.skillId().isBlank()) {
            return "暂无匹配的 Skill references";
        }
        try {
            String reference = interviewSkillService.buildEvaluationReferenceSectionSafe(detail.skillId());
            if (reference != null && !reference.isBlank() && !"未配置 references。".equals(reference)) {
                sources.add(new AgentSource("skill", "Skill: " + detail.skillId(), null, "skill-reference"));
                return truncate(promptSanitizer.sanitizeForContext(reference), 8_000);
            }
        } catch (Exception error) {
            log.warn("Agent 加载 Skill references 失败: skillId={}, error={}", detail.skillId(),
                error.getClass().getSimpleName());
        }
        return "暂无匹配的 Skill references";
    }

    private String loadRagContext(
        InterviewDetailDTO detail,
        String retrievalQuery,
        List<AgentSource> sources,
        List<Message> history
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
            String label = "agent-retrieval-history-"
                + ("assistant".equals(item.role()) ? "assistant" : "user");
            int boundaryOverhead = promptSanitizer.wrapWithStableDelimiters(label, "").length();
            int contentBudget = remaining - boundaryOverhead;
            if (contentBudget <= 0) {
                break;
            }
            if (safeContent.length() > contentBudget) {
                safeContent = safeContent.substring(safeContent.length() - contentBudget);
            }
            String content = promptSanitizer.wrapWithStableDelimiters(
                label, safeContent
            );
            if (content.isBlank()) {
                continue;
            }
            Message message = "assistant".equals(item.role())
                ? new AssistantMessage(content)
                : new UserMessage(content);
            result.add(message);
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
