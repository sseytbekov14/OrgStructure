package cs_orgchart.faq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.employee.override.AdminEditException;
import cs_orgchart.employee.override.AdminGuards;
import cs_orgchart.service.OrgStreamService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Objects;

/**
 * The FAQ & Request Guidelines of the functions (U5): read by everyone who may see the site, edited by admins with
 * optimistic locking. Works in both EMPLOYEE_SOURCE modes (it is not employee data). After a change has committed,
 * the open pages are told through SSE (org-updated, source "faq"). The log names the admin and the function only.
 */
@Slf4j
@Service
public class FaqService {

    public record FaqView(String cs, Long version, FaqDocument content, Instant updatedAt) {
    }

    public record FaqRequest(FaqDocument content, Long version) {
    }

    record FaqChanged(String cs) {
    }

    private final FunctionFaqRepository faqs;
    private final AdminGuards guards;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;
    private final OrgStreamService orgStream;

    public FaqService(FunctionFaqRepository faqs, AdminGuards guards, ObjectMapper json,
                      ApplicationEventPublisher events, OrgStreamService orgStream) {
        this.faqs = faqs;
        this.guards = guards;
        this.json = json;
        this.events = events;
        this.orgStream = orgStream;
    }

    @Transactional(readOnly = true)
    public FaqView get(String cs) {
        FunctionFaq faq = faqs.findById(cs)
                .orElseThrow(() -> AdminEditException.notFound("NO_FAQ", "No FAQ for " + cs + "."));
        return view(faq);
    }

    @Transactional
    public FaqView update(String cs, FaqRequest request, String admin) {
        guards.requireKnownFunction(cs);
        if (request == null || request.content() == null) {
            throw AdminEditException.badRequest("BODY_MISSING", "the content is missing");
        }
        FaqDocument content = FaqRules.validate(request.content());
        FunctionFaq faq = faqs.findById(cs).orElse(null);
        if (faq == null) {
            if (request.version() != null) {
                throw AdminEditException.conflict("CHANGED", "Changed by someone else: reload the FAQ.");
            }
            faq = FunctionFaq.builder().cs(cs).build();
        } else if (!Objects.equals(request.version(), faq.getVersion())) {
            throw AdminEditException.conflict("CHANGED", "Changed by someone else: reload the FAQ.");
        }
        String text = write(content);
        if (text.equals(faq.getContent())) {
            return view(faq);
        }
        faq.setContent(text);
        faq.setUpdatedBy(admin);
        FunctionFaq saved = faqs.saveAndFlush(faq);
        log.info("Admin {} changed the FAQ of {}", admin, cs);
        events.publishEvent(new FaqChanged(cs));
        return view(saved);
    }

    /** The other open pages load the FAQ again once the change has committed */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFaqChanged(FaqChanged change) {
        orgStream.publishOrgUpdated("faq");
    }

    private FaqView view(FunctionFaq faq) {
        try {
            return new FaqView(faq.getCs(), faq.getVersion(), json.readValue(faq.getContent(), FaqDocument.class),
                    faq.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the FAQ of " + faq.getCs() + " is not a valid document", e);
        }
    }

    private String write(FaqDocument content) {
        try {
            return json.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("the FAQ cannot be written", e);
        }
    }
}
