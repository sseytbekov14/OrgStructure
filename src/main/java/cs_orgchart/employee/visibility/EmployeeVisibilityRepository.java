package cs_orgchart.employee.visibility;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeVisibilityRepository extends JpaRepository<EmployeeVisibility, Long> {

    @Query("SELECT v.employeeId FROM EmployeeVisibility v WHERE v.hidden = true")
    List<Long> findHiddenEmployeeIds();

    List<EmployeeVisibility> findByHiddenTrueOrderByHiddenAtDesc();
}
