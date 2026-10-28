package cs_orgchart.employee.override;

import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.EmployeeSourceProperties;
import cs_orgchart.employee.visibility.EmployeeVisibilityRepository;
import org.springframework.stereotype.Component;

/**
 * The checks every admin change starts with (overlay, heads, groups, hiding): editing on (EMPLOYEE_SOURCE=db), a
 * known function, a person who can be on the site.
 */
@Component
public class AdminGuards {

    private final EmployeeRepository employees;
    private final DepartmentFunctionMapRepository functionMap;
    private final EmployeeSourceProperties source;
    private final EmployeeVisibilityRepository visibility;

    public AdminGuards(EmployeeRepository employees, DepartmentFunctionMapRepository functionMap,
                       EmployeeSourceProperties source, EmployeeVisibilityRepository visibility) {
        this.employees = employees;
        this.functionMap = functionMap;
        this.source = source;
        this.visibility = visibility;
    }

    public boolean editingEnabled() {
        return source.isDb();
    }

    /** 409 EDITING_DISABLED with EMPLOYEE_SOURCE=excel */
    public void requireEditing() {
        if (!editingEnabled()) {
            throw AdminEditException.conflict("EDITING_DISABLED", "admin editing requires EMPLOYEE_SOURCE=db");
        }
    }

    public void requireKnownFunction(String cs) {
        if (cs == null || cs.isBlank() || !functionMap.findCsNames().contains(cs)) {
            throw AdminEditException.badRequest("UNKNOWN_FUNCTION", "unknown function: " + cs);
        }
    }

    /** The person exists, is enabled and from Central Services (not an external manager) */
    public Employee requireEmployee(Long employeeId) {
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> AdminEditException.notFound("UNKNOWN_EMPLOYEE", "no employee " + employeeId));
        if (!employee.isEnabled()) {
            throw AdminEditException.badRequest("EMPLOYEE_DISABLED", "employee " + employeeId + " is disabled in AD");
        }
        if (employee.isExternal()) {
            throw AdminEditException.badRequest("EMPLOYEE_EXTERNAL", "employee " + employeeId + " is not in Central"
                    + " Services");
        }
        return employee;
    }

    /** {@link #requireEmployee} and not hidden from the site (U4) */
    public Employee requireListedEmployee(Long employeeId) {
        Employee employee = requireEmployee(employeeId);
        if (isHidden(employeeId)) {
            throw AdminEditException.badRequest("EMPLOYEE_HIDDEN", "employee " + employeeId + " is hidden from the"
                    + " site; restore them first");
        }
        return employee;
    }

    public boolean isHidden(Long employeeId) {
        return visibility.findById(employeeId).map(v -> v.isHidden()).orElse(false);
    }
}
