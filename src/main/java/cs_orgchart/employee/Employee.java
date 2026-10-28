package cs_orgchart.employee;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An employee as read from Active Directory (spec v3 §4.3, §6.1). Not the same as {@link cs_orgchart.model.Employee},
 * which is the in-memory record the site shows today (Excel).
 *
 * <p>AD is the source of these facts; presentation data lives in
 * {@link cs_orgchart.employee.override.EmployeeOverride}. Rows are never deleted: leavers get {@code enabled = false}.
 * {@code updatedAt} is set by the sync when an AD field changes, {@code lastSyncedAt} on every sync that sees the row.
 */
@Entity
@Table(name = "employees")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** objectGUID: the permanent key of the upsert */
    @Column(name = "ad_guid", nullable = false, unique = true)
    private UUID adGuid;

    /** distinguishedName: resolves {@code manager}; changes when the account moves, so it is not a key */
    @Column(name = "dn", nullable = false, length = 512)
    private String dn;

    /** sAMAccountName */
    @Column(name = "login", nullable = false, length = 128)
    private String login;

    /** userPrincipalName */
    @Column(name = "upn", nullable = false, length = 256)
    private String upn;

    @Column(name = "email", length = 256)
    private String email;

    /** AD employeeID (not the database id) */
    @Column(name = "employee_id", length = 64)
    private String employeeId;

    @Column(name = "display_name", nullable = false, length = 256)
    private String displayName;

    @Column(name = "first_name", length = 128)
    private String firstName;

    @Column(name = "last_name", length = 128)
    private String lastName;

    @Column(name = "job_title", length = 256)
    private String jobTitle;

    @Column(name = "company", length = 256)
    private String company;

    /** The raw AD value, kept unchanged */
    @Column(name = "department", length = 512)
    private String department;

    /** Normalized department (spec §5.2) */
    @Column(name = "dept_level1", length = 256)
    private String deptLevel1;

    @Column(name = "dept_level2", length = 256)
    private String deptLevel2;

    @Column(name = "dept_level3", length = 256)
    private String deptLevel3;

    @Column(name = "dept_level4", length = 256)
    private String deptLevel4;

    /** DN of the line manager */
    @Column(name = "manager_dn", length = 512)
    private String managerDn;

    /** kPMG-User-PerformanceManagerUPN */
    @Column(name = "pm_upn", length = 256)
    private String pmUpn;

    /** {@link #id} of the line manager */
    @Column(name = "manager_id")
    private Long managerId;

    /** {@link #id} of the performance manager */
    @Column(name = "pm_id")
    private Long pmId;

    /** From the DN: OU=&lt;XX&gt;,OU=Countries, one of AD_LDAP_COUNTRIES; null only for external rows */
    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(name = "office", length = 256)
    private String office;

    @Column(name = "city", length = 128)
    private String city;

    /** U6: AD co (Country/Region name) */
    @Column(name = "country_name", length = 128)
    private String countryName;

    /** U6: AD c (two-letter code, upper case) */
    @Column(name = "country_iso", length = 2)
    private String countryIso;

    @Column(name = "phone", length = 64)
    private String phone;

    @Column(name = "mobile", length = 64)
    private String mobile;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /** A manager or PM outside Central Services, kept only to link to */
    @Column(name = "is_external", nullable = false)
    @Builder.Default
    private boolean external = false;

    @Column(name = "ad_when_changed")
    private Instant adWhenChanged;

    @Column(name = "last_synced_at", nullable = false)
    @Builder.Default
    private Instant lastSyncedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
