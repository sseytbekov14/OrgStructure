package cs_orgchart.photo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * employee_photos WITHOUT the image bytes, read-only: what the directory build and the photo sync list (the state and
 * the hash of everybody) never loads a photo.
 */
@Entity
@Immutable
@Table(name = "employee_photos")
@Getter
@NoArgsConstructor
public class EmployeePhotoInfo {

    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private PhotoState state;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "source_when_changed")
    private Instant sourceWhenChanged;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private PhotoSource source;
}
