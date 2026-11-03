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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 校验并绑定 Agent 的简历、面试报告、上传素材和知识库资源。 */
@Service
@RequiredArgsConstructor
public class AgentContextBindingService {

    private final AgentChatSessionRepository sessionRepository;
    private final AgentSessionContextRepository contextRepository;
    private final AgentContextAssetService assetService;
    private final AgentInterviewContextService interviewContextService;
    private final ResumeRepository resumeRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    @Transactional
    public AgentContextBindingResponse attach(String agentSessionId, AttachAgentResourceRequest request, String ownerId) {
        AgentChatSessionEntity session = sessionRepository.findByAgentSessionId(agentSessionId)
            .filter(item -> ownerId.equals(item.getOwnerKey()))
            .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "无权访问该 Agent 会话"));
        String type = request.normalizedType();
        String resourceId = request.resourceId().trim();
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
        contextRepository.deactivateAllActive(session.getAgentSessionId(), LocalDateTime.now());
        AgentSessionContextEntity context = contextRepository.save(AgentSessionContextEntity.builder()
            .agentSessionId(session.getAgentSessionId())
            .contextType(type)
            .interviewSessionId("INTERVIEW_REPORT".equals(type) ? resourceId : null)
            .resourceId(resourceId)
            .resourceName(resourceName)
            .scope(request.normalizedScope())
            .sourceMessageId(request.sourceMessageIdAsLong())
            .active(true)
            .build());
        return AgentContextBindingResponse.from(context);
    }

    @Transactional(readOnly = true)
    public List<AgentContextBindingResponse> list(String agentSessionId, String ownerId) {
        requireSession(agentSessionId, ownerId);
        return contextRepository.findByAgentSessionIdAndActiveTrueOrderByAttachedAtDesc(agentSessionId).stream()
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
                ownerId, VectorStatus.COMPLETED
            ).stream()
            .limit(50)
            .map(AgentKnowledgeBaseOption::from)
            .toList();
    }

    @Transactional
    public void clear(String agentSessionId, String ownerId) {
        requireSession(agentSessionId, ownerId);
        contextRepository.deactivateAllActive(agentSessionId, LocalDateTime.now());
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
}
