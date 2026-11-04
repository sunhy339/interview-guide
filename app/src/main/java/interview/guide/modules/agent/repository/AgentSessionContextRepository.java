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

    /**
     * 读取上下文时同时约束 Agent 会话归属，避免未来新增调用入口只校验资源 ID
     * 而把另一账号的会话绑定带入 Prompt。
     */
    @Query("""
        SELECT c FROM AgentSessionContextEntity c
         WHERE c.agentSessionId = :agentSessionId
           AND c.active = true
           AND EXISTS (
               SELECT s.id FROM AgentChatSessionEntity s
                WHERE s.agentSessionId = c.agentSessionId
                  AND s.ownerKey = :ownerKey
           )
         ORDER BY c.attachedAt DESC
        """)
    List<AgentSessionContextEntity>
        findByAgentSessionIdAndOwnerKeyAndActiveTrueOrderByAttachedAtDesc(
            @Param("agentSessionId") String agentSessionId,
            @Param("ownerKey") String ownerKey
        );

    Optional<AgentSessionContextEntity>
        findFirstByAgentSessionIdAndContextTypeAndResourceIdAndActiveTrue(
            String agentSessionId, String contextType, String resourceId
        );

    int countByAgentSessionIdAndActiveTrue(String agentSessionId);

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

    @Modifying
    @Query("""
        UPDATE AgentSessionContextEntity c
           SET c.active = false, c.detachedAt = :detachedAt
         WHERE c.agentSessionId = :agentSessionId
           AND c.contextType = :contextType
           AND c.resourceId = :resourceId
           AND c.active = true
        """)
    int deactivateActiveResource(
        @Param("agentSessionId") String agentSessionId,
        @Param("contextType") String contextType,
        @Param("resourceId") String resourceId,
        @Param("detachedAt") LocalDateTime detachedAt
    );

    @Modifying
    @Query("""
        UPDATE AgentSessionContextEntity c
           SET c.active = false, c.detachedAt = :detachedAt
         WHERE c.agentSessionId = :agentSessionId
           AND c.scope = :scope
           AND c.active = true
        """)
    int deactivateActiveByScope(
        @Param("agentSessionId") String agentSessionId,
        @Param("scope") String scope,
        @Param("detachedAt") LocalDateTime detachedAt
    );
}
