package cs_orgchart.employee;

import cs_orgchart.photo.PhotoProperties.Source;

import java.util.List;
import java.util.Map;

/**
 * The photoUrl of an employee for one directory build: a photo uploaded on the site always first, then the stored AD
 * photo ({@code /api/photos/{id}?v=<first 8 characters of the hash>}, the version busts the browser cache) and the
 * photo file of the folder in the order of PHOTO_SOURCE_PRIORITY, else {@link EmployeePhotos#DEFAULT} (the pages show
 * the initials). Without stored photos (Excel mode, AD_PHOTOS_ENABLED=false and nothing uploaded) exactly
 * {@link EmployeePhotos#urlFor}.
 */
public final class PhotoLinks {

    private final Map<String, String> files;
    private final Map<Long, String> adHashes;
    private final Map<Long, String> uploadedHashes;
    private final List<Source> priority;

    private PhotoLinks(Map<String, String> files, Map<Long, String> adHashes, Map<Long, String> uploadedHashes,
                       List<Source> priority) {
        this.files = files;
        this.adHashes = adHashes;
        this.uploadedHashes = uploadedHashes;
        this.priority = priority;
    }

    /** The folder only: as before the AD photos */
    public static PhotoLinks folderOnly(Map<String, String> files) {
        return new PhotoLinks(files, Map.of(), Map.of(), List.of(Source.FOLDER));
    }

    /**
     * @param adHashes employee id → SHA-256 of the stored PRESENT photo (no bytes)
     */
    public static PhotoLinks of(Map<String, String> files, Map<Long, String> adHashes, List<Source> priority) {
        return of(files, adHashes, Map.of(), priority);
    }

    /**
     * @param adHashes       employee id → SHA-256 of the stored PRESENT AD photo (no bytes)
     * @param uploadedHashes employee id → SHA-256 of the photo uploaded on the site (wins over everything)
     */
    public static PhotoLinks of(Map<String, String> files, Map<Long, String> adHashes,
                                Map<Long, String> uploadedHashes, List<Source> priority) {
        return new PhotoLinks(files, Map.copyOf(adHashes), Map.copyOf(uploadedHashes), List.copyOf(priority));
    }

    public String urlFor(Long employeeId, String email) {
        String uploaded = storedUrl(employeeId, uploadedHashes);
        if (uploaded != null) {
            return uploaded;
        }
        for (Source source : priority) {
            String url = switch (source) {
                case AD -> storedUrl(employeeId, adHashes);
                case FOLDER -> EmployeePhotos.folderUrl(email, files);
            };
            if (url != null) {
                return url;
            }
        }
        return EmployeePhotos.DEFAULT;
    }

    /** The photo shown is one uploaded on the site (it can be removed again) */
    public boolean isUploaded(Long employeeId) {
        return employeeId != null && uploadedHashes.containsKey(employeeId);
    }

    private static String storedUrl(Long employeeId, Map<Long, String> hashes) {
        String hash = employeeId == null ? null : hashes.get(employeeId);
        return hash == null ? null : "/api/photos/" + employeeId + "?v=" + hash.substring(0, Math.min(8, hash.length()));
    }
}
