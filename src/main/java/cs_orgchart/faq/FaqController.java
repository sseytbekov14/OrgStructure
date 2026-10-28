package cs_orgchart.faq;

import cs_orgchart.faq.FaqService.FaqRequest;
import cs_orgchart.faq.FaqService.FaqView;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/functions/{cs}/faq for everybody who may see the site (with an ETag: an unchanged FAQ answers 304), PUT
 * /api/manage/functions/{cs}/faq for admins (version: optimistic locking, 409 when changed meanwhile). Both modes.
 */
@RestController
public class FaqController {

    private final FaqService service;

    public FaqController(FaqService service) {
        this.service = service;
    }

    static String etag(FaqView view) {
        return "\"faq-" + Integer.toHexString(view.cs().hashCode()) + "-" + view.version() + "\"";
    }

    @GetMapping("/api/functions/{cs}/faq")
    public ResponseEntity<FaqView> faq(@PathVariable String cs,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        FaqView view = service.get(cs);
        String etag = etag(view);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(view);
    }

    @PutMapping("/api/manage/functions/{cs}/faq")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<FaqView> update(@PathVariable String cs, @RequestBody FaqRequest request,
                                          Authentication admin) {
        FaqView view = service.update(cs, request, admin.getName());
        return ResponseEntity.ok().eTag(etag(view)).body(view);
    }
}
