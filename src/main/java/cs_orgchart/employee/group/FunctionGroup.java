package cs_orgchart.employee.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A group of a function (U4, table function_groups): a team or a country column of the Groups view. AUTO groups are
 * the AD default groups (registered by {@link GroupRegistrar}), MANUAL groups are made by admins.
 */
@Entity
@Table(name = "function_groups")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionGroup {

    public static final String TEAM = "TEAM";
    public static final String COUNTRY = "COUNTRY";
    public static final String AUTO = "AUTO";
    public static final String MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cs", nullable = false, length = 256)
    private String cs;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    /** {@link #TEAM} or {@link #COUNTRY} */
    @Column(name = "kind", nullable = false, length = 16)
    @Builder.Default
    private String kind = TEAM;

    /** {@link #AUTO} (AD default group) or {@link #MANUAL} (made by an admin, or migrated) */
    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private String source = MANUAL;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_by", length = 256)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_by", length = 256)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }
}
