package cs_orgchart.controller;

import cs_orgchart.service.PageVisitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/page-visits")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PageVisitController {

    private final PageVisitService pageVisitService;

    /**
     * POST /api/page-visits
     * Body: { "pageName": "Main", "visitorId": "user-1234" }
     */
    @PostMapping
    public ResponseEntity<Void> logVisit(@RequestBody Map<String, String> body) {
        try {
            String pageName = body.get("pageName");
            String visitorId = body.get("visitorId");
            
            pageVisitService.logVisit(pageName, visitorId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Invalid page visit log request", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
