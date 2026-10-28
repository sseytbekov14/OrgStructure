package cs_orgchart.faq;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** The FAQ document of a function (U5, table function_faq); content is the JSON of a {@link FaqDocument} */
@Entity
@Table(name = "function_faq")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionFaq {

    @Id
    @Column(name = "cs", length = 256)
    private String cs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", nullable = false)
    private String content;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "updated_by", length = 256)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = Instant.now();
    }
}
