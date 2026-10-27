package cs_orgchart.ad.sync;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AdSyncLogRepository extends JpaRepository<AdSyncLog, Long> {

    List<AdSyncLog> findByOrderByIdDesc(Pageable page);

    Optional<AdSyncLog> findFirstByOrderByIdDesc();

    Optional<AdSyncLog> findFirstByStatusInOrderByIdDesc(Collection<String> statuses);

    Optional<AdSyncLog> findFirstByStatusOrderByIdDesc(String status);

    /** RUNNING rows other than {@code exceptId} → FAILED with this error code */
    @Modifying
    @Query("UPDATE AdSyncLog l SET l.status = 'FAILED', l.errorCode = :code, l.errorMessage = :message,"
            + " l.finishedAt = :now WHERE l.status = 'RUNNING' AND l.id <> :exceptId")
    int failRunning(long exceptId, String code, String message, Instant now);
}
