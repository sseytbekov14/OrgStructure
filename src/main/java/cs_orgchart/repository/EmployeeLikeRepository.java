package cs_orgchart.repository;

import cs_orgchart.model.EmployeeLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmployeeLikeRepository extends JpaRepository<EmployeeLike, Long> {

    @Query("SELECT e.employeeEmail, " +
           "SUM(CASE WHEN e.reactionType = 'SOLVED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN e.reactionType = 'EXCEEDED' THEN 1 ELSE 0 END) " +
           "FROM EmployeeLike e GROUP BY e.employeeEmail")
    List<Object[]> findAllSummaries();

    @Query("SELECT e.employeeEmail, " +
           "SUM(CASE WHEN e.reactionType = 'SOLVED' THEN 1 ELSE 0 END), " +
           "SUM(CASE WHEN e.reactionType = 'EXCEEDED' THEN 1 ELSE 0 END) " +
           "FROM EmployeeLike e WHERE e.employeeEmail = :email GROUP BY e.employeeEmail")
    List<Object[]> findSummaryByEmail(String email);
}
