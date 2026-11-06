package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentRunStateEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentRunStateRepository extends JpaRepository<AgentRunStateEntity, Long> {

    Optional<AgentRunStateEntity> findByRunId(String runId);
}
