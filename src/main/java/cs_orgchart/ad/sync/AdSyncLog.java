package cs_orgchart.ad.sync;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One AD sync run (spec v2.1 §6.2, §7): its trigger, outcome and counts.
 */
@Entity
@Table(name = "ad_sync_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdSyncLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    @Builder.Default
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** RUNNING / SUCCESS / FAILED / SKIPPED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** SCHEDULED / MANUAL / STARTUP */
    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType;

    @Column(name = "fetched_count")
    private Integer fetchedCount;

    @Column(name = "inserted_count")
    private Integer insertedCount;

    @Column(name = "updated_count")
    private Integer updatedCount;

    @Column(name = "disabled_count")
    private Integer disabledCount;

    @Column(name = "external_count")
    private Integer externalCount;

    @Column(name = "unmapped_count")
    private Integer unmappedCount;

    /** PMs resolved only by the local part of the e-mail */
    @Column(name = "pm_localpart_count")
    private Integer pmLocalpartCount;

    /** e.g. AUTH, TLS, NETWORK, TIMEOUT, SAFETY */
    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
