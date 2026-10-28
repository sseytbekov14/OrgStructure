package cs_orgchart.ad.web;

import cs_orgchart.ad.config.AdLdapProperties;
import cs_orgchart.ad.config.AdSyncModeCondition;
import cs_orgchart.ad.sync.AdSyncLog;
import cs_orgchart.ad.sync.AdSyncLogRepository;
import cs_orgchart.ad.sync.AdSyncService;
import cs_orgchart.ad.sync.DryRunReport;
import cs_orgchart.ad.sync.SyncOutcome;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.photo.PhotoSyncService;
import org.springframework.context.annotation.Conditional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The AD sync for SYSTEM_ADMIN (spec v3 §10.2): the /api/admin/** rule of SecurityConfig and the method checks
 * here. Only with AD_SYNC_MODE set.
 */
@RestController
@RequestMapping("/api/admin/ad-sync")
@Conditional(AdSyncModeCondition.class)
public class AdSyncAdminController {

    static final int MAX_LOG_ROWS = 100;

    private final AdSyncService service;
    private final AdSyncLogRepository syncLog;
    private final AdLdapProperties properties;
    private final PhotoSyncService photos;

    public AdSyncAdminController(AdSyncService service, AdSyncLogRepository syncLog, AdLdapProperties properties,
                                 PhotoSyncService photos) {
        this.service = service;
        this.photos = photos;
        this.syncLog = syncLog;
        this.properties = properties;
    }

    /**
     * A sync now ({@code dryRun=true}: the report of what it would do, nothing written). A real sync needs
     * AD_SYNC_ENABLED=true; a dry run works without it.
     *
     * @return 200 SUCCESS (or the dry run report), 409 SKIPPED (another sync is running) or disabled, 500 FAILED
     */
    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<?> sync(@RequestParam(defaultValue = "false") boolean dryRun) {
        if (dryRun) {
            DryRunReport report = service.dryRun(SyncTrigger.MANUAL).withPhotoCandidates(photos.countCandidates());
            return ResponseEntity.status("OK".equals(report.status()) ? HttpStatus.OK : HttpStatus.BAD_GATEWAY)
                    .body(report);
        }
        if (!properties.getSync().isEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "DISABLED",
                    "message", "AD_SYNC_ENABLED=false: only a dry run (?dryRun=true) is possible"));
        }
        SyncOutcome outcome = service.sync(SyncTrigger.MANUAL);
        HttpStatus status = outcome.status() == SyncStatus.SUCCESS ? HttpStatus.OK
                : outcome.status() == SyncStatus.SKIPPED ? HttpStatus.CONFLICT : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).body(outcome);
    }

    /**
     * The photo phase now (AD_PHOTOS_ENABLED=true, EMPLOYEE_SOURCE=db); {@code force=true} reads everybody, ignoring
     * the whenChanged comparison. Counts only in the answer.
     *
     * @return 200 with the counts; 409 when the photos are off, the sync is suspended or another photo run is going on
     */
    @PostMapping("/photos")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<?> photos(@RequestParam(defaultValue = "false") boolean force) {
        PhotoSyncService.PhotoRun run = photos.run(null, SyncTrigger.MANUAL, force);
        return switch (run.status()) {
            case DONE -> ResponseEntity.ok(run);
            case DISABLED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "DISABLED",
                    "message", "AD photos need AD_PHOTOS_ENABLED=true and EMPLOYEE_SOURCE=db"));
            case SUSPENDED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "SUSPENDED",
                    "message", "the AD sync is suspended after invalid credentials: run a manual sync first"));
            case BUSY -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "BUSY",
                    "message", "another photo run is going on"));
        };
    }

    /** The last sync runs, newest first */
    @GetMapping("/log")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public List<AdSyncLog> log(@RequestParam(defaultValue = "20") int limit) {
        return syncLog.findByOrderByIdDesc(PageRequest.of(0, Math.max(1, Math.min(limit, MAX_LOG_ROWS))));
    }
}
