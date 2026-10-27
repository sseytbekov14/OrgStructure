package cs_orgchart.department;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DepartmentFunctionMapRepository extends JpaRepository<DepartmentFunctionMap, Long> {
}
