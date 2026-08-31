package cs_orgchart.controller;

import cs_orgchart.service.SearchLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/search-logs")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SearchLogController {

    private final SearchLogService searchLogService;

    /**
     * POST /api/search-logs
     * Body: { "query": "vacation", "resultsCount": 5 }
     */
    @PostMapping
    public ResponseEntity<Void> logSearch(@RequestBody Map<String, Object> body) {
        try {
            String query = (String) body.get("query");
            Object countObj = body.get("resultsCount");
            int resultsCount = 0;
            if (countObj instanceof Number) {
                resultsCount = ((Number) countObj).intValue();
            } else if (countObj instanceof String) {
                resultsCount = Integer.parseInt((String) countObj);
            }
            String visitorId = (String) body.get("visitorId");
            
            searchLogService.logSearch(query, resultsCount, visitorId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.warn("Invalid search log request", e);
            return ResponseEntity.badRequest().build();
        }
    }
}
