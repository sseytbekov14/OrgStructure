package cs_orgchart.employee.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FunctionGroupRepository extends JpaRepository<FunctionGroup, Long> {

    List<FunctionGroup> findByCsOrderBySortOrderAscNameAsc(String cs);

    @Query("SELECT g FROM FunctionGroup g WHERE g.cs = :cs AND lower(g.name) = lower(:name)")
    Optional<FunctionGroup> findByCsAndNameIgnoreCase(String cs, String name);

    @Query("SELECT coalesce(max(g.sortOrder), 0) FROM FunctionGroup g WHERE g.cs = :cs")
    int maxSortOrder(String cs);
}
