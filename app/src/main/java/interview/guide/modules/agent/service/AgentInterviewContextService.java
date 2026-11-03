package interview.guide.modules.agent.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.agent.model.AgentInterviewSearchResult;
import interview.guide.modules.auth.AuthUserContext;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
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
        List<InterviewSessionEntity> sessions = AuthUserContext.currentUserId()
            .map(interviewSessionRepository::findAllByOwnerIdOrderByCreatedAtDesc)
            .orElseGet(() -> interviewSessionRepository.findAllByOwnerIdOrderByCreatedAtDesc(owner));
        return sessions.stream()
            .filter(session -> matches(session, normalizedQuery))
            .sorted(Comparator.comparing(InterviewSessionEntity::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(Math.max(1, Math.min(20, limit)))
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

    private boolean matches(InterviewSessionEntity session, String query) {
        if (query.isBlank()) {
            return true;
        }
        return contains(session.getTitle(), query)
            || contains(session.getSkillId(), query)
            || contains(session.getSessionId(), query)
            || contains(session.getStatus() == null ? null : session.getStatus().name(), query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private String requireOwner(String ownerKey) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Agent 身份已失效，请刷新页面后重试");
        }
        return ownerKey.trim();
    }
}
