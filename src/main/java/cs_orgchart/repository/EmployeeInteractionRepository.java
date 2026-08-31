package cs_orgchart.repository;

import cs_orgchart.model.EmployeeInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeInteractionRepository extends JpaRepository<EmployeeInteraction, Long> {

    @Query("SELECT e.employeeEmail, e.interactionType, COUNT(e) FROM EmployeeInteraction e GROUP BY e.employeeEmail, e.interactionType ORDER BY COUNT(e) DESC")
    List<Object[]> findBottleneckMetrics();
}
