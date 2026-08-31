package cs_orgchart.controller;

import cs_orgchart.model.Employee;
import cs_orgchart.service.ExcelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class EmployeeController {

    private final ExcelService excelService;

    @GetMapping("/functions")
    public ResponseEntity<List<String>> getFunctions() {
        log.info("GET /api/functions");
        List<String> functions = excelService.getFunctions();
        return ResponseEntity.ok(functions);
    }

    @GetMapping("/search")
    public ResponseEntity<List<Employee>> search(
        @RequestParam(required = false) String q) {
        log.info("GET /api/search?q={}", q);
        return ResponseEntity.ok(excelService.search(q));
}

    @GetMapping("/employees")
    public ResponseEntity<List<Employee>> getEmployees(
            @RequestParam(required = false) String cs) {

        if (cs != null && !cs.isEmpty()) {
            log.info("GET /api/employees?cs={}", cs);
            return ResponseEntity.ok(excelService.getEmployeesByFunction(cs));
        }

        log.info("GET /api/employees");
        return ResponseEntity.ok(excelService.getAllEmployees());
    }
}