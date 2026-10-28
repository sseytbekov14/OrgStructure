package cs_orgchart.access;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin page (U1) is not a static file: it lives in classpath:pages/ and is served here to admins only
 * (403 for everybody else; anonymous callers go to sign-in). Its data comes from /api/manage/**, which checks again.
 */
@Controller
public class AdminPageController {

    private static final Resource PAGE = new ClassPathResource("pages/admin.html");

    @GetMapping({"/admin.html", "/admin"})
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(PAGE);
    }
}
