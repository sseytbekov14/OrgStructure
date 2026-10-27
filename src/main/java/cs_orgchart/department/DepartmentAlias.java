package cs_orgchart.department;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A spelling of a department segment in AD and the name it is shown with (spec v3 §5.3), e.g.
 * {@code f&o} → {@code Facilities & Operations}.
 */
@Entity
@Table(name = "department_aliases")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lower case */
    @Column(name = "alias", nullable = false, unique = true, length = 256)
    private String alias;

    @Column(name = "canonical", nullable = false, length = 256)
    private String canonical;
}
