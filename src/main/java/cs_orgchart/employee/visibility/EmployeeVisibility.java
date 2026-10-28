package cs_orgchart.employee.visibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A person hidden from the site by an admin (U4, table employee_visibility): global, never changed by the AD sync.
 */
@Entity
@Table(name = "employee_visibility")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeVisibility {

    /** {@link cs_orgchart.employee.Employee#getId()} */
    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "hidden", nullable = false)
    @Builder.Default
    private boolean hidden = true;

    @Column(name = "hidden_by", length = 256)
    private String hiddenBy;

    @Column(name = "hidden_at", nullable = false)
    private Instant hiddenAt;

    @Column(name = "reason", length = 200)
    private String reason;
}
