package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentSessionContextEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentSessionContextRepository extends JpaRepository<AgentSessionContextEntity, Long> {

    Optional<AgentSessionContextEntity> findFirstByAgentSessionIdAndContextTypeAndActiveTrueOrderByAttachedAtDesc(
        String agentSessionId, String contextType
    );

    List<AgentSessionContextEntity> findByAgentSessionIdAndActiveTrueOrderByAttachedAtDesc(
        String agentSessionId
    );

    @Modifying
    @Query("""
        UPDATE AgentSessionContextEntity c
           SET c.active = false, c.detachedAt = :detachedAt
         WHERE c.agentSessionId = :agentSessionId
           AND c.contextType = :contextType
           AND c.active = true
        """)
    int deactivateActive(
        @Param("agentSessionId") String agentSessionId,
        @Param("contextType") String contextType,
        @Param("detachedAt") LocalDateTime detachedAt
    );

    @Modifying
    @Query("""
        UPDATE AgentSessionContextEntity c
           SET c.active = false, c.detachedAt = :detachedAt
         WHERE c.agentSessionId = :agentSessionId
           AND c.active = true
        """)
    int deactivateAllActive(
        @Param("agentSessionId") String agentSessionId,
        @Param("detachedAt") LocalDateTime detachedAt
    );
}
