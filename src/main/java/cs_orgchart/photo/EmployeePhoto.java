package cs_orgchart.photo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One row of employee_photos WITH the image bytes: read one at a time (GET /api/photos/{id}) and written by the photo
 * sync. Lists use {@link EmployeePhotoInfo}, which has no content column.
 */
@Entity
@Table(name = "employee_photos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployeePhoto {

    /** {@link cs_orgchart.employee.Employee#getId()} */
    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private PhotoState state;

    /** The re-encoded JPEG (PRESENT only) */
    @ToString.Exclude
    @Column(name = "content")
    private byte[] content;

    @Column(name = "content_type", length = 32)
    private String contentType;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    /** Hex SHA-256 of {@link #content}: the ETag and the version in the photo URL */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** employees.ad_when_changed at the time of the check */
    @Column(name = "source_when_changed")
    private Instant sourceWhenChanged;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    /** AD (the photo sync) or MANUAL (uploaded on the site: the sync leaves it alone) */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    @Builder.Default
    private PhotoSource source = PhotoSource.AD;

    /** Who uploaded a MANUAL photo (the sign-in name); null for AD */
    @Column(name = "uploaded_by", length = 256)
    private String uploadedBy;
}
