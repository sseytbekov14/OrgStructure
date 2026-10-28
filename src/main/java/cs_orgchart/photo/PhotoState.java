package cs_orgchart.photo;

/** What the last check of an employee's thumbnailPhoto found */
public enum PhotoState {
    /** A valid image: stored as a re-encoded JPEG */
    PRESENT,
    /** AD has no photo: nothing stored; not read again until whenChanged changes */
    NONE,
    /** AD has a value that is not a usable JPEG or PNG: nothing stored */
    INVALID
}
