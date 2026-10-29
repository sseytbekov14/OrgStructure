package cs_orgchart.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByAdGuid(UUID adGuid);

    Optional<Employee> findFirstByUpnIgnoreCase(String upn);

    /** The employees the site shows: synced from AD and still there (the base of the sync safety check) */
    long countByEnabledTrueAndExternalFalse();

    /**
     * The employee of the site a sign-in name belongs to: enabled, not external, whose e-mail or UPN it is
     * (lower case expected)
     */
    @Query("SELECT e FROM Employee e WHERE e.enabled = TRUE AND e.external = FALSE"
            + " AND (lower(e.email) = :name OR lower(e.upn) = :name) ORDER BY e.id")
    List<Employee> findBySignInName(@Param("name") String name);
}
