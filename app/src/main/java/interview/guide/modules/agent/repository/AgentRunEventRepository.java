package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentRunEventEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentRunEventRepository extends JpaRepository<AgentRunEventEntity, Long> {

    List<AgentRunEventEntity> findTop200ByOwnerKeyAndAgentSessionIdOrderByIdAsc(
        String ownerKey, String agentSessionId
    );

    List<AgentRunEventEntity> findTop200ByOwnerKeyAndAgentSessionIdAndRunIdOrderByIdAsc(
        String ownerKey, String agentSessionId, String runId
    );
}
