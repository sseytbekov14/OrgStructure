package cs_orgchart.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** employee_photos with the bytes: one row at a time (no listing methods on purpose) */
@Repository
public interface EmployeePhotoRepository extends JpaRepository<EmployeePhoto, Long> {
}
