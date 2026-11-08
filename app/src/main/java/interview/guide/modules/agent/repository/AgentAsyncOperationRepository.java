package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentAsyncOperationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentAsyncOperationRepository extends JpaRepository<AgentAsyncOperationEntity, Long> {

    Optional<AgentAsyncOperationEntity> findByOwnerKeyAndRunIdAndKnowledgeBaseIdAndOperation(
        String ownerKey, String runId, Long knowledgeBaseId, String operation
    );

    List<AgentAsyncOperationEntity> findByKnowledgeBaseIdAndOperation(
        Long knowledgeBaseId, String operation
    );
}
