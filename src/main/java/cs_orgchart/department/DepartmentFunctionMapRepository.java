package cs_orgchart.department;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartmentFunctionMapRepository extends JpaRepository<DepartmentFunctionMap, Long> {

    /** The functions of the site (cs_name) */
    @Query("SELECT DISTINCT m.csName FROM DepartmentFunctionMap m")
    List<String> findCsNames();
}
