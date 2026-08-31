package cs_orgchart.service;

import cs_orgchart.model.EmployeeLike;
import cs_orgchart.model.LikeSummary;
import cs_orgchart.repository.EmployeeLikeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class LikeService {

    private final EmployeeLikeRepository repository;

    public EmployeeLike addLike(String employeeEmail, String reactionType, String visitorId) {
        if (employeeEmail == null || employeeEmail.isBlank()) {
            throw new IllegalArgumentException("Employee email is required");
        }
        if (!"SOLVED".equals(reactionType) && !"EXCEEDED".equals(reactionType)) {
            throw new IllegalArgumentException("Reaction type must be SOLVED or EXCEEDED");
        }

        EmployeeLike like = EmployeeLike.builder()
                .employeeEmail(employeeEmail.trim().toLowerCase())
                .reactionType(reactionType)
                .visitorId(visitorId)
                .createdAt(LocalDateTime.now())
                .build();

        EmployeeLike saved = repository.save(like);
        log.info("Like added: {} → {} (id={})", employeeEmail, reactionType, saved.getId());
        return saved;
    }

    public Map<String, LikeSummary> getAllSummaries() {
        Map<String, LikeSummary> result = new HashMap<>();
        List<Object[]> rows = repository.findAllSummaries();
        for (Object[] row : rows) {
            String email = (String) row[0];
            long solved = ((Number) row[1]).longValue();
            long exceeded = ((Number) row[2]).longValue();
            result.put(email, new LikeSummary(email, solved, exceeded));
        }
        return result;
    }

    public LikeSummary getSummaryByEmail(String email) {
        List<Object[]> rows = repository.findSummaryByEmail(email.trim().toLowerCase());
        if (rows.isEmpty()) {
            return new LikeSummary(email, 0, 0);
        }
        Object[] row = rows.get(0);
        return new LikeSummary(
                (String) row[0],
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue()
        );
    }
}
