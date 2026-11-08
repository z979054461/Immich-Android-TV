import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;

/** Locate the Java PKCS12 alias by a pinned PUBLIC certificate, never print key material. */
public final class ResolveSigningAlias {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected keystore and alias-output paths");
        String expected = required("RELEASE_CERT_SHA256").replace(":", "").toLowerCase();
        if (!expected.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid public certificate fingerprint");
        char[] storePassword = required("RELEASE_KEYSTORE_PASSWORD").toCharArray();
        char[] keyPassword = required("RELEASE_KEY_PASSWORD").toCharArray();
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (InputStream input = Files.newInputStream(Path.of(args[0]))) { store.load(input, storePassword); }
            var matches = new ArrayList<String>();
            var aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!store.isKeyEntry(alias) || store.getCertificate(alias) == null) continue;
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(store.getCertificate(alias).getEncoded()));
                if (expected.equals(digest)) matches.add(alias);
            }
            if (matches.size() != 1) throw new IllegalStateException("Expected exactly one signing entry matching the pinned certificate");
            String alias = matches.get(0);
            if (!(store.getKey(alias, keyPassword) instanceof PrivateKey key)) throw new IllegalStateException("Not a private-key entry");
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key); signer.update(challenge);
            byte[] proof = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(store.getCertificate(alias)); verifier.update(challenge);
            if (!verifier.verify(proof)) throw new IllegalStateException("Signing key and certificate do not match");
            Files.writeString(Path.of(args[1]), alias);
            Arrays.fill(proof, (byte) 0); Arrays.fill(challenge, (byte) 0);
            System.out.println("Dedicated signing key verified against pinned public certificate");
        } finally {
            Arrays.fill(storePassword, '\0'); Arrays.fill(keyPassword, '\0');
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing signing setting: " + name);
        return value;
    }
}
