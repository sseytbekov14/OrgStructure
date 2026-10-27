package cs_orgchart.ad.sync;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdSyncLogRepository extends JpaRepository<AdSyncLog, Long> {
}
