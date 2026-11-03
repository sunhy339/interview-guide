package interview.guide.modules.agent.repository;

import interview.guide.modules.agent.model.AgentContextAssetEntity;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentContextAssetRepository extends JpaRepository<AgentContextAssetEntity, Long> {

    Optional<AgentContextAssetEntity> findByAssetIdAndOwnerId(String assetId, String ownerId);

    /** 串行化同一素材的审核/入库确认，避免重复确认创建多个知识库实体。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AgentContextAssetEntity a WHERE a.assetId = :assetId AND a.ownerId = :ownerId")
    Optional<AgentContextAssetEntity> findByAssetIdAndOwnerIdForUpdate(
        @Param("assetId") String assetId, @Param("ownerId") String ownerId
    );

    Optional<AgentContextAssetEntity> findByFileHashAndOwnerId(String fileHash, String ownerId);

    List<AgentContextAssetEntity> findTop50ByOwnerIdOrderByCreatedAtDesc(String ownerId);
}
