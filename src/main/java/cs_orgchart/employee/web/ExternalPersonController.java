package cs_orgchart.employee.web;

import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeDtoAssembler;
import cs_orgchart.employee.EmployeeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * U6: the limited card of a manager outside Central Services (the "Reports to" of a card points to them by pmId):
 * name, e-mail, job title, city and country only, never phone, mobile or office. 404 for everybody else (people of
 * the site have their full card in the directory). Same access as the other /api/** endpoints.
 */
@RestController
public class ExternalPersonController {

    public record ExternalPerson(String name, String email, String jobTitle, String city, String country) {
    }

    private final EmployeeRepository employees;

    public ExternalPersonController(EmployeeRepository employees) {
        this.employees = employees;
    }

    @GetMapping("/api/people/{id}/external")
    public ResponseEntity<ExternalPerson> external(@PathVariable long id) {
        return employees.findById(id)
                .filter(Employee::isExternal)
                .map(e -> new ExternalPerson(e.getDisplayName(), e.getEmail() != null ? e.getEmail() : e.getUpn(),
                        e.getJobTitle(), e.getCity(), EmployeeDtoAssembler.country(e)))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
