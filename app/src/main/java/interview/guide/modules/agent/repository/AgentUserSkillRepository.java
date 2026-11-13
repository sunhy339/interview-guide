package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentUserSkillEntity;
import interview.guide.modules.agent.model.AgentUserSkillStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentUserSkillRepository extends JpaRepository<AgentUserSkillEntity, Long> {

    Optional<AgentUserSkillEntity> findBySkillIdAndOwnerId(String skillId, String ownerId);

    List<AgentUserSkillEntity> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);

    List<AgentUserSkillEntity> findByOwnerIdAndSkillKeyOrderByVersionDesc(String ownerId, String skillKey);

    @Query("""
        SELECT s FROM AgentUserSkillEntity s
        WHERE s.ownerId = :ownerId AND s.status = :status
          AND (LOWER(s.skillKey) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR LOWER(COALESCE(s.description, '')) LIKE LOWER(CONCAT('%', :query, '%')))
        ORDER BY s.updatedAt DESC
        """)
    List<AgentUserSkillEntity> searchEnabled(
        @Param("ownerId") String ownerId,
        @Param("status") AgentUserSkillStatus status,
        @Param("query") String query
    );

    List<AgentUserSkillEntity> findTop20ByOwnerIdAndStatusOrderByUpdatedAtDesc(
        String ownerId, AgentUserSkillStatus status
    );
}
