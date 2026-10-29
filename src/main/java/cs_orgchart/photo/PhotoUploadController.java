package cs_orgchart.photo;

import cs_orgchart.employee.override.AdminEditException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Photos uploaded on the site ({@link PhotoUploadService}). The body is the image itself (image/jpeg or image/png,
 * at most {@link PhotoUploadService#MAX_UPLOAD_BYTES}); CSRF as every write.
 * <ul>
 *   <li>PUT / DELETE /api/me/photo: the signed-in person's own photo. Who that is comes from the session only (their
 *       e-mail or UPN): there is no parameter to name somebody else;</li>
 *   <li>PUT / DELETE /api/manage/employees/{id}/photo: anybody's, for admins.</li>
 * </ul>
 * Refusals: {"status", "code", "message"} (AdminEditErrors); 415 UNSUPPORTED_TYPE for another content type.
 */
@RestController
public class PhotoUploadController {

    private final PhotoUploadService service;

    public PhotoUploadController(PhotoUploadService service) {
        this.service = service;
    }

    @PutMapping("/api/me/photo")
    public PhotoUploadService.Uploaded uploadOwn(HttpServletRequest request, Authentication authentication)
            throws IOException {
        long id = service.self(authentication).getId();
        return service.upload(id, body(request), authentication.getName(), true);
    }

    @DeleteMapping("/api/me/photo")
    public ResponseEntity<Void> removeOwn(Authentication authentication) {
        service.remove(service.self(authentication).getId(), authentication.getName(), true);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/manage/employees/{id}/photo")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public PhotoUploadService.Uploaded upload(@PathVariable long id, HttpServletRequest request,
                                              Authentication admin) throws IOException {
        return service.upload(id, body(request), admin.getName(), false);
    }

    @DeleteMapping("/api/manage/employees/{id}/photo")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> remove(@PathVariable long id, Authentication admin) {
        service.remove(id, admin.getName(), false);
        return ResponseEntity.noContent().build();
    }

    /**
     * The image bytes, never more than the limit read into memory. The content type is checked here, not with
     * {@code consumes}: that would answer 415 before the access check, and a non-admin must get 403 first.
     */
    static byte[] body(HttpServletRequest request) throws IOException {
        if (!isImage(request.getContentType())) {
            throw new AdminEditException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_TYPE",
                    "send the photo as image/jpeg or image/png");
        }
        if (request.getContentLengthLong() > PhotoUploadService.MAX_UPLOAD_BYTES) {
            throw tooLarge();
        }
        byte[] bytes = request.getInputStream().readNBytes(PhotoUploadService.MAX_UPLOAD_BYTES + 1);
        if (bytes.length > PhotoUploadService.MAX_UPLOAD_BYTES) {
            throw tooLarge();
        }
        return bytes;
    }

    private static boolean isImage(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            return MediaType.IMAGE_JPEG.equalsTypeAndSubtype(type) || MediaType.IMAGE_PNG.equalsTypeAndSubtype(type);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static AdminEditException tooLarge() {
        return new AdminEditException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE",
                "the photo is larger than " + PhotoUploadService.MAX_UPLOAD_BYTES / (1024 * 1024) + " MB");
    }
}
