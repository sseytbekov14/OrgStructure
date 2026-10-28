package cs_orgchart.access;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppAdminRepository extends JpaRepository<AppAdmin, Long> {

    @Query("SELECT lower(a.loginName) FROM AppAdmin a WHERE a.enabled = true")
    List<String> findEnabledLoginNames();

    Optional<AppAdmin> findByLoginNameIgnoreCase(String loginName);

    long countByEnabledTrue();

    List<AppAdmin> findAllByOrderByLoginNameAsc();
}
