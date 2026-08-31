package cs_orgchart.controller;

import cs_orgchart.service.EmployeeInteractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/interactions")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class EmployeeInteractionController {

    private final EmployeeInteractionService interactionService;

    /**
     * POST /api/interactions
     * Body: { "employeeEmail": "user@kpmg.kz", "interactionType": "PROFILE_VIEW" }
     */
    @PostMapping
    public ResponseEntity<Void> logInteraction(@RequestBody Map<String, String> body) {
        try {
            String email = body.get("employeeEmail");
            String type = body.get("interactionType");
            String visitorId = body.get("visitorId");
            
            interactionService.logInteraction(email, type, visitorId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Invalid interaction log request", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
