package cs_orgchart.employee.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * The position of a person inside their group of a function, set by an admin (table group_member_order). It counts
 * only while the person is in {@link #groupId} (null: the "Other" column, no group). Without a row the person follows
 * those who have one, by grade. Never changed by the AD sync.
 */
@Entity
@Table(name = "group_member_order")
@IdClass(GroupMemberOrder.Key.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupMemberOrder {

    /** The primary key: one position per person per function */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private String cs;
        private Long employeeId;
    }

    /** Site function name */
    @Id
    @Column(name = "cs", length = 256)
    private String cs;

    /** {@link cs_orgchart.employee.Employee#getId()} */
    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    /** {@link FunctionGroup#getId()}; null: the people without a group */
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "updated_by", length = 256)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
