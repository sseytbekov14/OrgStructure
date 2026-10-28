package cs_orgchart.employee.override;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmployeeOverrideRepository extends JpaRepository<EmployeeOverride, Long> {

    Optional<EmployeeOverride> findByEmployeeIdAndCs(Long employeeId, String cs);

    List<EmployeeOverride> findByCs(String cs);

    List<EmployeeOverride> findByGroupId(Long groupId);

    List<EmployeeOverride> findByEmployeeId(Long employeeId);
}
