package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentChatSessionEntity;
import interview.guide.modules.agent.model.AgentContextAssetEntity;
import interview.guide.modules.agent.model.AgentContextBindingResponse;
import interview.guide.modules.agent.model.AgentKnowledgeBaseOption;
import interview.guide.modules.agent.model.AgentSessionContextEntity;
import interview.guide.modules.agent.model.AttachAgentResourceRequest;
import interview.guide.modules.agent.repository.AgentChatSessionRepository;
import interview.guide.modules.agent.repository.AgentSessionContextRepository;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 校验并绑定 Agent 的简历、面试报告、上传素材和知识库资源。 */
@Service
public class AgentContextBindingService {

    private static final int MAX_ACTIVE_CONTEXTS = 8;

    private final AgentChatSessionRepository sessionRepository;
    private final AgentSessionContextRepository contextRepository;
    private final AgentContextAssetService assetService;
    private final AgentInterviewContextService interviewContextService;
    private final ResumeRepository resumeRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final AgentAuditService auditService;

    /** 保留无审计器的构造方式，兼容迁移期单元测试和旧内部调用方。 */
    public AgentContextBindingService(
        AgentChatSessionRepository sessionRepository,
        AgentSessionContextRepository contextRepository,
        AgentContextAssetService assetService,
        AgentInterviewContextService interviewContextService,
        ResumeRepository resumeRepository,
        KnowledgeBaseRepository knowledgeBaseRepository
    ) {
        this(sessionRepository, contextRepository, assetService, interviewContextService,
            resumeRepository, knowledgeBaseRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentContextBindingService(
        AgentChatSessionRepository sessionRepository,
        AgentSessionContextRepository contextRepository,
        AgentContextAssetService assetService,
        AgentInterviewContextService interviewContextService,
        ResumeRepository resumeRepository,
        KnowledgeBaseRepository knowledgeBaseRepository,
        AgentAuditService auditService
    ) {
        this.sessionRepository = sessionRepository;
        this.contextRepository = contextRepository;
        this.assetService = assetService;
        this.interviewContextService = interviewContextService;
        this.resumeRepository = resumeRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.auditService = auditService;
    }

    @Transactional
    public AgentContextBindingResponse attach(String agentSessionId, AttachAgentResourceRequest request, String ownerId) {
        AgentChatSessionEntity session = sessionRepository.findByAgentSessionIdForUpdate(agentSessionId)
            .filter(item -> ownerId.equals(item.getOwnerKey()))
            .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 会话"));
        String type = request.normalizedType();
        String resourceId = request.resourceId().trim();
        String scope = request.normalizedScope();
        if (!List.of("SESSION", "TURN").contains(scope)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上下文作用域只能是 SESSION 或 TURN");
        }
        String resourceName;
        switch (type) {
            case "INTERVIEW_REPORT" -> {
                interviewContextService.assertOwner(resourceId, ownerId);
                resourceName = "面试报告 " + resourceId;
            }
            case "RESUME" -> {
                Long id = parseLong(resourceId, "简历 ID");
                resourceName = resumeRepository.findByIdAndOwnerId(id, ownerId)
                    .map(item -> item.getOriginalFilename())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "简历不存在"));
            }
            case "FILE" -> {
                AgentContextAssetEntity asset = assetService.requireApproved(resourceId, ownerId);
                resourceName = asset.getOriginalFilename();
            }
            case "KNOWLEDGE_BASE" -> {
                Long id = parseLong(resourceId, "知识库 ID");
                KnowledgeBaseEntity kb = knowledgeBaseRepository.findAccessibleById(id, ownerId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
                resourceName = kb.getName();
            }
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上下文类型: " + type);
        }
        AgentSessionContextEntity existing = contextRepository
            .findFirstByAgentSessionIdAndContextTypeAndResourceIdAndActiveTrue(
                session.getAgentSessionId(), type, resourceId
            )
            .orElse(null);
        if (existing != null) {
            audit(ownerId, session.getInterviewSessionId(), agentSessionId,
                "CONTEXT_ATTACH_REUSED", resourceId, type);
            return AgentContextBindingResponse.from(existing);
        }
        if (contextRepository.countByAgentSessionIdAndActiveTrue(session.getAgentSessionId())
            >= MAX_ACTIVE_CONTEXTS) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "当前 Agent 会话最多同时使用 " + MAX_ACTIVE_CONTEXTS + " 份资料，请先移除不需要的上下文"
            );
        }
        AgentSessionContextEntity context = contextRepository.save(AgentSessionContextEntity.builder()
            .agentSessionId(session.getAgentSessionId())
            .contextType(type)
            .interviewSessionId("INTERVIEW_REPORT".equals(type) ? resourceId : null)
            .resourceId(resourceId)
            .resourceName(resourceName)
            .scope(scope)
            .sourceMessageId(request.sourceMessageIdAsLong())
            .active(true)
            .build());
        audit(ownerId, session.getInterviewSessionId(), agentSessionId,
            "CONTEXT_ATTACHED", resourceId, type);
        return AgentContextBindingResponse.from(context);
    }

    /**
     * 消费本轮临时上下文。该操作独立于模型调用事务，保证临时资料不会因刷新、重启或后续
     * 对话再次自动进入 Prompt；审计只记录资源引用，不记录正文。
     */
    @Transactional
    public void consumeTurnContexts(String agentSessionId, String ownerId) {
        AgentChatSessionEntity session = requireSession(agentSessionId, ownerId);
        int consumed = contextRepository.deactivateActiveByScope(
            session.getAgentSessionId(), "TURN", LocalDateTime.now()
        );
        if (consumed > 0) {
            audit(ownerId, session.getInterviewSessionId(), agentSessionId,
                "CONTEXT_TURN_CONSUMED", agentSessionId, String.valueOf(consumed));
        }
    }

    @Transactional
    public void detach(
        String agentSessionId, String contextType, String resourceId, String ownerId
    ) {
        AgentChatSessionEntity session = requireSession(agentSessionId, ownerId);
        String type = contextType == null ? "" : contextType.trim().toUpperCase(java.util.Locale.ROOT);
        String resource = resourceId == null ? "" : resourceId.trim();
        if (type.isBlank() || resource.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上下文类型和资源 ID 不能为空");
        }
        if (!List.of("INTERVIEW_REPORT", "RESUME", "FILE", "KNOWLEDGE_BASE").contains(type)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的上下文类型: " + type);
        }
        contextRepository.deactivateActiveResource(
            session.getAgentSessionId(), type, resource, LocalDateTime.now()
        );
        audit(ownerId, session.getInterviewSessionId(), agentSessionId,
            "CONTEXT_DETACHED", resource, type);
    }

    @Transactional(readOnly = true)
    public List<AgentContextBindingResponse> list(String agentSessionId, String ownerId) {
        requireSession(agentSessionId, ownerId);
        return contextRepository
            .findByAgentSessionIdAndOwnerKeyAndActiveTrueOrderByAttachedAtDesc(agentSessionId, ownerId).stream()
            .map(AgentContextBindingResponse::from)
            .toList();
    }

    /** 返回 Agent 可选择的知识库摘要，不把知识库正文直接注入前端或会话。 */
    @Transactional(readOnly = true)
    public List<AgentKnowledgeBaseOption> listKnowledgeBases(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户身份不能为空");
        }
        return knowledgeBaseRepository.findAccessibleByOwnerAndVectorStatus(
                ownerId, VectorStatus.COMPLETED, PageRequest.of(0, 50)
            ).stream()
            .map(AgentKnowledgeBaseOption::from)
            .toList();
    }

    @Transactional
    public void clear(String agentSessionId, String ownerId) {
        AgentChatSessionEntity session = requireSession(agentSessionId, ownerId);
        contextRepository.deactivateAllActive(agentSessionId, LocalDateTime.now());
        audit(ownerId, session.getInterviewSessionId(), agentSessionId,
            "CONTEXT_CLEARED", agentSessionId, "success");
    }

    private AgentChatSessionEntity requireSession(String agentSessionId, String ownerId) {
        return sessionRepository.findByAgentSessionId(agentSessionId)
            .filter(item -> ownerId.equals(item.getOwnerKey()))
            .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 会话"));
    }

    private Long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + "格式错误");
        }
    }

    private void audit(
        String ownerId, String interviewSessionId, String agentSessionId,
        String action, String resourceId, String result
    ) {
        if (auditService != null) {
            auditService.recordQuietly(
                ownerId, interviewSessionId, agentSessionId, null,
                action, "context-binding", resourceId, result
            );
        }
    }
}
