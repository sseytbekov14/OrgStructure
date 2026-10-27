package cs_orgchart.ad.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * What the AD sync does after the application has started (only with AD_SYNC_ENABLED=true), in a background
 * thread: the start is never delayed and nothing that goes wrong here stops the application.
 * <ol>
 *   <li>rows left RUNNING by an earlier start → FAILED (INTERRUPTED);</li>
 *   <li>AD_SYNC_STARTUP_DRY_RUN=true: a dry run, its summary in the log and the report (counts only) in
 *       AD_SYNC_REPORT_PATH; nothing is written to the database, AD_SYNC_ON_STARTUP is ignored;</li>
 *   <li>otherwise AD_SYNC_ON_STARTUP=true: one sync.</li>
 * </ol>
 */
@Slf4j
public class AdSyncStartup {

    private final AdSyncService service;
    private final ObjectMapper json;
    private final boolean onStartup;
    private final boolean startupDryRun;
    private final Path reportPath;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ad-sync-startup");
        thread.setDaemon(true);
        return thread;
    });

    public AdSyncStartup(AdSyncService service, ObjectMapper json, boolean onStartup, boolean startupDryRun,
                         String reportPath) {
        this.service = service;
        this.json = json;
        this.onStartup = onStartup;
        this.startupDryRun = startupDryRun;
        this.reportPath = Path.of(reportPath == null || reportPath.isBlank() ? "logs/ad-sync-report.json"
                : reportPath.trim());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        start();
    }

    /** The startup tasks in the background thread */
    Future<?> start() {
        return executor.submit(this::runStartupTasks);
    }

    void runStartupTasks() {
        try {
            service.repairInterruptedRuns();
        } catch (RuntimeException e) {
            log.warn("AD sync: rows left RUNNING could not be checked: {}", e.toString());
        }
        try {
            if (startupDryRun) {
                if (onStartup) {
                    log.info("AD sync: AD_SYNC_STARTUP_DRY_RUN=true, AD_SYNC_ON_STARTUP is ignored (no sync)");
                }
                writeReport(service.dryRun(SyncTrigger.STARTUP));
            } else if (onStartup) {
                service.sync(SyncTrigger.STARTUP);
            }
        } catch (RuntimeException e) {
            log.error("AD sync at startup could not run; the application goes on without it", e);
        }
    }

    private void writeReport(DryRunReport report) {
        try {
            Path absolute = reportPath.toAbsolutePath();
            if (absolute.getParent() != null) {
                Files.createDirectories(absolute.getParent());
            }
            json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(absolute.toFile(), report);
            log.info("AD dry run report written to {}", absolute);
        } catch (IOException | RuntimeException e) {
            log.error("AD dry run report could not be written to {}: {}", reportPath.toAbsolutePath(), e.toString());
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
