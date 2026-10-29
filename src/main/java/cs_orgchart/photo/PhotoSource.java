package cs_orgchart.photo;

/** Where a stored photo comes from */
public enum PhotoSource {
    /** thumbnailPhoto of AD, written by the photo sync */
    AD,
    /** Uploaded on the site by the person or by an admin: wins over AD, never touched by the sync */
    MANUAL
}
