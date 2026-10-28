package cs_orgchart.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A person with admin access managed in the database (table app_admins, U1); env super admins are not here */
@Entity
@Table(name = "app_admins")
@Getter
@Setter
@NoArgsConstructor
public class AppAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "login_name", nullable = false, length = 256)
    private String loginName;

    @Column(length = 256)
    private String note;

    @Column(name = "added_by", length = 256)
    private String addedBy;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    @Column(nullable = false)
    private boolean enabled = true;
}
