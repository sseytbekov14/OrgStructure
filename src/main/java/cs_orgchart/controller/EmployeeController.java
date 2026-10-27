package cs_orgchart.controller;

import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.model.Employee;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The employees of the site (EmployeeDirectory: from Excel or the database, EMPLOYEE_SOURCE). Optional filters:
 * {@code cs} (function) and {@code country} (country code, database employees only).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class EmployeeController {

    private final EmployeeDirectory directory;

    @GetMapping("/functions")
    public ResponseEntity<List<String>> getFunctions() {
        log.info("GET /api/functions");
        List<String> functions = directory.getFunctions();
        return ResponseEntity.ok(functions);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Employee>> search(
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String cs,
        @RequestParam(required = false) String country) {
        log.info("GET /api/search?q={}", q);
        List<Employee> found = directory.search(q);
        if (cs != null && !cs.isEmpty()) {
            found = found.stream().filter(e -> cs.equals(e.getCs())).toList();
        }
        return ResponseEntity.ok(EmployeeDirectory.inCountry(found, country));
    }

    @GetMapping("/employees")
    public ResponseEntity<List<Employee>> getEmployees(
            @RequestParam(required = false) String cs,
            @RequestParam(required = false) String country) {

        if (cs != null && !cs.isEmpty()) {
            log.info("GET /api/employees?cs={}", cs);
            return ResponseEntity.ok(EmployeeDirectory.inCountry(directory.getEmployeesByFunction(cs), country));
        }

        log.info("GET /api/employees");
        return ResponseEntity.ok(EmployeeDirectory.inCountry(directory.getAllEmployees(), country));
    }
}
