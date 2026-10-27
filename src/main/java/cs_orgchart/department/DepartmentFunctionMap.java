package cs_orgchart.department;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Which site function a normalized AD department belongs to (spec v2.1 §5.5): the row for
 * {@code (deptLevel2, deptLevel3)} first, then the row for {@code (deptLevel2, null)}.
 */
@Entity
@Table(name = "department_function_map")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepartmentFunctionMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dept_level2", nullable = false, length = 256)
    private String deptLevel2;

    /** null: any level 3 */
    @Column(name = "dept_level3", length = 256)
    private String deptLevel3;

    /** Function name shown on the site (the keys of FUNC_ICONS, FUNCTION_FAQ, TREE_BY_DEFAULT in the frontend) */
    @Column(name = "cs_name", nullable = false, length = 256)
    private String csName;

    @Column(name = "visible", nullable = false)
    @Builder.Default
    private boolean visible = true;
}
