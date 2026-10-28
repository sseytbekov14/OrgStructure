package cs_orgchart.faq;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FunctionFaqRepository extends JpaRepository<FunctionFaq, String> {
}
