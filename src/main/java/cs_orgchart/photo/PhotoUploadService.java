package cs_orgchart.photo;

import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.EmployeeSourceProperties;
import cs_orgchart.employee.override.AdminEditException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Photos uploaded on the site (EMPLOYEE_SOURCE=db): every employee their own photo, admins anybody's. The upload is
 * UNTRUSTED like an AD value and goes through the same {@link PhotoValidator} (JPEG/PNG by the magic bytes, limits
 * checked before decoding, re-encoded as a clean JPEG of at most {@link PhotoValidator#STORED_MAX_SIDE} px). Stored as
 * a {@link PhotoSource#MANUAL} row, which wins over AD and which the photo sync never reads or overwrites; removing it
 * lets the AD photo show again (the photo phase runs at once when AD photos are on). Published at once: admins can
 * remove an unsuitable photo. Logs ids and the uploader only, never the bytes.
 */
@Slf4j
@Service
public class PhotoUploadService {

    /** The page sends the photo scaled down to {@code PHOTO_UPLOAD_SIDE} px (a few hundred KB) */
    public static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
    /** A larger side is refused from the header, before decoding */
    static final int MAX_UPLOAD_SIDE = 4096;

    /** An upload stored: the URL the pages show it with (the version busts the cache) */
    public record Uploaded(long employeeId, String photoUrl) {
    }

    private final EmployeeRepository employees;
    private final EmployeePhotoRepository photos;
    private final EmployeeSourceProperties source;
    private final ApplicationEventPublisher events;
    private final ObjectProvider<PhotoSyncService> photoSync;
    private final PhotoValidator validator = new PhotoValidator(MAX_UPLOAD_BYTES, MAX_UPLOAD_SIDE);

    public PhotoUploadService(EmployeeRepository employees, EmployeePhotoRepository photos,
                              EmployeeSourceProperties source, ApplicationEventPublisher events,
                              ObjectProvider<PhotoSyncService> photoSync) {
        this.employees = employees;
        this.photos = photos;
        this.source = source;
        this.events = events;
        this.photoSync = photoSync;
    }

    /** Uploads need the employees of the database (EMPLOYEE_SOURCE=db) */
    public boolean enabled() {
        return source.isDb();
    }

    /** The employee of the site the signed-in person is (their e-mail or UPN), if any */
    public Optional<Employee> signedIn(Authentication authentication) {
        if (!enabled() || authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken || authentication.getName() == null
                || authentication.getName().isBlank()) {
            return Optional.empty();
        }
        List<Employee> found = employees.findBySignInName(authentication.getName().trim().toLowerCase(Locale.ROOT));
        return found.stream().findFirst();
    }

    /** The own employee of the signed-in person, else 404 (also on the open site: nobody is signed in) */
    public Employee self(Authentication authentication) {
        requireEnabled();
        return signedIn(authentication).orElseThrow(() -> AdminEditException.notFound("NOT_AN_EMPLOYEE",
                "your sign-in is not one of the people on the site"));
    }

    /**
     * Stores the photo of an employee (replacing an AD or an earlier uploaded one)
     *
     * @param by   the sign-in name of the uploader
     * @param self true: the person's own photo; false: an admin's upload
     */
    public Uploaded upload(long employeeId, byte[] raw, String by, boolean self) {
        requireEnabled();
        Employee employee = person(employeeId);
        PhotoValidator.Result result = validator.validate(raw);
        if (!result.valid()) {
            log.info("Photo upload for employee id={} by {} refused: {}", employee.getId(), by, result.reason());
            throw AdminEditException.badRequest("INVALID_PHOTO",
                    "the file is not a usable JPEG or PNG photo (" + result.reason() + ")");
        }
        photos.save(EmployeePhoto.builder().employeeId(employee.getId()).state(PhotoState.PRESENT)
                .content(result.content()).contentType(result.contentType()).width(result.width())
                .height(result.height()).sizeBytes(result.content().length).sha256(result.sha256())
                .checkedAt(Instant.now()).source(PhotoSource.MANUAL).uploadedBy(by).build());
        log.info("Photo of employee id={} uploaded by {} ({})", employee.getId(), by, self ? "own photo" : "admin");
        events.publishEvent(new PhotosChanged(1, 0));
        return new Uploaded(employee.getId(),
                "/api/photos/" + employee.getId() + "?v=" + result.sha256().substring(0, 8));
    }

    /** Removes the uploaded photo of an employee: the AD photo (or the folder, or the initials) shows again */
    public void remove(long employeeId, String by, boolean self) {
        requireEnabled();
        Employee employee = person(employeeId);
        EmployeePhoto photo = photos.findById(employee.getId())
                .filter(p -> p.getSource() == PhotoSource.MANUAL)
                .orElseThrow(() -> AdminEditException.notFound("NO_UPLOADED_PHOTO",
                        "there is no uploaded photo to remove"));
        photos.delete(photo);
        log.info("Uploaded photo of employee id={} removed by {} ({})", employee.getId(), by,
                self ? "own photo" : "admin");
        events.publishEvent(new PhotosChanged(0, 1));
        photoSync.ifAvailable(PhotoSyncService::refreshSoon);
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new AdminEditException(HttpStatus.CONFLICT, "PHOTOS_DISABLED",
                    "uploading photos requires EMPLOYEE_SOURCE=db");
        }
    }

    /** Enabled and of Central Services: managers outside it and people who left get no uploaded photo */
    private Employee person(long employeeId) {
        return employees.findById(employeeId)
                .filter(e -> e.isEnabled() && !e.isExternal())
                .orElseThrow(() -> AdminEditException.notFound("NOT_FOUND", "no such person on the site"));
    }
}
