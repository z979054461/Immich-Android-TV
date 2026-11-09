package nl.giejay.android.tv.immich.security;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free scenarios also called by JUnit. All I/O is synthetic temporary files. */
public final class PrivateThumbnailCacheScenarios {
    private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String SERVER = PrivateThumbnailCache.serverFingerprint("https://synthetic.invalid");
    private static final ThumbnailKey KEY = ThumbnailKey.asset(ASSET, ThumbnailKey.Size.THUMBNAIL, false, "v1");
    private static int checks;

    public static void main(String[] args) throws Exception { runAll(); }
    public static void runAll() throws Exception {
        checks = 0;
        roundTripAndOpaqueFiles();
        profilesAndFreshSessionsCannotHit();
        configIdentityCannotBeSubstituted();
        everyRevocationStage();
        revokedLeaseCannotRead();
        processRestartCannotDecrypt();
        corruptedEntryFallsBack();
        copiedCiphertextFailsAuthenticatedIdentity();
        invalidBodiesNeverCache();
        cacheOffNeverCreatesFiles();
        quotaCountsParallelReservationsAndTemporaryFiles();
        lruEvictsOldestEntry();
        diskFullIsNetworkFallback();
        cancellationDuringBlockingNetwork();
        cleanupCannotDeleteNewSession();
        retiredConnectionAndKeyCannotRevive();
        capacityChangesKeepNetworkAndCancelOldReservations();
        concurrentCommitCannotDelayRevocationOrReviveFiles();
        storageChangesFenceInflightWritesWithoutBreakingBrowsing();
        delayedStorageCleanupCannotRemoveNewGeneration();
        clearedCacheHitFallsBackToFreshNetwork();
        explicitClearPreservesSessionAndRejectsPendingWrites();
        explicitClearReportsCleanupFailure();
        decoderFailureEvictsOnlyItsExactEntry();
        decoderRejectionNeverWaitsForDisk();
        rejectionMemoryAndCleanupQueueAreBounded();
        System.out.println("Private thumbnail cache: " + checks + " assertions passed");
    }

    private static final class PendingCleanup implements Executor {
        final List<Runnable> work = new ArrayList<>();
        @Override public synchronized void execute(Runnable job) { work.add(job); }
        void run() {
            List<Runnable> pending;
            synchronized (this) { pending = new ArrayList<>(work); work.clear(); }
            pending.forEach(Runnable::run);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final File directory = Files.createTempDirectory("immich-synthetic-cache-").toFile();
        final PendingCleanup cleanup = new PendingCleanup();
        final PrivateThumbnailCache cache;
        Fixture() throws IOException { this(8192, 1024, stage -> {}); }
        Fixture(long capacity, int max, PrivateThumbnailCache.Hooks hooks) throws IOException {
            cache = new PrivateThumbnailCache(directory, cleanup, capacity, max, hooks);
        }
        PrivateThumbnailCache.Session session() { return session(PROFILE); }
        PrivateThumbnailCache.Session session(UUID profile) {
            return cache.beginSession(new Object(), profile, SERVER, () -> true);
        }
        List<File> files() throws IOException {
            try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(directory.toPath())) {
                List<File> files = new ArrayList<>();
                stream.filter(Files::isRegularFile).forEach(path -> files.add(path.toFile()));
                return files;
            }
        }
        @Override public void close() throws IOException {
            cache.revoke(); cleanup.run();
            try (java.util.stream.Stream<java.nio.file.Path> stream = Files.walk(directory.toPath())) {
                stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private static class Source implements PrivateThumbnailCache.Source {
        final byte[] body;
        final AtomicInteger opened = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        int status = 200;
        String type = "image/jpeg";
        long length;
        Source(byte[] body) { this.body = body; length = body.length; }
        @Override public PrivateThumbnailCache.Response open() throws IOException {
            opened.incrementAndGet();
            if (cancelled.get()) throw new IOException("Synthetic source cancelled");
            return new PrivateThumbnailCache.Response(status, type, length, new ByteArrayInputStream(body));
        }
        @Override public void cancel() { cancelled.set(true); }
    }

    private static byte[] image(String marker) {
        byte[] text = marker.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[text.length + 3];
        bytes[0] = (byte) 255; bytes[1] = (byte) 216; bytes[2] = (byte) 255;
        System.arraycopy(text, 0, bytes, 3, text.length);
        return bytes;
    }
    private static byte[] bytes(InputStream input) throws IOException {
        try (InputStream closeable = input) { return closeable.readAllBytes(); }
    }
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private interface Failing { void run() throws Exception; }
    private static void fails(Failing job) throws Exception {
        try { job.run(); throw new AssertionError("Expected IOException"); }
        catch (IOException expected) { checks++; }
    }
    private static void equal(byte[] expected, byte[] actual) { check(Arrays.equals(expected, actual), "bytes differ"); }

    private static void roundTripAndOpaqueFiles() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            byte[] image = image("private-person-name https://synthetic.invalid/api synthetic-api-key");
            Source first = new Source(image);
            equal(image, bytes(f.cache.load(session, KEY, first)));
            Source second = new Source(image("wrong"));
            equal(image, bytes(f.cache.load(session, KEY, second)));
            check(first.opened.get() == 1 && second.opened.get() == 0, "cache must hit");
            check(f.files().size() == 1, "one real encrypted file");
            for (File file : f.files()) {
                byte[] encoded = Files.readAllBytes(file.toPath());
                check(encoded[0] == 'I' && encoded[1] == 'T', "versioned envelope");
                String disk = new String(encoded, StandardCharsets.ISO_8859_1);
                check(!disk.contains("https://") && !disk.contains("private-person-name") &&
                        !disk.contains("synthetic-api-key") && !disk.contains(SERVER), "no metadata/plaintext on disk");
                check(!contains(encoded, image) && !Arrays.equals(Arrays.copyOf(encoded, 3),
                        new byte[] {(byte)255, (byte)216, (byte)255}) &&
                        !Arrays.equals(Arrays.copyOf(encoded, 4), new byte[] {(byte)137, 80, 78, 71}),
                        "not a plaintext JPEG/PNG file");
                check(file.getName().matches("[0-9a-f]{64}\\.enc"), "hashed filename");
                check(!file.getAbsolutePath().contains(PROFILE.toString()), "no profile in path");
            }
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
            return true;
        }
        return false;
    }

    private static void profilesAndFreshSessionsCannotHit() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session old = f.session();
            bytes(f.cache.load(old, KEY, new Source(image("account-one"))));
            PrivateThumbnailCache.Session other = f.session(OTHER);
            Source network = new Source(image("account-two"));
            equal(network.body, bytes(f.cache.load(other, KEY, network)));
            check(network.opened.get() == 1, "same server/asset across profiles cannot hit");
            fails(() -> f.cache.load(old, KEY, new Source(image("never"))));
            PrivateThumbnailCache.Session reopened = f.session();
            Source fresh = new Source(image("reopened"));
            equal(fresh.body, bytes(f.cache.load(reopened, KEY, fresh)));
            check(fresh.opened.get() == 1, "same profile fresh nonce cannot hit old data");
        }
    }

    private static void configIdentityCannotBeSubstituted() throws Exception {
        try (Fixture f = new Fixture()) {
            Object config = new String("config");
            AtomicBoolean current = new AtomicBoolean(true);
            PrivateThumbnailCache.Session session = f.cache.beginSession(config, PROFILE, SERVER, current::get);
            check(f.cache.capture(config, PROFILE) == session, "capture same identity");
            check(f.cache.capture(new String("config"), PROFILE) == null, "equal config is not same identity");
            check(f.cache.capture(config, OTHER) == null, "profile cannot be substituted");
            bytes(f.cache.load(session, KEY, new Source(image("one"))));
            current.set(false);
            Source source = new Source(image("two"));
            fails(() -> f.cache.load(session, KEY, source));
            check(source.opened.get() == 0, "stale predicate cannot start network");
        }
    }

    private static void everyRevocationStage() throws Exception {
        for (PrivateThumbnailCache.Stage stage : PrivateThumbnailCache.Stage.values()) {
            AtomicReference<Fixture> reference = new AtomicReference<>();
            AtomicBoolean enabled = new AtomicBoolean(false);
            try (Fixture f = new Fixture(8192, 1024, reached -> {
                if (enabled.get() && reached == stage) {
                    reference.get().cache.revoke();
                    reference.get().cleanup.run();
                }
            })) {
                reference.set(f);
                PrivateThumbnailCache.Session session = f.session();
                if (stage == PrivateThumbnailCache.Stage.BEFORE_READ || stage == PrivateThumbnailCache.Stage.AFTER_DECRYPT) {
                    bytes(f.cache.load(session, KEY, new Source(image("seed"))));
                }
                enabled.set(true);
                Source source = new Source(image("race"));
                fails(() -> f.cache.load(session, KEY, source));
                f.cleanup.run();
                check(f.files().isEmpty(), "old writer resurrected file at " + stage);
                check(f.cache.accountedBytes() == 0, "reservation leak at " + stage);
            }
        }
    }

    private static void revokedLeaseCannotRead() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            PrivateThumbnailCache.Lease lease = f.cache.load(session, KEY, new Source(image("secret")));
            check(lease.read() == 255, "first read works");
            f.cache.revoke();
            fails(lease::read);
            fails(() -> lease.read(new byte[30]));
            fails(lease::requireCurrent);
            lease.close();
        }
    }

    private static void processRestartCannotDecrypt() throws Exception {
        try (Fixture f = new Fixture()) {
            bytes(f.cache.load(f.session(), KEY, new Source(image("pre-crash"))));
            check(f.files().size() == 1, "crash leaves only ciphertext");
            PendingCleanup delayed = new PendingCleanup();
            PrivateThumbnailCache restarted = new PrivateThumbnailCache(f.directory, delayed, 8192, 1024, stage -> {});
            PrivateThumbnailCache.Session session = restarted.beginSession(new Object(), PROFILE, SERVER, () -> true);
            Source fresh = new Source(image("after-crash"));
            equal(fresh.body, bytes(restarted.load(session, KEY, fresh)));
            check(fresh.opened.get() == 1, "restart cannot open previous session");
            delayed.run();
            check(f.files().size() == 1, "startup cleanup removes orphans but not new generation");
            restarted.revoke(); delayed.run();
        }
    }

    private static void corruptedEntryFallsBack() throws Exception {
        for (int corruption : new int[] {0, 7, -1}) {
            try (Fixture f = new Fixture()) {
                PrivateThumbnailCache.Session session = f.session();
                bytes(f.cache.load(session, KEY, new Source(image("seed"))));
                File file = f.files().get(0);
                byte[] corrupt = Files.readAllBytes(file.toPath());
                corrupt[corruption < 0 ? corrupt.length - 1 : corruption] ^= 1;
                Files.write(file.toPath(), corrupt);
                Source fallback = new Source(image("fallback"));
                equal(fallback.body, bytes(f.cache.load(session, KEY, fallback)));
                check(fallback.opened.get() == 1, "corruption must miss");
            }
        }
    }

    private static void copiedCiphertextFailsAuthenticatedIdentity() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            ThumbnailKey changed = ThumbnailKey.asset(ASSET, ThumbnailKey.Size.PREVIEW, true, "v2");
            bytes(f.cache.load(session, KEY, new Source(image("first"))));
            File first = f.files().get(0);
            byte[] ciphertext = Files.readAllBytes(first.toPath());
            bytes(f.cache.load(session, changed, new Source(image("second"))));
            File second = f.files().stream().filter(file -> !file.equals(first)).findFirst().orElseThrow();
            Files.write(second.toPath(), ciphertext);
            Source fallback = new Source(image("correct"));
            equal(fallback.body, bytes(f.cache.load(session, changed, fallback)));
            check(fallback.opened.get() == 1, "AAD binds size/edited/version/resource");
        }
    }

    private static void invalidBodiesNeverCache() throws Exception {
        List<Source> invalid = new ArrayList<>();
        Source unauthorized = new Source(image("no")); unauthorized.status = 401; invalid.add(unauthorized);
        Source redirect = new Source(image("no")); redirect.status = 302; invalid.add(redirect);
        Source html = new Source("<html>not an image</html>".getBytes(StandardCharsets.UTF_8)); invalid.add(html);
        Source video = new Source(image("no")); video.type = "video/mp4"; invalid.add(video);
        Source json = new Source(image("no")); json.type = "application/json"; invalid.add(json);
        Source declared = new Source(image("no")); declared.length = 1025; invalid.add(declared);
        Source chunked = new Source(new byte[2048]); chunked.length = -1; invalid.add(chunked);
        for (Source source : invalid) {
            try (Fixture f = new Fixture()) {
                fails(() -> f.cache.load(f.session(), KEY, source));
                check(f.files().isEmpty() && f.cache.accountedBytes() == 0, "bad response cannot persist");
            }
        }
    }

    private static void cacheOffNeverCreatesFiles() throws Exception {
        try (Fixture f = new Fixture(0, 1024, stage -> {})) {
            PrivateThumbnailCache.Session session = f.session();
            Source source = new Source(image("network-only"));
            equal(source.body, bytes(f.cache.load(session, KEY, source)));
            equal(source.body, bytes(f.cache.load(session, KEY, source)));
            check(source.opened.get() == 2 && f.files().isEmpty(), "disabled cache remains guarded network-only");
        }
    }

    private static void quotaCountsParallelReservationsAndTemporaryFiles() throws Exception {
        AtomicReference<Fixture> reference = new AtomicReference<>();
        AtomicInteger tempsSeen = new AtomicInteger();
        long capacity = 1057; // One 1024-byte payload + envelope, never two concurrent reservations.
        try (Fixture f = new Fixture(capacity, 1024, stage -> {
            if (stage == PrivateThumbnailCache.Stage.TEMP_OPEN) {
                tempsSeen.incrementAndGet();
                check(reference.get().cache.accountedBytes() <= capacity, "temp reservation exceeds cap");
            }
        })) {
            reference.set(f);
            PrivateThumbnailCache.Session session = f.session();
            CountDownLatch started = new CountDownLatch(1), unblock = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Source blocked = new Source(image("one")) {
                @Override public PrivateThumbnailCache.Response open() throws IOException {
                    started.countDown();
                    try { if (!unblock.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                    catch (InterruptedException e) { throw new IOException(e); }
                    return super.open();
                }
            };
            Thread worker = new Thread(() -> {
                try { bytes(f.cache.load(session, KEY, blocked)); } catch (Throwable e) { failure.set(e); }
            });
            worker.start();
            check(started.await(5, TimeUnit.SECONDS), "first network started");
            check(f.cache.accountedBytes() == capacity, "full reservation counted while network pending");
            ThumbnailKey secondKey = ThumbnailKey.person(ASSET, "1");
            Source second = new Source(image("two"));
            equal(second.body, bytes(f.cache.load(session, secondKey, second)));
            check(f.files().isEmpty(), "second fetch cannot over-reserve/write");
            unblock.countDown(); worker.join(5000);
            check(!worker.isAlive() && failure.get() == null, "parallel fetch finishes");
            check(tempsSeen.get() == 1 && f.cache.accountedBytes() <= capacity, "one cache write within cap");
            check(f.files().stream().mapToLong(File::length).sum() <= capacity, "actual disk bytes within cap");
        }
    }

    private static void lruEvictsOldestEntry() throws Exception {
        try (Fixture f = new Fixture(1130, 1024, stage -> {})) {
            PrivateThumbnailCache.Session session = f.session();
            ThumbnailKey second = ThumbnailKey.person(ASSET, "2");
            bytes(f.cache.load(session, KEY, new Source(image("old"))));
            File first = f.files().get(0); first.setLastModified(1L);
            bytes(f.cache.load(session, second, new Source(image("second"))));
            // Both small entries fit, but reserving a full bounded third entry requires eviction.
            ThumbnailKey third = ThumbnailKey.person(OTHER, "3");
            bytes(f.cache.load(session, third, new Source(image("third"))));
            check(!first.exists(), "oldest entry evicted first");
            check(f.cache.accountedBytes() <= 1130, "LRU stays within cap");
        }
    }

    private static void diskFullIsNetworkFallback() throws Exception {
        try (Fixture f = new Fixture(8192, 1024, stage -> {
            if (stage == PrivateThumbnailCache.Stage.TEMP_OPEN) throw new IOException("Synthetic disk full");
        })) {
            Source source = new Source(image("still-network-image"));
            equal(source.body, bytes(f.cache.load(f.session(), KEY, source)));
            check(f.files().isEmpty() && f.cache.accountedBytes() == 0, "disk-full temp removed, no plaintext fallback");
        }
    }

    private static void cancellationDuringBlockingNetwork() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            CountDownLatch reading = new CountDownLatch(1), cancelSignal = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Source source = new Source(image("delayed")) {
                @Override public PrivateThumbnailCache.Response open() {
                    return new PrivateThumbnailCache.Response(200, "image/jpeg", -1, new InputStream() {
                        @Override public int read() throws IOException {
                            reading.countDown();
                            try { if (!cancelSignal.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                            catch (InterruptedException e) { throw new IOException(e); }
                            return 255;
                        }
                    });
                }
                @Override public void cancel() { super.cancel(); cancelSignal.countDown(); }
            };
            Thread worker = new Thread(() -> {
                try { bytes(f.cache.load(session, KEY, source)); }
                catch (Throwable e) { failure.set(e); }
            });
            worker.start();
            check(reading.await(5, TimeUnit.SECONDS), "network read started");
            f.cache.revoke(); f.cleanup.run();
            worker.join(5000);
            check(source.cancelled.get(), "lock cancels captured request");
            check(failure.get() instanceof IOException && !worker.isAlive(), "read rejects revoked session");
            check(f.files().isEmpty() && f.cache.accountedBytes() == 0, "cancel leaves no persistent payload");
        }
    }

    private static void cleanupCannotDeleteNewSession() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session old = f.session();
            bytes(f.cache.load(old, KEY, new Source(image("old"))));
            PrivateThumbnailCache.Session next = f.session();
            Source source = new Source(image("next"));
            bytes(f.cache.load(next, KEY, source));
            f.cleanup.run();
            equal(source.body, bytes(f.cache.load(next, KEY, source)));
            check(source.opened.get() == 1 && f.files().size() == 1, "delayed cleanup affects only retired generation");
        }
    }

    private static void retiredConnectionAndKeyCannotRevive() throws Exception {
        try (Fixture f = new Fixture()) {
            Object config = new Object();
            PrivateThumbnailCache.Session session = f.cache.beginSession(config, PROFILE, SERVER, () -> true);
            java.lang.reflect.Field keyField = session.getClass().getDeclaredField("key");
            keyField.setAccessible(true);
            byte[] key = (byte[]) keyField.get(session);
            bytes(f.cache.load(session, KEY, new Source(image("sensitive"))));
            for (File file : f.files()) check(!contains(Files.readAllBytes(file.toPath()), key), "key never persisted");
            AtomicInteger delivered = new AtomicInteger();
            check(session.deliverIfCurrent(delivered::incrementAndGet), "current UI delivery works");
            f.cache.revoke();
            check(keyField.get(session) == null && Arrays.equals(key, new byte[32]), "owned key buffer wiped");
            check(!session.deliverIfCurrent(delivered::incrementAndGet) && delivered.get() == 1, "late callback suppressed");
            try {
                f.cache.beginSession(config, PROFILE, SERVER, () -> true);
                throw new AssertionError("retired connection identity revived");
            } catch (IllegalArgumentException expected) { checks++; }
        }
    }

    private static void capacityChangesKeepNetworkAndCancelOldReservations() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            Source source = new Source(image("before-off"));
            bytes(f.cache.load(session, KEY, source));
            f.cache.setCapacity(PrivateThumbnailCache.Capacity.OFF);
            f.cleanup.run();
            check(session.isCurrent() && f.files().isEmpty(), "off clears disk without changing unlocked identity");
            bytes(f.cache.load(session, KEY, source));
            check(source.opened.get() == 2 && f.files().isEmpty(), "off continues guarded network normally");
            f.cache.setCapacity(PrivateThumbnailCache.Capacity.MIB_64);
            bytes(f.cache.load(session, KEY, source));
            check(source.opened.get() == 3 && f.files().size() == 1, "reenable uses same still-unlocked session");
        }
        AtomicReference<Fixture> holder = new AtomicReference<>();
        try (Fixture f = new Fixture(8192, 1024, stage -> {
            if (stage == PrivateThumbnailCache.Stage.AFTER_BODY) {
                holder.get().cache.setCapacity(PrivateThumbnailCache.Capacity.OFF);
            }
        })) {
            holder.set(f);
            Source source = new Source(image("pending"));
            equal(source.body, bytes(f.cache.load(f.session(), KEY, source)));
            check(f.files().isEmpty(), "pre-change reservation cannot commit with cache disabled");
        }
    }

    private static void concurrentCommitCannotDelayRevocationOrReviveFiles() throws Exception {
        CountDownLatch committing = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(8192, 1024, stage -> {
            if (stage == PrivateThumbnailCache.Stage.BEFORE_COMMIT) {
                committing.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                catch (InterruptedException e) { throw new IOException(e); }
            }
        })) {
            PrivateThumbnailCache.Session session = f.session();
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try { bytes(f.cache.load(session, KEY, new Source(image("pending-commit")))); }
                catch (Throwable failure) { outcome.set(failure); }
            });
            writer.start();
            check(committing.await(5, TimeUnit.SECONDS), "writer paused before atomic commit");
            // This returns while the writer still holds its disk mutex. No disk I/O on lock.
            f.cache.revoke();
            check(!session.isCurrent(), "revocation immediate while disk operation is pending");
            release.countDown(); writer.join(5000); f.cleanup.run();
            check(!writer.isAlive() && outcome.get() instanceof IOException, "old commit rejected");
            check(f.files().isEmpty() && f.cache.accountedBytes() == 0, "old writer cannot recreate deleted cache");
        } finally { release.countDown(); }
    }

    private static void storageChangesFenceInflightWritesWithoutBreakingBrowsing() throws Exception {
        for (PrivateThumbnailCache.Stage point : new PrivateThumbnailCache.Stage[] {
                PrivateThumbnailCache.Stage.AFTER_BODY, PrivateThumbnailCache.Stage.TEMP_OPEN,
                PrivateThumbnailCache.Stage.BEFORE_COMMIT }) {
            AtomicReference<Fixture> holder = new AtomicReference<>();
            AtomicBoolean changed = new AtomicBoolean();
            try (Fixture f = new Fixture(8192, 1024, stage -> {
                if (stage == point && changed.compareAndSet(false, true)) {
                    holder.get().cache.setCapacity(PrivateThumbnailCache.Capacity.OFF);
                    holder.get().cache.setCapacity(PrivateThumbnailCache.Capacity.MIB_64);
                    holder.get().cleanup.run();
                }
            })) {
                holder.set(f);
                PrivateThumbnailCache.Session session = f.session();
                Source source = new Source(image("old pending response"));
                equal(source.body, bytes(f.cache.load(session, KEY, source)));
                check(session.isCurrent(), "storage setting does not expire bound media models");
                check(f.files().isEmpty(), "old write cannot repopulate cleared/re-enabled storage at " + point);
                equal(source.body, bytes(f.cache.load(session, KEY, source)));
                check(source.opened.get() == 2 && f.files().size() == 1, "fresh request caches in new storage generation");
                equal(source.body, bytes(f.cache.load(session, KEY, source)));
                check(source.opened.get() == 2, "browsing still gets new-generation hits");
            }
        }
    }

    private static void delayedStorageCleanupCannotRemoveNewGeneration() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            Source source = new Source(image("visible"));
            PrivateThumbnailCache.Lease held = f.cache.load(session, KEY, source);
            f.cache.setCapacity(PrivateThumbnailCache.Capacity.MIB_64);
            equal(source.body, bytes(held)); // Currently authorized pixels need not disappear.
            equal(source.body, bytes(f.cache.load(session, KEY, source)));
            check(f.files().size() == 2, "old and new generation have different hashed paths");
            f.cleanup.run();
            check(f.files().size() == 1, "delayed clear removes only the previous generation");
            equal(source.body, bytes(f.cache.load(session, KEY, source)));
            check(source.opened.get() == 2, "new file survives delayed old cleanup");
        }
    }

    private static void clearedCacheHitFallsBackToFreshNetwork() throws Exception {
        AtomicReference<Fixture> holder = new AtomicReference<>();
        AtomicBoolean cleared = new AtomicBoolean();
        try (Fixture f = new Fixture(8192, 1024, stage -> {
            if (stage == PrivateThumbnailCache.Stage.AFTER_DECRYPT && cleared.compareAndSet(false, true)) {
                holder.get().cache.setCapacity(PrivateThumbnailCache.Capacity.MIB_64);
            }
        })) {
            holder.set(f);
            PrivateThumbnailCache.Session session = f.session();
            bytes(f.cache.load(session, KEY, new Source(image("old cached"))));
            Source fresh = new Source(image("fresh after clear"));
            equal(fresh.body, bytes(f.cache.load(session, KEY, fresh)));
            check(fresh.opened.get() == 1, "a hit cleared during decryption cannot escape the generation fence");
        }
    }

    private static void explicitClearPreservesSessionAndRejectsPendingWrites() throws Exception {
        for (PrivateThumbnailCache.Stage point : new PrivateThumbnailCache.Stage[] {
                PrivateThumbnailCache.Stage.AFTER_BODY, PrivateThumbnailCache.Stage.TEMP_OPEN,
                PrivateThumbnailCache.Stage.BEFORE_COMMIT }) {
            AtomicReference<Fixture> holder = new AtomicReference<>();
            AtomicReference<Boolean> completed = new AtomicReference<>();
            AtomicBoolean cleared = new AtomicBoolean();
            try (Fixture f = new Fixture(8192, 1024, stage -> {
                if (stage == point && cleared.compareAndSet(false, true)) {
                    holder.get().cache.clear(completed::set);
                }
            })) {
                holder.set(f);
                PrivateThumbnailCache.Session session = f.session();
                Source source = new Source(image("pending explicit clear"));
                equal(source.body, bytes(f.cache.load(session, KEY, source)));
                check(completed.get() == null, "clear reports only after background cleanup");
                check(session.isCurrent(), "clear must not replace the unlocked media session");
                check(f.files().isEmpty(), "pending write cannot refill explicitly cleared storage");
                f.cleanup.run();
                check(Boolean.TRUE.equals(completed.get()), "successful physical clear reported");
                equal(source.body, bytes(f.cache.load(session, KEY, source)));
                check(f.files().size() == 1, "same session continues browsing and caching after clear");
            }
        }
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            Source source = new Source(image("visible before clear"));
            PrivateThumbnailCache.Lease held = f.cache.load(session, KEY, source);
            AtomicReference<Boolean> completed = new AtomicReference<>();
            f.cache.clear(completed::set);
            equal(source.body, bytes(held));
            equal(source.body, bytes(f.cache.load(session, KEY, source)));
            f.cleanup.run();
            check(Boolean.TRUE.equals(completed.get()) && f.files().size() == 1,
                    "clear reports old files removed without removing new-generation content");
            equal(source.body, bytes(f.cache.load(session, KEY, source)));
            check(source.opened.get() == 2, "fresh cache survives delayed explicit clear");
        }
    }

    private static void explicitClearReportsCleanupFailure() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache cache = new PrivateThumbnailCache(f.directory,
                    job -> { throw new java.util.concurrent.RejectedExecutionException("Synthetic cleanup failure"); },
                    8192, 1024, stage -> {});
            PrivateThumbnailCache.Session session = cache.beginSession(new Object(), PROFILE, SERVER, () -> true);
            Source source = new Source(image("opaque leftover"));
            bytes(cache.load(session, KEY, source));
            AtomicReference<Boolean> completed = new AtomicReference<>();
            AtomicInteger reports = new AtomicInteger();
            cache.clear(success -> { reports.incrementAndGet(); completed.set(success); });
            check(Boolean.FALSE.equals(completed.get()) && reports.get() == 1, "cleanup rejection reports failure once");
            check(session.isCurrent(), "disk cleanup failure does not lock the user out");
            equal(source.body, bytes(cache.load(session, KEY, source)));
            check(source.opened.get() == 2, "failed cleanup still fences old ciphertext out of cache hits");
            cache.revoke();
        }
    }

    private static void decoderFailureEvictsOnlyItsExactEntry() throws Exception {
        try (Fixture f = new Fixture()) {
            PrivateThumbnailCache.Session session = f.session();
            PrivateThumbnailCache.Lease invalid = f.cache.load(session, KEY, new Source(image("invalid decoder input")));
            PrivateThumbnailCache.EntryToken token = invalid.getEntryToken();
            check(token != null, "committed entry carries exact decoder evidence");
            invalid.close();
            check(f.cache.reject(token), "decoder failure registers its exact encoded entry");
            check(f.files().size() == 1, "rejection returns before asynchronous disk deletion");
            Source replacement = new Source(image("replacement"));
            PrivateThumbnailCache.Lease loaded = f.cache.load(session, KEY, replacement);
            PrivateThumbnailCache.EntryToken replacementToken = loaded.getEntryToken();
            equal(replacement.body, bytes(loaded));
            check(f.cache.reject(token), "late old nonce is registered without synchronous disk verification");
            equal(replacement.body, bytes(f.cache.load(session, KEY, replacement)));
            check(replacement.opened.get() == 1, "old rejection marker does not blacklist a newer IV before cleanup");
            f.cleanup.run();
            equal(replacement.body, bytes(f.cache.load(session, KEY, replacement)));
            check(replacement.opened.get() == 1, "replacement remains a hit");
            f.cache.setCapacity(PrivateThumbnailCache.Capacity.MIB_64);
            Source newGeneration = new Source(image("new generation"));
            equal(newGeneration.body, bytes(f.cache.load(session, KEY, newGeneration)));
            check(!f.cache.reject(replacementToken), "old decoder cannot evict a new storage generation");
            f.cleanup.run();
            equal(newGeneration.body, bytes(f.cache.load(session, KEY, newGeneration)));
            check(newGeneration.opened.get() == 1, "new generation remains cached");
            PrivateThumbnailCache.Session other = f.session(OTHER);
            Source nextAccount = new Source(image("next account"));
            equal(nextAccount.body, bytes(f.cache.load(other, KEY, nextAccount)));
            check(!f.cache.reject(token), "old account decoder token is revoked");
            equal(nextAccount.body, bytes(f.cache.load(other, KEY, nextAccount)));
            check(nextAccount.opened.get() == 1, "other account remains cached");
        }
    }

    private static void decoderRejectionNeverWaitsForDisk() throws Exception {
        AtomicBoolean block = new AtomicBoolean();
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture f = new Fixture(8192, 1024, stage -> {
            if (block.get() && stage == PrivateThumbnailCache.Stage.BEFORE_COMMIT) {
                writing.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timed out"); }
                catch (InterruptedException e) { throw new IOException(e); }
            }
        })) {
            PrivateThumbnailCache.Session session = f.session();
            PrivateThumbnailCache.Lease bad = f.cache.load(session, KEY, new Source(image("bad decoded image")));
            PrivateThumbnailCache.EntryToken token = bad.getEntryToken();
            bad.close();
            block.set(true);
            AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try { bytes(f.cache.load(session, ThumbnailKey.person(OTHER, "busy"), new Source(image("other image")))); }
                catch (Throwable failure) { outcome.set(failure); }
            });
            writer.start();
            check(writing.await(3, TimeUnit.SECONDS), "writer holds global disk mutex");
            long before = System.nanoTime();
            check(f.cache.reject(token), "failure handler registers rejection");
            check(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "failure callback must not wait for disk");
            check(writer.isAlive(), "registration completed while disk was still held");
            release.countDown(); writer.join(5000);
            check(!writer.isAlive() && outcome.get() == null, "unrelated disk writer completes");
            block.set(false);
            // Background deletion deliberately has not run. Rebinding must still use network.
            Source replacement = new Source(image("fresh response"));
            equal(replacement.body, bytes(f.cache.load(session, KEY, replacement)));
            check(replacement.opened.get() == 1, "immediate rebind bypasses the rejected bytes before cleanup");
            f.cleanup.run();
            equal(replacement.body, bytes(f.cache.load(session, KEY, replacement)));
            check(replacement.opened.get() == 1, "delayed old rejection cannot delete replacement nonce");
        } finally { release.countDown(); }
    }

    private static void rejectionMemoryAndCleanupQueueAreBounded() throws Exception {
        try (Fixture f = new Fixture(64 * 1024, 1024, stage -> {})) {
            PrivateThumbnailCache.Session session = f.session();
            List<PrivateThumbnailCache.EntryToken> tokens = new ArrayList<>();
            for (int i = 0; i <= PrivateThumbnailCache.MAX_REJECTED_ENTRIES; i++) {
                ThumbnailKey key = ThumbnailKey.person(new UUID(0, 1000 + i), "bad");
                PrivateThumbnailCache.Lease lease = f.cache.load(session, key, new Source(image("synthetic bad image")));
                tokens.add(lease.getEntryToken());
                lease.close();
            }
            for (PrivateThumbnailCache.EntryToken token : tokens) check(f.cache.reject(token), "reject bad entry");
            check(f.cache.rejectedEntryCount() == PrivateThumbnailCache.MAX_REJECTED_ENTRIES,
                    "pending/failed rejection collection has a fixed bound");
            check(f.cleanup.work.size() == 1, "one coalesced deletion worker, not one task per failure");
            ThumbnailKey last = ThumbnailKey.person(new UUID(0, 1000 + PrivateThumbnailCache.MAX_REJECTED_ENTRIES), "bad");
            Source fallback = new Source(image("network-only after overflow"));
            equal(fallback.body, bytes(f.cache.load(session, last, fallback)));
            check(fallback.opened.get() == 1, "overflow bypasses cache rather than dropping protective markers");
            f.cleanup.run();
            check(f.cache.rejectedEntryCount() == 0, "successful background deletion releases rejection metadata");
            bytes(f.cache.load(session, last, fallback));
            check(fallback.opened.get() == 2, "unrecorded overflow entry remains quarantined");
            f.cache.clear(success -> check(success, "explicit clear succeeds"));
            f.cleanup.run();
            equal(fallback.body, bytes(f.cache.load(session, last, fallback)));
            equal(fallback.body, bytes(f.cache.load(session, last, fallback)));
            check(fallback.opened.get() == 3, "clear resets bounded quarantine and permits normal caching");
            f.cache.revoke();
            check(f.cache.rejectedEntryCount() == 0, "revocation releases rejection metadata");
        }
    }
}
