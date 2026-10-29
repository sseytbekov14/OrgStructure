package cs_orgchart.photo;

import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.EmployeeSourceProperties;
import cs_orgchart.employee.visibility.EmployeeVisibilityRepository;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Arrays;

/**
 * GET /api/photos/{employeeId}: the stored photo of one employee (from AD, or uploaded on the site), as the other
 * /api/** endpoints only for those who may see the site (no security rule of its own). Always the re-encoded JPEG with
 * nosniff, never anything else; 304 for a matching If-None-Match. 404 when there is no PRESENT photo, the employee is
 * hidden or disabled, outside db mode, or for an AD photo while AD photos are off (AD_PHOTOS_ENABLED; uploaded photos
 * do not depend on it). One photo per request: there is no listing.
 */
@RestController
public class PhotoController {

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();

    private final EmployeePhotoRepository photos;
    private final EmployeeRepository employees;
    private final EmployeeVisibilityRepository visibility;
    private final PhotoProperties properties;
    private final EmployeeSourceProperties source;

    public PhotoController(EmployeePhotoRepository photos, EmployeeRepository employees,
                           EmployeeVisibilityRepository visibility, PhotoProperties properties,
                           EmployeeSourceProperties source) {
        this.photos = photos;
        this.employees = employees;
        this.visibility = visibility;
        this.properties = properties;
        this.source = source;
    }

    @GetMapping("/api/photos/{employeeId}")
    public ResponseEntity<byte[]> photo(@PathVariable long employeeId,
                                        @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                        String ifNoneMatch) {
        if (!source.isDb() || !servable(employeeId)) {
            return ResponseEntity.notFound().build();
        }
        EmployeePhoto photo = photos.findById(employeeId).orElse(null);
        if (photo == null || photo.getState() != PhotoState.PRESENT || photo.getContent() == null
                || photo.getSha256() == null
                || (photo.getSource() != PhotoSource.MANUAL && !properties.isAdEnabled())) {
            return ResponseEntity.notFound().build();
        }
        String etag = "\"" + photo.getSha256().trim() + "\"";
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(CACHE)
                    .header("X-Content-Type-Options", "nosniff").build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .eTag(etag)
                .cacheControl(CACHE)
                .body(photo.getContent());
    }

    /** Enabled and not hidden; managers outside Central Services (external) too: their card shows the photo */
    private boolean servable(long employeeId) {
        Employee employee = employees.findById(employeeId).orElse(null);
        return employee != null && employee.isEnabled()
                && visibility.findById(employeeId).map(v -> !v.isHidden()).orElse(true);
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null) {
            return false;
        }
        return Arrays.stream(ifNoneMatch.split(",")).map(String::trim)
                .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
                .anyMatch(tag -> tag.equals(etag) || tag.equals("*"));
    }
}
