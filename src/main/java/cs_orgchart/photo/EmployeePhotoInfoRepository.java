package cs_orgchart.photo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** employee_photos without the bytes (lists) */
@Repository
public interface EmployeePhotoInfoRepository extends JpaRepository<EmployeePhotoInfo, Long> {

    List<EmployeePhotoInfo> findByState(PhotoState state);
}
