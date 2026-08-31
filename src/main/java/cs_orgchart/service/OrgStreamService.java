package cs_orgchart.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class OrgStreamService {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicLong version = new AtomicLong(1);

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);

        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            emitters.remove(emitter);
        });
        emitter.onError(error -> emitters.remove(emitter));

        sendEvent(emitter, "connected", buildPayload("connected"));
        return emitter;
    }

    public void publishOrgUpdated(String source) {
        long nextVersion = version.incrementAndGet();
        Map<String, Object> payload = Map.of(
                "version", nextVersion,
                "updatedAt", Instant.now().toString(),
                "source", source
        );

        emitters.forEach(emitter -> sendEvent(emitter, "org-updated", payload));
        log.info("Broadcasted org update version={} to {} clients", nextVersion, emitters.size());
    }

    private Map<String, Object> buildPayload(String source) {
        return Map.of(
                "version", version.get(),
                "updatedAt", Instant.now().toString(),
                "source", source
        );
    }

    private void sendEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload));
        } catch (Exception ex) {
            // Remove dead emitter silently — calling completeWithError from a
            // non-container thread causes IllegalStateException in Tomcat's AsyncContext
            emitters.remove(emitter);
        }
    }
}
