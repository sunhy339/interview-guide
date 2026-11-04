package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentInterviewSearchResult;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Agent 面试上下文查询服务。
 *
 * <p>搜索阶段只返回元数据，报告正文必须在用户明确绑定后再由上下文服务加载。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentInterviewContextService {

    private final InterviewSessionRepository interviewSessionRepository;

    public List<AgentInterviewSearchResult> search(String ownerKey, String query, int limit) {
        String owner = requireOwner(ownerKey);
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String effectiveOwner = AuthUserContext.currentUserId().orElse(owner);
        int safeLimit = Math.max(1, Math.min(20, limit));
        PageRequest pageRequest = PageRequest.of(0, safeLimit);
        Page<InterviewSessionEntity> page;
        if (normalizedQuery.isBlank()) {
            page = interviewSessionRepository.findAllByOwnerIdOrderByCreatedAtDesc(
                effectiveOwner, pageRequest
            );
        } else {
            InterviewSessionEntity.SessionStatus status = parseStatus(normalizedQuery);
            page = status == null
                ? interviewSessionRepository.searchOwnedForAgent(
                    effectiveOwner, escapeLikeQuery(normalizedQuery), pageRequest
                )
                : interviewSessionRepository.findByOwnerIdAndStatus(effectiveOwner, status, pageRequest);
        }
        return page.getContent().stream()
            .map(AgentInterviewSearchResult::from)
            .toList();
    }

    public void assertOwner(String sessionId, String ownerKey) {
        String owner = requireOwner(ownerKey);
        boolean allowed = AuthUserContext.currentUserId()
            .map(current -> interviewSessionRepository.findBySessionIdAndOwnerId(sessionId, current).isPresent())
            .orElseGet(() -> interviewSessionRepository.findBySessionIdAndOwnerId(sessionId, owner).isPresent());
        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该面试记录");
        }
    }

    private InterviewSessionEntity.SessionStatus parseStatus(String query) {
        try {
            return InterviewSessionEntity.SessionStatus.valueOf(query.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String escapeLikeQuery(String query) {
        return query.replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_");
    }

    private String requireOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份已失效，请刷新页面后重试");
        }
        return ownerKey.trim();
    }
}
