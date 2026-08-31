package cs_orgchart.service;

import cs_orgchart.model.PageVisit;
import cs_orgchart.repository.PageVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class PageVisitService {

    private final PageVisitRepository repository;

    @Async
    public void logVisit(String pageName, String visitorId) {
        if (pageName == null || pageName.trim().isEmpty()) {
            return;
        }

        try {
            PageVisit visit = PageVisit.builder()
                    .pageName(pageName.trim())
                    .visitorId(visitorId)
                    .createdAt(LocalDateTime.now())
                    .build();

            repository.save(visit);
            log.debug("Page visit logged: {} by {}", pageName, visitorId);
        } catch (Exception e) {
            log.error("Failed to save page visit log", e);
        }
    }
}
