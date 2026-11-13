package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentChatMessageEntity;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentChatMessageRepository extends JpaRepository<AgentChatMessageEntity, Long> {

    List<AgentChatMessageEntity> findBySessionIdAndCompletedTrueOrderBySequenceNumAsc(Long sessionId);

    List<AgentChatMessageEntity> findBySessionIdAndCompletedTrueOrderBySequenceNumDesc(
        Long sessionId, Pageable pageable
    );

    /**
     * 会话已经生成滚动摘要后，只把摘要边界之后的原始消息送入下一轮模型。
     * 摘要边界之前的消息仍然永久保留在表中，不参与本次 Prompt 重建。
     */
    List<AgentChatMessageEntity>
        findBySessionIdAndCompletedTrueAndSequenceNumGreaterThanOrderBySequenceNumAsc(
            Long sessionId, Integer sequenceNum
        );

    Optional<AgentChatMessageEntity> findTopBySessionIdOrderBySequenceNumDesc(Long sessionId);

    Optional<AgentChatMessageEntity> findTopBySessionIdAndCompletedFalseOrderBySequenceNumDesc(Long sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT m FROM AgentChatMessageEntity m
        WHERE m.session.id = :sessionId
          AND m.steering = true
          AND m.steeringConsumed = false
          AND m.steeringRunId = :runId
        ORDER BY m.sequenceNum ASC
        """)
    List<AgentChatMessageEntity> findPendingSteeringForUpdate(
        @Param("sessionId") Long sessionId,
        @Param("runId") String runId
    );
}
