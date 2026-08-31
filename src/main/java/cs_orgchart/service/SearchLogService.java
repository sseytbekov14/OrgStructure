package cs_orgchart.service;

import cs_orgchart.model.SearchLog;
import cs_orgchart.repository.SearchLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class SearchLogService {

    private final SearchLogRepository repository;

    @Async
    public void logSearch(String query, int resultsCount, String visitorId) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }
        
        try {
            SearchLog logEntry = SearchLog.builder()
                    .query(query.trim())
                    .resultsCount(resultsCount)
                    .visitorId(visitorId)
                    .createdAt(LocalDateTime.now())
                    .build();
            
            repository.save(logEntry);
            log.debug("Search logged: '{}' ({} results)", query, resultsCount);
        } catch (Exception e) {
            log.error("Failed to save search log", e);
        }
    }
}
