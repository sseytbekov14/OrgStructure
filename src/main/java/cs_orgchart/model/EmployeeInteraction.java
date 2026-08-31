package cs_orgchart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "employee_interactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeeInteraction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_email", nullable = false)
    private String employeeEmail;

    /**
     * e.g., PROFILE_VIEW, MAIL_CLICK
     */
    @Column(name = "interaction_type", nullable = false, length = 50)
    private String interactionType;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "visitor_id")
    private String visitorId;
}
