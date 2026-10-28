package cs_orgchart.employee;

import cs_orgchart.photo.PhotoProperties.Source;

import java.util.List;
import java.util.Map;

/**
 * The photoUrl of an employee for one directory build: the stored AD photo ({@code /api/photos/{id}?v=<first 8
 * characters of the hash>}, the version busts the browser cache), the photo file of the folder, in the order of
 * PHOTO_SOURCE_PRIORITY, else {@link EmployeePhotos#DEFAULT} (the pages show the initials). Without AD photos (Excel
 * mode, AD_PHOTOS_ENABLED=false) exactly {@link EmployeePhotos#urlFor}.
 */
public final class PhotoLinks {

    private final Map<String, String> files;
    private final Map<Long, String> adHashes;
    private final List<Source> priority;

    private PhotoLinks(Map<String, String> files, Map<Long, String> adHashes, List<Source> priority) {
        this.files = files;
        this.adHashes = adHashes;
        this.priority = priority;
    }

    /** The folder only: as before the AD photos */
    public static PhotoLinks folderOnly(Map<String, String> files) {
        return new PhotoLinks(files, Map.of(), List.of(Source.FOLDER));
    }

    /**
     * @param adHashes employee id → SHA-256 of the stored PRESENT photo (no bytes)
     */
    public static PhotoLinks of(Map<String, String> files, Map<Long, String> adHashes, List<Source> priority) {
        return new PhotoLinks(files, Map.copyOf(adHashes), List.copyOf(priority));
    }

    public String urlFor(Long employeeId, String email) {
        for (Source source : priority) {
            String url = switch (source) {
                case AD -> adUrl(employeeId);
                case FOLDER -> EmployeePhotos.folderUrl(email, files);
            };
            if (url != null) {
                return url;
            }
        }
        return EmployeePhotos.DEFAULT;
    }

    private String adUrl(Long employeeId) {
        String hash = employeeId == null ? null : adHashes.get(employeeId);
        return hash == null ? null : "/api/photos/" + employeeId + "?v=" + hash.substring(0, Math.min(8, hash.length()));
    }
}
