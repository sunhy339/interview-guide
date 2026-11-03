package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentContextAssetEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentContextAssetRepository extends JpaRepository<AgentContextAssetEntity, Long> {

    Optional<AgentContextAssetEntity> findByAssetIdAndOwnerId(String assetId, String ownerId);

    Optional<AgentContextAssetEntity> findByFileHashAndOwnerId(String fileHash, String ownerId);

    List<AgentContextAssetEntity> findTop50ByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
