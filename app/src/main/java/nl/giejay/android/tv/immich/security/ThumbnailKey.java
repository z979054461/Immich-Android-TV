package nl.giejay.android.tv.immich.security;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A deliberately closed set of cacheable resources. Never accepts a URL or original/video. */
public final class ThumbnailKey {
    public enum Size { THUMBNAIL, PREVIEW }

    private final String identity;

    private ThumbnailKey(String kind, UUID id, String size, boolean edited, String version) {
        if (id == null || version == null || version.length() > 128) {
            throw new IllegalArgumentException("Invalid thumbnail identity");
        }
        // Length-prefix the version so there can be no separator collisions. This value
        // is only used as authenticated data/in a digest, never written as metadata.
        identity = kind + "\n" + id + "\n" + size + "\n" + edited + "\n"
                + version.length() + ":" + version;
    }

    public static ThumbnailKey asset(UUID id, Size size, boolean edited, String version) {
        if (size == null) throw new IllegalArgumentException("Missing thumbnail size");
        return new ThumbnailKey("asset", id, size.name(), edited, version);
    }

    public static ThumbnailKey person(UUID id, String version) {
        return new ThumbnailKey("person", id, "THUMBNAIL", false, version);
    }

    byte[] authenticatedIdentity() { return identity.getBytes(StandardCharsets.UTF_8); }

    @Override public String toString() { return "ThumbnailKey(redacted)"; }
}
