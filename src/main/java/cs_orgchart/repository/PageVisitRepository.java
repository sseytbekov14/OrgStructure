package cs_orgchart.repository;

import cs_orgchart.model.PageVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PageVisitRepository extends JpaRepository<PageVisit, Long> {
}
