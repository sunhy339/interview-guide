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
