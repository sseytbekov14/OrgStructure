package cs_orgchart.photo;

/**
 * Published after a photo phase that stored, replaced or removed photos: the employee directory is rebuilt (the
 * photo URLs carry the new hashes) and the open pages get org-updated.
 */
public record PhotosChanged(int updated, int removed) {
}
