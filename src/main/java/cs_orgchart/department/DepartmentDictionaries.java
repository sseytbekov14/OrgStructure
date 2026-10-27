package cs_orgchart.department;

import java.util.List;
import java.util.Map;

/**
 * The dictionaries of the department normalizer and the function resolver (spec v2.1 §5.3, §5.5): from the database
 * in the application ({@link JpaDepartmentDictionaries}), in memory in the tests.
 */
public interface DepartmentDictionaries {

    /** department_aliases: alias (lower case) → canonical name */
    Map<String, String> aliases();

    /** department_general_nodes: function name → general node name */
    Map<String, String> generalNodes();

    /** department_function_map */
    List<FunctionMapping> functionMappings();

    /** One row of department_function_map; {@code deptLevel3} null: any level 3 */
    record FunctionMapping(String deptLevel2, String deptLevel3, String csName, boolean visible) {
    }
}
