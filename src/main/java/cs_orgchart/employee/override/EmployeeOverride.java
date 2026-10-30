package cs_orgchart.employee.override;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Presentation data AD does not have (spec v3 §6.4): one row per person per site function, edited by admins.
 * The AD sync never touches it. At most one person per function holds each {@code headRank}.
 */
@Entity
@Table(name = "employee_overrides")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link cs_orgchart.employee.Employee#getId()} */
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    /** Site function name (F&amp;O, ITS, ...) */
    @Column(name = "cs", nullable = false, length = 256)
    private String cs;

    /** Team or country code; null: the default group (spec §5.5) */
    @Column(name = "group_name", length = 256)
    private String groupName;

    /**
     * The group of the person in this function (U4, {@link cs_orgchart.employee.group.FunctionGroup}); null: the AD
     * default group. The application reads this; {@link #groupName} is only kept in step (rollback to the previous
     * release).
     */
    @Column(name = "group_id")
    private Long groupId;

    /** null: not a head; 1: main head (tree root); 2..: additional heads */
    @Column(name = "head_rank")
    private Short headRank;

    @Column(name = "area_of_duties", columnDefinition = "TEXT")
    private String areaOfDuties;

    @Column(name = "job_description", columnDefinition = "TEXT")
    private String jobDescription;

    /** Parent in this function only ({@link cs_orgchart.employee.Employee#getId()}); null: the parent from AD */
    @Column(name = "manager_override_id")
    private Long managerOverrideId;

    /** Optimistic locking: a stale edit fails instead of overwriting another admin's change */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** E-mail of the admin who made the last change */
    @Column(name = "updated_by", length = 256)
    private String updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
