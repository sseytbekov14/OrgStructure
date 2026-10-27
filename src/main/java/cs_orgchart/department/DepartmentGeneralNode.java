package cs_orgchart.department;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The node appended to a department that names only its function (spec v2.1 §5.3), e.g.
 * {@code ITS} → {@code ITS General}.
 */
@Entity
@Table(name = "department_general_nodes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentGeneralNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "function_name", nullable = false, unique = true, length = 256)
    private String functionName;

    @Column(name = "general_name", nullable = false, length = 256)
    private String generalName;
}
