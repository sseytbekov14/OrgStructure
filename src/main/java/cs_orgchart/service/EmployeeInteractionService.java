package cs_orgchart.service;

import cs_orgchart.model.EmployeeInteraction;
import cs_orgchart.repository.EmployeeInteractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class EmployeeInteractionService {

    private final EmployeeInteractionRepository repository;

    @Async
    public void logInteraction(String employeeEmail, String interactionType, String visitorId) {
        if (employeeEmail == null || employeeEmail.trim().isEmpty() || interactionType == null) {
            return;
        }

        try {
            EmployeeInteraction interaction = EmployeeInteraction.builder()
                    .employeeEmail(employeeEmail.trim().toLowerCase())
                    .interactionType(interactionType)
                    .visitorId(visitorId)
                    .createdAt(LocalDateTime.now())
                    .build();

            repository.save(interaction);
            log.debug("Interaction logged: {} -> {}", employeeEmail, interactionType);
        } catch (Exception e) {
            log.error("Failed to save employee interaction log", e);
        }
    }
}
