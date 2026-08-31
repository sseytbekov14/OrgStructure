package cs_orgchart.controller;

import cs_orgchart.model.EmployeeLike;
import cs_orgchart.model.LikeSummary;
import cs_orgchart.service.LikeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/likes")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LikeController {

    private final LikeService likeService;

    /**
     * POST /api/likes
     * Body: { "employeeEmail": "user@kpmg.kz", "reactionType": "SOLVED" }
     */
    @PostMapping
    public ResponseEntity<EmployeeLike> addLike(@RequestBody Map<String, String> body) {
        String email = body.get("employeeEmail");
        String type = body.get("reactionType");
        String visitorId = body.get("visitorId");
        log.info("POST /api/likes  email={} type={} visitor={}", email, type, visitorId);

        try {
            EmployeeLike saved = likeService.addLike(email, type, visitorId);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * GET /api/likes/summary
     * Returns aggregated counts for all employees
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, LikeSummary>> getAllSummaries() {
        log.info("GET /api/likes/summary");
        return ResponseEntity.ok(likeService.getAllSummaries());
    }

    /**
     * GET /api/likes/summary/{email}
     * Returns aggregated counts for a specific employee
     */
    @GetMapping("/summary/{email}")
    public ResponseEntity<LikeSummary> getSummary(@PathVariable String email) {
        log.info("GET /api/likes/summary/{}", email);
        return ResponseEntity.ok(likeService.getSummaryByEmail(email));
    }
}
