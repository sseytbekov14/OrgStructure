package cs_orgchart.photo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The employee photos from AD (thumbnailPhoto), mapped in application.yaml. The photos are used only with
 * EMPLOYEE_SOURCE=db and AD_PHOTOS_ENABLED=true; otherwise the site shows the folder photos exactly as before.
 */
@Data
@ConfigurationProperties("app.photos")
public class PhotoProperties {

    /** Where a photo may come from, in the order of PHOTO_SOURCE_PRIORITY */
    public enum Source { AD, FOLDER }

    /** AD_PHOTOS_ENABLED (default off: extra AD reads, and storing photos needs the information-security approval) */
    private boolean adEnabled = false;
    /** PHOTO_SOURCE_PRIORITY: ad,folder (default) or folder,ad; the initials placeholder is always the last resort */
    private String sourcePriority = "ad,folder";
    /** AD_PHOTOS_MAX_BYTES: larger thumbnailPhoto values are INVALID */
    private int maxBytes = 204_800;
    /**
     * AD_PHOTOS_MAX_SIDE: a declared width or height above it is INVALID (checked before decoding); valid photos are
     * stored scaled down to {@link PhotoValidator#STORED_MAX_SIDE}
     */
    private int maxSide = 4096;
    /** AD_PHOTOS_TIME_BUDGET: the photo phase of one run stops after this long; the next run continues */
    private Duration timeBudget = Duration.ofSeconds(120);

    /**
     * {@link #sourcePriority} parsed: ad and/or folder, each once
     *
     * @throws IllegalStateException for any other value (the application does not start with a wrong setting)
     */
    public List<Source> priority() {
        List<Source> sources = new ArrayList<>();
        for (String part : (sourcePriority == null ? "" : sourcePriority).split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Source source;
            try {
                source = Source.valueOf(name);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("PHOTO_SOURCE_PRIORITY=" + sourcePriority
                        + ": use ad,folder or folder,ad (unknown source " + part.trim() + ")");
            }
            if (sources.contains(source)) {
                throw new IllegalStateException("PHOTO_SOURCE_PRIORITY=" + sourcePriority + " names "
                        + part.trim() + " twice");
            }
            sources.add(source);
        }
        if (sources.isEmpty()) {
            throw new IllegalStateException("PHOTO_SOURCE_PRIORITY is empty: use ad,folder or folder,ad");
        }
        return List.copyOf(sources);
    }
}
