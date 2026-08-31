package cs_orgchart.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
@RequiredArgsConstructor
public class FileWatcherService {

    @Value("${app.data.excel-path}")
    private String excelPath;

    @Value("${app.data.photos-path}")
    private String photosPath;

    @Value("${app.data.watch-interval-ms:1000}")
    private long watchIntervalMs;

    private final ExcelService excelService;
    private final OrgStreamService orgStreamService;

    private final AtomicLong lastReloadAt = new AtomicLong(0);
    private FileAlterationMonitor monitor;

    @PostConstruct
    public void startWatching() {
        try {
            monitor = new FileAlterationMonitor(watchIntervalMs);

            // 1. Наблюдатель за файлом Excel
            File excelFile = new File(excelPath);
            File parentDir = excelFile.getParentFile();
            if (parentDir != null && parentDir.exists()) {
                FileAlterationObserver excelObserver = new FileAlterationObserver(
                        parentDir,
                        file -> file.getName().equals(excelFile.getName())
                );
                excelObserver.addListener(new FileAlterationListenerAdaptor() {
                    @Override
                    public void onFileChange(File file) {
                        handleExcelChange(file);
                    }
                });
                monitor.addObserver(excelObserver);
                log.info("Apache Commons IO file watcher added for: {}", excelPath);
            } else {
                log.warn("Excel parent directory does not exist: {}", excelPath);
            }

            // 2. Наблюдатель за папкой с фотографиями
            File photosDir = new File(photosPath);
            if (photosDir.exists() && photosDir.isDirectory()) {
                FileAlterationObserver photosObserver = new FileAlterationObserver(
                        photosDir,
                        file -> {
                            String name = file.getName().toLowerCase();
                            return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp");
                        }
                );
                photosObserver.addListener(new FileAlterationListenerAdaptor() {
                    @Override
                    public void onFileCreate(File file) {
                        handlePhotosChange(file);
                    }
                    @Override
                    public void onFileChange(File file) {
                        handlePhotosChange(file);
                    }
                    @Override
                    public void onFileDelete(File file) {
                        handlePhotosChange(file);
                    }
                });
                monitor.addObserver(photosObserver);
                log.info("Apache Commons IO file watcher added for photos directory: {}", photosPath);
            } else {
                log.warn("Photos directory does not exist or is not a directory: {}", photosPath);
            }

            monitor.start();
            log.info("File watcher monitor started successfully");
        } catch (Exception e) {
            log.error("Failed to start file watcher: {}", e.getMessage(), e);
        }
    }

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> scheduledTask;

    @PreDestroy
    public void stopWatching() {
        if (monitor != null) {
            try {
                monitor.stop();
                log.info("File watcher stopped");
            } catch (Exception e) {
                log.error("Error stopping file watcher: {}", e.getMessage(), e);
            }
        }
        scheduler.shutdownNow();
    }

    private void handleExcelChange(File file) {
        scheduleReload(file.getName(), "Excel file");
    }

    private void handlePhotosChange(File file) {
        scheduleReload(file.getName(), "Photos folder");
    }

    private synchronized void scheduleReload(String fileName, String source) {
        if (scheduledTask != null && !scheduledTask.isDone()) {
            scheduledTask.cancel(false);
        }
        scheduledTask = scheduler.schedule(() -> {
            try {
                log.info("{} changed (file: {}), rebuilding org chart...", source, fileName);
                excelService.reloadData();
                orgStreamService.publishOrgUpdated(fileName);
                log.info("Org chart rebuilt successfully");
            } catch (Exception e) {
                log.error("Failed to rebuild org chart: {}", e.getMessage(), e);
            }
        }, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
    }
}
