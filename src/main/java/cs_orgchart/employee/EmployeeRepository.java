package cs_orgchart.employee;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByAdGuid(UUID adGuid);

    Optional<Employee> findFirstByUpnIgnoreCase(String upn);

    /** The employees the site shows: synced from AD and still there (the base of the sync safety check) */
    long countByEnabledTrueAndExternalFalse();
}
