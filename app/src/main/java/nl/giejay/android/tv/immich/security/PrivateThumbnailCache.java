package nl.giejay.android.tv.immich.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Session-only encrypted thumbnail cache. The sole key is random process memory, never
 * an API key, Keystore key or persisted authorization. All sessions share one disk budget.
 * The directory MUST be constructed from Context.cacheDir, never external storage.
 *
 * revoke() invalidates and wipes our key/plaintext arrays synchronously. JCA/JVM and
 * image decoders may keep copies: this does not promise perfect memory zeroization.
 * Files remaining after a crash are unusable and are removed on the next construction.
 */
public final class PrivateThumbnailCache {
    public enum Capacity {
        OFF(0), MIB_64(64), MIB_128(128), MIB_256(256);
        public final long bytes;
        Capacity(long mib) { bytes = mib * 1024L * 1024L; }
        public static Capacity fromMiB(int value) {
            for (Capacity capacity : values()) if (capacity.bytes == value * 1024L * 1024L) return capacity;
            return MIB_128;
        }
    }

    public static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    private static final int TAG_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final byte[] HEADER = new byte[] { 'I', 'T', 'V', 'C', 1 };
    private static final int OVERHEAD = HEADER.length + IV_BYTES + TAG_BYTES;

    /** Must open only the captured session's guarded, allowlisted thumbnail request. */
    public interface Source {
        Response open() throws IOException;
        /** Must be nonblocking (e.g. OkHttp Call.cancel), safe before/after open. */
        void cancel();
    }

    public static final class Response implements AutoCloseable {
        public final int status;
        public final String contentType;
        public final long length;
        public final InputStream body;
        public Response(int status, String contentType, long length, InputStream body) {
            this.status = status;
            this.contentType = contentType;
            this.length = length;
            this.body = body;
        }
        @Override public void close() throws IOException { body.close(); }
    }

    public static final class SessionExpiredException extends IOException {
        SessionExpiredException() { super("Thumbnail session locked or changed"); }
    }

    // Deterministic race/failure injection in package tests. No production logging.
    enum Stage { BEFORE_READ, AFTER_DECRYPT, NETWORK_CHUNK, AFTER_BODY, TEMP_OPEN,
        BEFORE_COMMIT, AFTER_COMMIT, BEFORE_DELIVERY }
    interface Hooks { void at(Stage stage) throws IOException; }
    private static final Hooks NO_HOOKS = stage -> {};

    public final class Session {
        private final Object connectionIdentity;
        private final UUID profileId;
        private final byte[] identity;
        private final File directory;
        private final BooleanSupplier validity;
        private byte[] key;
        private final Set<Source> sources = new HashSet<>();
        private final Set<Lease> leases = new HashSet<>();

        private Session(Object connectionIdentity, UUID profileId, String serverFingerprint,
                        BooleanSupplier validity) {
            this.connectionIdentity = connectionIdentity;
            this.profileId = profileId;
            this.validity = validity;
            byte[] nonce = randomBytes(32);
            identity = ("ITVC1\n" + profileId + "\n" + serverFingerprint + "\n" + hex(nonce))
                    .getBytes(StandardCharsets.UTF_8);
            Arrays.fill(nonce, (byte) 0);
            key = randomBytes(32);
            directory = new File(root, digest(identity));
        }

        /** Connection comparison is referential, never data-class equality. */
        public boolean matches(Object connection, UUID profile) {
            synchronized (state) {
                return connectionIdentity == connection && profileId.equals(profile) && currentLocked(this);
            }
        }
        public boolean isCurrent() { synchronized (state) { return currentLocked(this); } }
        public void requireCurrent() throws SessionExpiredException {
            synchronized (state) { requireCurrentLocked(this); }
        }
        /** Only for a short, already-decoded UI assignment, never I/O or image decoding. */
        public boolean deliverIfCurrent(Runnable delivery) {
            synchronized (state) {
                if (!currentLocked(this)) return false;
                delivery.run();
                return true;
            }
        }
        @Override public String toString() { return "ThumbnailSession(redacted)"; }
    }

    /** Caller owns/ closes this stream; lock wipes it and every subsequent read fails. */
    public final class Lease extends InputStream {
        private final Session session;
        private final EntryToken entry;
        private byte[] bytes;
        private int offset;
        private Lease(Session session, byte[] bytes, EntryToken entry) {
            this.session = session; this.bytes = bytes; this.entry = entry;
        }
        public EntryToken getEntryToken() { return entry; }
        public void requireCurrent() throws IOException {
            synchronized (state) {
                requireCurrentLocked(session);
                if (bytes == null) throw new IOException("Thumbnail stream closed");
            }
        }
        @Override public int read() throws IOException {
            synchronized (state) {
                requireCurrent();
                return offset == bytes.length ? -1 : bytes[offset++] & 255;
            }
        }
        @Override public int read(byte[] target, int start, int count) throws IOException {
            if (target == null) throw new NullPointerException();
            if (start < 0 || count < 0 || start > target.length - count) throw new IndexOutOfBoundsException();
            synchronized (state) {
                requireCurrent();
                if (count == 0) return 0;
                if (offset == bytes.length) return -1;
                int copied = Math.min(count, bytes.length - offset);
                System.arraycopy(bytes, offset, target, start, copied);
                offset += copied;
                return copied;
            }
        }
        @Override public long skip(long count) throws IOException {
            synchronized (state) {
                requireCurrent();
                int skipped = (int) Math.min(Math.max(0, count), bytes.length - offset);
                offset += skipped;
                return skipped;
            }
        }
        @Override public int available() throws IOException {
            synchronized (state) { requireCurrent(); return bytes.length - offset; }
        }
        @Override public void close() {
            synchronized (state) { wipe(); session.leases.remove(this); }
        }
        private void wipe() {
            if (bytes != null) { Arrays.fill(bytes, (byte) 0); bytes = null; }
        }
    }

    /** Opaque evidence of the exact encrypted entry supplied to one decoder attempt. */
    public static final class EntryToken {
        private final Session session;
        private final File file;
        private final long generation;
        private final byte[] iv;
        private final String rejectionId;
        private EntryToken(Session session, File file, long generation, byte[] iv) {
            this.session = session; this.file = file; this.generation = generation; this.iv = iv.clone();
            this.rejectionId = rejectionId(file, iv);
        }
        @Override public String toString() { return "ThumbnailEntry(redacted)"; }
    }

    private static final class Loaded {
        final byte[] bytes;
        final EntryToken entry;
        Loaded(byte[] bytes, EntryToken entry) { this.bytes = bytes; this.entry = entry; }
    }

    private final File root;
    private final Executor cleanup;
    private final SecureRandom random = new SecureRandom();
    private final Object state = new Object();
    private final Object disk = new Object();
    // Never hold this monitor while taking disk/state locks or doing I/O.
    private final Object rejectionState = new Object();
    private final Hooks hooks;
    private final int maxEntry;
    private final boolean diskAvailable;
    private final Set<Reservation> reservations = new HashSet<>();
    static final int MAX_REJECTED_ENTRIES = 256;
    private static final class Rejection {
        final EntryToken entry;
        boolean pending = true;
        Rejection(EntryToken entry) { this.entry = entry; }
    }
    private final Map<String, Rejection> rejectedEntries = new HashMap<>();
    private boolean rejectionCleanupScheduled;
    private Session bypassSession;
    private long bypassGeneration;
    private final List<WeakReference<Object>> usedConnections = new ArrayList<>();
    private volatile long budget;
    // Written under disk, also read by the UI's memory-only rejection gate. Changes invalidate every older writer,
    // without extending authorization or invalidating currently displayed/decoded images.
    private volatile long storageGeneration;
    private volatile Session active;

    public PrivateThumbnailCache(File appCacheDir, Executor cleanup, Capacity capacity) throws IOException {
        this(appCacheDir, cleanup, capacity.bytes, MAX_ENTRY_BYTES, NO_HOOKS);
    }

    PrivateThumbnailCache(File appCacheDir, Executor cleanup, long budget, int maxEntry, Hooks hooks)
            throws IOException {
        if (budget < 0 || budget > Capacity.MIB_256.bytes || maxEntry < 1 || maxEntry > MAX_ENTRY_BYTES) {
            throw new IllegalArgumentException("Invalid cache limits");
        }
        File privateRoot;
        boolean diskAvailable;
        try {
            privateRoot = new File(appCacheDir.getCanonicalFile(), "private_thumbnails");
            diskAvailable = privateRoot.getCanonicalFile().equals(privateRoot.getAbsoluteFile());
        } catch (IOException inaccessible) {
            // No usable canonical private directory: preserve guarded network loading only.
            privateRoot = new File(appCacheDir.getAbsoluteFile(), "private_thumbnails");
            diskAvailable = false;
        }
        this.root = privateRoot;
        this.diskAvailable = diskAvailable;
        this.cleanup = cleanup;
        this.budget = diskAvailable ? budget : 0;
        this.maxEntry = maxEntry;
        this.hooks = hooks;
        // Only the owner constructs one controller per process. Never restore a key or
        // session from these entries, even if cleanup is delayed/fails due to disk errors.
        File[] orphans = diskAvailable ? root.listFiles() : null;
        if (orphans != null) scheduleCleanup(Arrays.asList(orphans));
    }

    public Session beginSession(Object connectionIdentity, UUID profileId, String serverFingerprint,
                                BooleanSupplier validity) {
        if (connectionIdentity == null || profileId == null || validity == null ||
                serverFingerprint == null || !serverFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid thumbnail session identity");
        }
        Session retired;
        Session next;
        synchronized (state) {
            usedConnections.removeIf(reference -> reference.get() == null);
            for (WeakReference<Object> reference : usedConnections) {
                if (reference.get() == connectionIdentity) {
                    throw new IllegalArgumentException("A new connection identity is required");
                }
            }
            retired = retireLocked();
            next = new Session(connectionIdentity, profileId, serverFingerprint, validity);
            active = next;
            usedConnections.add(new WeakReference<>(connectionIdentity));
        }
        finishRevocation(retired);
        return next;
    }

    public Session capture(Object connectionIdentity, UUID profileId) {
        synchronized (state) {
            return active != null && active.matches(connectionIdentity, profileId) ? active : null;
        }
    }

    /** No file deletion, crypto, network reads or image decoding on the locking thread. */
    public void revoke() {
        Session retired;
        synchronized (state) { retired = retireLocked(); }
        finishRevocation(retired);
    }

    private Session retireLocked() {
        Session retired = active;
        active = null;
        if (retired == null) return null;
        Arrays.fill(retired.key, (byte) 0);
        retired.key = null;
        for (Lease lease : retired.leases) lease.wipe();
        retired.leases.clear();
        return retired;
    }

    private void finishRevocation(Session retired) {
        if (retired == null) return;
        synchronized (rejectionState) {
            rejectedEntries.values().removeIf(rejection -> rejection.entry.session == retired);
            if (bypassSession == retired) bypassSession = null;
        }
        List<Source> cancel;
        synchronized (state) { cancel = new ArrayList<>(retired.sources); retired.sources.clear(); }
        for (Source source : cancel) {
            try { source.cancel(); } catch (RuntimeException ignored) { /* Still revoke every source. */ }
        }
        scheduleCleanup(Collections.singletonList(retired.directory));
    }

    /** Adjust storage without extending/changing the currently unlocked media session. */
    public void setCapacity(Capacity capacity) {
        synchronized (disk) {
            storageGeneration++;
            resetRejections();
            budget = diskAvailable ? capacity.bytes : 0;
            if (diskAvailable) scheduleCleanup(filesUnder(root));
        }
    }

    /** Explicit clear: fence old reads/writers immediately, then report physical cleanup. */
    public void clear(Consumer<Boolean> completed) {
        AtomicBoolean reported = new AtomicBoolean();
        Consumer<Boolean> report = success -> {
            if (reported.compareAndSet(false, true)) completed.accept(success);
        };
        List<File> snapshot = null;
        synchronized (disk) {
            storageGeneration++;
            resetRejections();
            try {
                if (!diskAvailable || !safeDirectory(root)) throw new IOException("Private cache unavailable");
                snapshot = clearableFilesUnder(root);
            } catch (IOException | RuntimeException unavailable) { /* Report outside the mutex. */ }
        }
        if (snapshot == null) { report.accept(false); return; }
        final List<File> previous = snapshot;
        try {
            cleanup.execute(() -> {
                boolean success = true;
                try {
                    synchronized (disk) {
                        for (File path : previous) if (!deleteTree(path)) success = false;
                    }
                } catch (RuntimeException unavailable) { success = false; }
                // The callback does not run under the disk/session mutexes.
                report.accept(success);
            });
        } catch (RuntimeException unavailable) { report.accept(false); }
    }

    /** Cache errors are misses. Network errors/oversize/invalid bodies fail closed. */
    public Lease load(Session session, ThumbnailKey key, Source source) throws IOException {
        session.requireCurrent();
        Loaded cached = read(session, key);
        if (cached != null) return deliver(session, cached.bytes, cached.entry);
        byte[] plaintext = null;
        synchronized (state) {
            requireCurrentLocked(session);
            session.sources.add(source);
        }
        Reservation reservation = null;
        try {
            reservation = reserve(session);
            session.requireCurrent();
            try (Response response = source.open()) {
                session.requireCurrent();
                if (response.status != 200 || !allowedType(response.contentType) || response.length > maxEntry) {
                    throw new IOException("Invalid thumbnail response");
                }
                plaintext = readBounded(response.body, maxEntry, session, true);
                hooks.at(Stage.AFTER_BODY);
                session.requireCurrent();
                if (!validImage(plaintext, response.contentType)) throw new IOException("Invalid thumbnail data");
            }
            session.requireCurrent();
            EntryToken entry = reservation == null ? null : write(session, key, plaintext, reservation);
            Lease result = deliver(session, plaintext, entry);
            plaintext = null; // Ownership has passed to the revocable lease.
            return result;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            synchronized (state) { session.sources.remove(source); }
            if (reservation != null) release(reservation);
        }
    }

    private Lease deliver(Session session, byte[] bytes, EntryToken entry) throws IOException {
        try {
            hooks.at(Stage.BEFORE_DELIVERY);
            if (entry != null && isRejected(session, entry.generation, entry.rejectionId)) {
                throw new IOException("Thumbnail decoder rejected this entry");
            }
            synchronized (state) {
                requireCurrentLocked(session);
                Lease lease = new Lease(session, bytes, entry);
                session.leases.add(lease);
                return lease;
            }
        } catch (IOException | RuntimeException failure) {
            Arrays.fill(bytes, (byte) 0);
            throw failure;
        }
    }

    private Loaded read(Session session, ThumbnailKey key) throws IOException {
        hooks.at(Stage.BEFORE_READ);
        session.requireCurrent();
        if (budget == 0 || bypassCache(session, storageGeneration)) return null;
        File file;
        long generation;
        synchronized (disk) {
            generation = storageGeneration;
            file = entryFile(session, key, generation);
        }
        byte[] envelope = null;
        byte[] plaintext = null;
        try {
            synchronized (disk) {
                session.requireCurrent();
                if (!file.isFile() || file.length() > maxEntry + OVERHEAD) return null;
                try (InputStream input = new FileInputStream(file)) {
                    byte[] prefix = readPrefix(input);
                    byte[] iv = Arrays.copyOfRange(prefix, HEADER.length, prefix.length);
                    // Only a tiny envelope header is read before checking decoder rejection.
                    // A queued disk cleanup is not needed to prevent another bad cache hit.
                    if (isRejected(session, generation, rejectionId(file, iv))) return null;
                    envelope = readBounded(new SequenceInputStream(new ByteArrayInputStream(prefix), input),
                            maxEntry + OVERHEAD, session, false);
                }
            }
            if (envelope.length <= OVERHEAD) throw new IOException("Invalid thumbnail envelope");
            for (int i = 0; i < HEADER.length; i++) if (envelope[i] != HEADER[i]) {
                throw new IOException("Unknown thumbnail format");
            }
            byte[] iv = Arrays.copyOfRange(envelope, HEADER.length, HEADER.length + IV_BYTES);
            if (isRejected(session, generation, rejectionId(file, iv))) return null;
            plaintext = cipher(session, key, generation, Cipher.DECRYPT_MODE, iv).doFinal(
                    envelope, HEADER.length + IV_BYTES, envelope.length - HEADER.length - IV_BYTES);
            hooks.at(Stage.AFTER_DECRYPT);
            session.requireCurrent();
            synchronized (disk) {
                if (generation != storageGeneration || isRejected(session, generation, rejectionId(file, iv))) return null;
                file.setLastModified(System.currentTimeMillis());
            }
            byte[] result = plaintext;
            plaintext = null;
            return new Loaded(result, new EntryToken(session, file, generation, iv));
        } catch (SessionExpiredException expired) {
            throw expired;
        } catch (IOException | GeneralSecurityException | RuntimeException corrupt) {
            // A concurrent successful writer may already have replaced this path. Remove
            // only the envelope we actually read, never a newer entry at the same path.
            if (envelope != null && envelope.length >= HEADER.length + IV_BYTES) {
                reject(new EntryToken(session, file, generation,
                        Arrays.copyOfRange(envelope, HEADER.length, HEADER.length + IV_BYTES)));
            }
            session.requireCurrent();
            return null;
        } finally {
            if (envelope != null) Arrays.fill(envelope, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private EntryToken write(Session session, ThumbnailKey key, byte[] plaintext, Reservation reservation)
            throws IOException {
        byte[] encrypted = null;
        try {
            byte[] iv = randomBytes(IV_BYTES);
            encrypted = cipher(session, key, reservation.generation, Cipher.ENCRYPT_MODE, iv).doFinal(plaintext);
            synchronized (disk) {
                if (budget == 0 || reservation.generation != storageGeneration || bypassCache(session, reservation.generation) || !safeDirectory(root) ||
                        !safeDirectory(session.directory) || accountedBytes() > budget) return null;
                // File creation and final rename are each serialized with revocation.
                // Cleanup can unlink an open temp; old writers never create it again.
                FileOutputStream output;
                synchronized (state) {
                    requireCurrentLocked(session);
                    if (!session.directory.isDirectory() && !session.directory.mkdirs()) return null;
                    reservation.temp = new File(session.directory, digest(randomBytes(32)) + ".tmp");
                    if (!reservation.temp.createNewFile()) { reservation.temp = null; return null; }
                    output = new FileOutputStream(reservation.temp);
                }
                try (FileOutputStream closeable = output) {
                    hooks.at(Stage.TEMP_OPEN);
                    session.requireCurrent();
                    if (reservation.generation != storageGeneration) return null;
                    closeable.write(HEADER);
                    closeable.write(iv);
                    closeable.write(encrypted);
                }
                hooks.at(Stage.BEFORE_COMMIT);
                synchronized (state) {
                    requireCurrentLocked(session);
                    if (reservation.generation != storageGeneration) return null;
                    File target = entryFile(session, key, reservation.generation);
                    // renameTo is an atomic same-directory rename on Android/Linux.
                    // Do not delete the destination first or retry into another location.
                    if (!reservation.temp.renameTo(target)) return null;
                    removeRejections(target, iv);
                    reservation.temp = null;
                    reservations.remove(reservation);
                }
                hooks.at(Stage.AFTER_COMMIT);
            }
            session.requireCurrent();
            return new EntryToken(session, entryFile(session, key, reservation.generation), reservation.generation, iv);
        } catch (SessionExpiredException expired) {
            throw expired;
        } catch (IOException | GeneralSecurityException | RuntimeException diskFailure) {
            // Never write plaintext or weaken the HTTP session guard as a fallback.
            session.requireCurrent();
            return null;
        } finally {
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }

    /** UI-safe: only bounded memory work, never disk/state locks or file operations. */
    public boolean reject(EntryToken entry) {
        if (entry == null) return false;
        boolean schedule = false;
        synchronized (rejectionState) {
            if (entry.generation != storageGeneration || entry.session != active) return false;
            if (!rejectedEntries.containsKey(entry.rejectionId)) {
                if (rejectedEntries.size() >= MAX_REJECTED_ENTRIES) {
                    // Never drop a bad-entry marker to admit another one. If disk cleanup
                    // is stuck/full, use network only for this generation until clear/unlock.
                    bypassSession = entry.session;
                    bypassGeneration = entry.generation;
                    return true;
                }
                rejectedEntries.put(entry.rejectionId, new Rejection(entry));
            }
            if (!rejectionCleanupScheduled) { rejectionCleanupScheduled = true; schedule = true; }
        }
        if (schedule) {
            try { cleanup.execute(this::cleanRejectedEntries); }
            catch (RuntimeException unavailable) {
                synchronized (rejectionState) { rejectionCleanupScheduled = false; }
                // Markers remain effective even if background work cannot be scheduled.
            }
        }
        return true;
    }

    private void cleanRejectedEntries() {
        while (true) {
            List<EntryToken> pending = new ArrayList<>();
            synchronized (rejectionState) {
                for (Rejection rejection : rejectedEntries.values()) {
                    if (rejection.pending) { rejection.pending = false; pending.add(rejection.entry); }
                }
                if (pending.isEmpty()) { rejectionCleanupScheduled = false; return; }
            }
            for (EntryToken entry : pending) {
                boolean removed;
                try { removed = deleteRejectedEntry(entry); }
                catch (RuntimeException unavailable) { removed = false; }
                if (removed) {
                    synchronized (rejectionState) { rejectedEntries.remove(entry.rejectionId); }
                }
            }
        }
    }

    /** Background only. A stale nonce must not remove a newer file at the same path. */
    private boolean deleteRejectedEntry(EntryToken entry) {
        synchronized (disk) {
            if (entry.generation != storageGeneration || !entry.session.isCurrent()) return true;
            try (InputStream input = new FileInputStream(entry.file)) {
                byte[] prefix = readPrefix(input);
                if (!MessageDigest.isEqual(entry.iv, Arrays.copyOfRange(prefix, HEADER.length, prefix.length))) return true;
            } catch (IOException | RuntimeException missing) { return !entry.file.exists(); }
            try { return entry.file.delete(); }
            catch (RuntimeException unavailable) { return false; }
        }
    }

    private byte[] readPrefix(InputStream input) throws IOException {
        byte[] prefix = new byte[HEADER.length + IV_BYTES];
        int count = 0;
        while (count < prefix.length) {
            int read = input.read(prefix, count, prefix.length - count);
            if (read < 0) throw new IOException("Invalid thumbnail envelope");
            count += read;
        }
        return prefix;
    }

    private static String rejectionId(File file, byte[] iv) { return file.getPath() + ":" + hex(iv); }
    private boolean bypassCache(Session session, long generation) {
        synchronized (rejectionState) { return bypassSession == session && bypassGeneration == generation; }
    }
    private boolean isRejected(Session session, long generation, String id) {
        synchronized (rejectionState) {
            return (bypassSession == session && bypassGeneration == generation) || rejectedEntries.containsKey(id);
        }
    }
    private void resetRejections() {
        synchronized (rejectionState) { rejectedEntries.clear(); bypassSession = null; }
    }
    private void removeRejections(File file, byte[] replacementIv) {
        synchronized (rejectionState) {
            rejectedEntries.values().removeIf(rejection -> rejection.entry.file.equals(file) &&
                    (replacementIv == null || !MessageDigest.isEqual(rejection.entry.iv, replacementIv)));
        }
    }
    int rejectedEntryCount() { synchronized (rejectionState) { return rejectedEntries.size(); } }

    private Cipher cipher(Session session, ThumbnailKey key, long generation, int mode, byte[] iv)
            throws GeneralSecurityException, SessionExpiredException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        synchronized (state) {
            requireCurrentLocked(session);
            cipher.init(mode, new SecretKeySpec(session.key, "AES"), new GCMParameterSpec(128, iv));
        }
        cipher.updateAAD(session.identity);
        cipher.updateAAD(key.authenticatedIdentity());
        cipher.updateAAD(generationBytes(generation));
        return cipher;
    }

    private byte[] readBounded(InputStream input, int maximum, Session session, boolean network)
            throws IOException {
        byte[] buffer = new byte[Math.min(16 * 1024, maximum + 1)];
        int size = 0;
        try {
            while (size <= maximum) {
                session.requireCurrent();
                if (size == buffer.length) {
                    byte[] expanded = Arrays.copyOf(buffer, Math.min(maximum + 1, buffer.length * 2));
                    Arrays.fill(buffer, (byte) 0);
                    buffer = expanded;
                }
                int count = input.read(buffer, size, Math.min(16 * 1024, buffer.length - size));
                session.requireCurrent();
                if (count < 0) break;
                if (count == 0) {
                    int value = input.read();
                    session.requireCurrent();
                    if (value < 0) break;
                    buffer[size++] = (byte) value;
                } else size += count;
                if (network) hooks.at(Stage.NETWORK_CHUNK);
                if (size > maximum) throw new IOException("Thumbnail exceeds safe size limit");
            }
            session.requireCurrent();
            return Arrays.copyOf(buffer, size);
        } finally { Arrays.fill(buffer, (byte) 0); }
    }

    private final class Reservation {
        File temp;
        final long bytes = maxEntry + OVERHEAD;
        final long generation = storageGeneration;
    }

    private Reservation reserve(Session session) throws IOException {
        synchronized (disk) {
            session.requireCurrent();
            long needed = maxEntry + OVERHEAD;
            if (budget < needed || bypassCache(session, storageGeneration) || !safeDirectory(root)) return null;
            List<File> files = filesUnder(root);
            files.sort(Comparator.comparingLong(File::lastModified));
            long total = reservedBytes();
            for (File file : files) if (!reservedTemp(file)) total += file.length();
            for (File file : files) {
                if (total <= budget - needed) break;
                if (!reservedTemp(file)) {
                    long length = file.length();
                    if (file.delete()) { total -= length; removeRejections(file, null); }
                }
            }
            session.requireCurrent();
            if (total > budget - needed) return null;
            Reservation result = new Reservation();
            reservations.add(result);
            return result;
        }
    }

    private void release(Reservation reservation) {
        synchronized (disk) {
            if (reservation.temp != null) reservation.temp.delete();
            reservations.remove(reservation);
        }
    }

    /** Includes abandoned ciphertext, temps and full outstanding write reservations. */
    long accountedBytes() {
        synchronized (disk) {
            long total = reservedBytes();
            for (File file : filesUnder(root)) if (!reservedTemp(file)) total += file.length();
            return total;
        }
    }
    private long reservedBytes() {
        long total = 0;
        for (Reservation reservation : reservations) total += reservation.bytes;
        return total;
    }
    private boolean reservedTemp(File file) {
        for (Reservation reservation : reservations) if (file.equals(reservation.temp)) return true;
        return false;
    }

    private List<File> filesUnder(File directory) {
        List<File> files = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) return files;
        for (File file : children) {
            // Never follow links out of the private cache tree, including cleanup.
            try { if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) continue; }
            catch (IOException ignored) { continue; }
            if (file.isDirectory()) files.addAll(filesUnder(file));
            else files.add(file);
        }
        return files;
    }

    private boolean safeDirectory(File directory) {
        try {
            return (!directory.exists() || directory.isDirectory()) &&
                    directory.getCanonicalFile().equals(directory.getAbsoluteFile());
        } catch (IOException inaccessible) { return false; }
    }

    private List<File> clearableFilesUnder(File directory) throws IOException {
        List<File> files = new ArrayList<>();
        if (!directory.exists()) return files;
        if (!safeDirectory(directory)) throw new IOException("Private cache unavailable");
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Private cache unavailable");
        for (File child : children) {
            if (!child.getCanonicalFile().equals(child.getAbsoluteFile())) throw new IOException("Unsafe cache path");
            if (child.isDirectory()) files.addAll(clearableFilesUnder(child));
            else files.add(child);
        }
        return files;
    }

    private void scheduleCleanup(List<File> paths) {
        try {
            cleanup.execute(() -> {
                synchronized (disk) {
                    for (File path : paths) deleteTree(path);
                }
            });
        } catch (RuntimeException ignored) { /* Ciphertext remains inaccessible and counted. */ }
    }
    private boolean deleteTree(File file) {
        try { if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) return false; }
        catch (IOException ignored) { return false; }
        if (!file.exists()) return true;
        File[] children = file.listFiles();
        boolean success = true;
        if (children != null) for (File child : children) if (!deleteTree(child)) success = false;
        boolean deleted = file.delete();
        if (deleted) removeRejections(file, null);
        return deleted && success;
    }
    private File entryFile(Session session, ThumbnailKey key, long generation) {
        MessageDigest digest = sha256();
        digest.update(session.identity);
        digest.update(key.authenticatedIdentity());
        digest.update(generationBytes(generation));
        return new File(session.directory, hex(digest.digest()) + ".enc");
    }
    private static byte[] generationBytes(long generation) {
        return ByteBuffer.allocate(Long.BYTES).putLong(generation).array();
    }
    private boolean currentLocked(Session session) {
        return active == session && session.key != null && session.validity.getAsBoolean();
    }
    private void requireCurrentLocked(Session session) throws SessionExpiredException {
        if (!currentLocked(session)) throw new SessionExpiredException();
    }
    private byte[] randomBytes(int size) { byte[] bytes = new byte[size]; random.nextBytes(bytes); return bytes; }
    public static String serverFingerprint(String normalizedServer) {
        return digest(normalizedServer.getBytes(StandardCharsets.UTF_8));
    }
    private static String digest(byte[] bytes) { return hex(sha256().digest(bytes)); }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static String mime(String contentType) {
        return contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }
    private static boolean allowedType(String type) {
        String value = mime(type);
        return value.equals("image/jpeg") || value.equals("image/png") || value.equals("image/webp");
    }
    private static boolean validImage(byte[] bytes, String type) {
        switch (mime(type)) {
            case "image/jpeg":
                return bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255;
            case "image/png":
                return bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8),
                        new byte[] { (byte) 137, 80, 78, 71, 13, 10, 26, 10 });
            case "image/webp":
                return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                        && bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
            default: return false;
        }
    }
}
