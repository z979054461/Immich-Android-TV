package nl.giejay.android.tv.immich.security;

import org.junit.Test;

public class PrivateThumbnailCacheTest {
    @Test public void realFilesAndRevocationRaces() throws Exception {
        PrivateThumbnailCacheScenarios.runAll();
    }
}
