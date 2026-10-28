package cs_orgchart.employee;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The photos of app.data.photos-path: a file named after the e-mail (or its local part) with .jpg, .png, .jpeg or
 * .webp, otherwise the default photo. The same for Excel and database employees.
 */
@Component
public class EmployeePhotos {

    public static final String DEFAULT = "/photos/default.jpg";
    private static final String[] EXTENSIONS = {".jpg", ".png", ".jpeg", ".webp"};

    @Value("${app.data.photos-path}")
    private String photosPath;

    /** The photo files: name in lower case → name */
    public Map<String, String> files() {
        Map<String, String> photoFilesMap = new HashMap<>();
        File photosDir = new File(photosPath);
        if (photosDir.exists() && photosDir.isDirectory()) {
            File[] files = photosDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    photoFilesMap.put(f.getName().toLowerCase(), f.getName());
                }
            }
        }
        return photoFilesMap;
    }

    public static String urlFor(String email, Map<String, String> photoFilesMap) {
        String url = folderUrl(email, photoFilesMap);
        return url == null ? DEFAULT : url;
    }

    /** The URL of the photo file of this e-mail, or null when the folder has none */
    public static String folderUrl(String email, Map<String, String> photoFilesMap) {
        if (email == null || email.trim().isEmpty()) {
            return null;
        }

        String cleanEmail = email.trim().toLowerCase();
        int atIndex = cleanEmail.indexOf('@');
        String username = atIndex > 0 ? cleanEmail.substring(0, atIndex) : cleanEmail;

        // 1. Check full email with extensions
        for (String ext : EXTENSIONS) {
            String exactName = photoFilesMap.get(cleanEmail + ext);
            if (exactName != null) {
                return "/photos/" + exactName;
            }
        }

        // 2. Check username with extensions
        for (String ext : EXTENSIONS) {
            String exactName = photoFilesMap.get(username + ext);
            if (exactName != null) {
                return "/photos/" + exactName;
            }
        }

        return null;
    }
}
