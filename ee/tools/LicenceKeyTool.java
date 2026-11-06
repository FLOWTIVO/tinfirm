/*
 * Tinfirm's licence key tool (ADR-0077, decision 6), for FLOWTIVO: makes the signing key pairs and signs licence keys.
 * JDK 25 only, run from the repository's root as a single source file:
 *
 *   java ee/tools/LicenceKeyTool.java keygen <private-key.pem>
 *       Writes a new Ed25519 private key to the file, which must not exist, encrypted with a passphrase (encrypted
 *       PKCS#8 PEM: PBES2, PBKDF2 with HMAC-SHA256 and AES-256-CBC), and prints its public key (X.509 PEM). The public
 *       key goes into the server as ee/src/main/resources/ee/licence-public-keys/<key id>.pub, the key id a name
 *       FLOWTIVO chooses (such as flowtivo-2027). Keep the private key and its passphrase outside this repository and
 *       every instance, apart from each other: whoever holds both issues licences.
 *
 *   java ee/tools/LicenceKeyTool.java protect <private-key.pem>
 *       Encrypts an existing unencrypted private key (PKCS#8 PEM, "PRIVATE KEY") in place, with a passphrase it asks
 *       for as keygen does. The key, and so its public key and key id, stay the same. The file is replaced only once
 *       the encrypted key is written and decrypts again.
 *
 *   java ee/tools/LicenceKeyTool.java sign --private-key <private-key.pem> --key-id <key id> [--public-key <file>]
 *       --id <licence id> --rev <n> --customer <name> --edition PRO|ENTERPRISE [--seats <n>] [--companies <n>]
 *       --starts <YYYY-MM-DD> --ends <YYYY-MM-DD>
 *       Prints the key: TINFIRM1.<base64url(payload)>.<base64url(Ed25519 signature over the payload's bytes)>, the
 *       payload naming the key id. The signature is checked against the key id's public key before the key is
 *       printed: --public-key, by default ee/src/main/resources/ee/licence-public-keys/<key id>.pub, so that no key is
 *       signed with a private key the server does not trust under that id. A Pro key needs --seats and takes no
 *       --companies; an Enterprise key may leave out either. Seats bought during the year: the same --id, the next
 *       --rev, the new total of --seats and the same --ends; the server refuses a revision not higher than the one it
 *       holds.
 *
 * The passphrase is read from the terminal, never from the command line or the environment. When standard input is not
 * a terminal, the tool reads it from the first line of standard input instead: a pipe from a secret store, or the
 * web app's end-to-end harness. keygen and protect ask for it twice on a terminal. sign still reads an unencrypted
 * private key, with a warning and without asking for a passphrase: encrypt it with protect.
 *
 * Why encrypted PKCS#8: the JDK writes and reads it without a library, OpenSSL reads it too (`openssl pkey`), and PBES2
 * with PBKDF2 is the strongest key derivation the JDK has (it has no scrypt or Argon2). The iterations make a guess
 * cost about a second; a long passphrase does the rest.
 *
 * The private key is never committed: no file of this repository holds one, and the tests sign with key pairs they
 * make themselves.
 */

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

public class LicenceKeyTool {

    /** A misspelt option is refused, never ignored: a missing --seats on an Enterprise key means no seat count. */
    private static final java.util.Set<String> OPTIONS = java.util.Set.of("private-key", "key-id", "public-key", "id",
            "rev", "customer", "edition", "seats", "companies", "starts", "ends");

    /** PBES2 with PBKDF2-HMAC-SHA256 and AES-256-CBC, as the JDK names it. */
    private static final String PBE = "PBEWithHmacSHA256AndAES_256";

    /** PBKDF2's iterations: OWASP's figure for HMAC-SHA256 (2023), about a second a guess on a laptop. */
    private static final int ITERATIONS = 600_000;

    private static final int MIN_PASSPHRASE = 12;

    /**
     * The BEGIN lines of the two private key PEMs, without their dashes: a whole BEGIN line in source reads as a key to
     * secret scanners.
     */
    private static final String ENCRYPTED = "BEGIN ENCRYPTED PRIVATE KEY";

    private static final String UNENCRYPTED = "BEGIN PRIVATE KEY";

    /** Where the server's trusted public keys are, one file a key id (LicenceTrust). */
    private static final String PUBLIC_KEYS = "ee/src/main/resources/ee/licence-public-keys/";

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("keygen")) {
            keygen(Path.of(args[1]));
        }
        else if (args.length == 2 && args[0].equals("protect")) {
            protect(Path.of(args[1]));
        }
        else if (args.length > 0 && args[0].equals("sign")) {
            System.out.println(sign(options(args)));
        }
        else {
            System.err.println("usage: LicenceKeyTool keygen <private-key.pem>");
            System.err.println("       LicenceKeyTool protect <private-key.pem>");
            System.err.println("       LicenceKeyTool sign --private-key <file> --key-id <key id> [--public-key <file>] "
                    + "--id <id> --rev <n> --customer <name> --edition PRO|ENTERPRISE [--seats <n>] [--companies <n>] "
                    + "--starts <date> --ends <date>");
            System.exit(2);
        }
    }

    private static void keygen(Path privateKeyFile) throws Exception {
        if (Files.exists(privateKeyFile)) {
            throw new IllegalArgumentException(privateKeyFile + " exists: a signing key is never overwritten");
        }
        char[] passphrase = passphrase(true);
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] encrypted = encrypt(pair.getPrivate().getEncoded(), passphrase);
            // Created owner-only before a byte of the key is written. A file system without POSIX permissions is
            // refused: its defaults may let others read the key.
            try {
                Files.createFile(privateKeyFile, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            }
            catch (UnsupportedOperationException ex) {
                throw new IllegalStateException(privateKeyFile + " is on a file system without POSIX permissions: "
                        + "make the signing key on one that has them, so that only its owner reads it");
            }
            Files.writeString(privateKeyFile, pem("ENCRYPTED PRIVATE KEY", encrypted));
            System.out.print(pem("PUBLIC KEY", pair.getPublic().getEncoded()));
        }
        finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    private static void protect(Path privateKeyFile) throws Exception {
        String pem = Files.readString(privateKeyFile);
        if (pem.contains(ENCRYPTED)) {
            throw new IllegalArgumentException(privateKeyFile + " is already encrypted");
        }
        if (!pem.contains(UNENCRYPTED)) {
            throw new IllegalArgumentException(privateKeyFile + " is not a private key in PKCS#8 PEM (PRIVATE KEY)");
        }
        byte[] pkcs8 = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der(pem)))
            .getEncoded();
        char[] passphrase = passphrase(true);
        try {
            String encrypted = pem("ENCRYPTED PRIVATE KEY", encrypt(pkcs8, passphrase));
            if (!Arrays.equals(decrypt(encrypted, passphrase).getEncoded(), pkcs8)) {
                throw new IllegalStateException("The encrypted key does not decrypt to the same key: nothing changed");
            }
            // Beside the key, owner-only, then moved over it: the file is never half written.
            Path parent = privateKeyFile.toAbsolutePath().getParent();
            Path temporary = Files.createTempFile(parent, ".licence-key-", ".pem",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            try {
                Files.writeString(temporary, encrypted);
                Files.move(temporary, privateKeyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
            finally {
                Files.deleteIfExists(temporary);
            }
        }
        finally {
            Arrays.fill(passphrase, '\0');
        }
        System.err.println(privateKeyFile + " is encrypted. Its public key and key id are unchanged. Unencrypted "
                + "copies of it elsewhere, such as backups, still hold the key in clear: replace or destroy them.");
    }

    /** The passphrase, from the terminal; from standard input's first line when that is not a terminal. */
    private static char[] passphrase(boolean confirm) throws Exception {
        Console console = System.console();
        char[] passphrase;
        if (console != null && console.isTerminal()) {
            passphrase = console.readPassword("Passphrase of the private key: ");
            if (passphrase != null && confirm) {
                char[] again = console.readPassword("The same passphrase again: ");
                boolean same = Arrays.equals(passphrase, again);
                if (again != null) {
                    Arrays.fill(again, '\0');
                }
                if (!same) {
                    Arrays.fill(passphrase, '\0');
                    throw new IllegalArgumentException("The two passphrases differ");
                }
            }
        }
        else {
            String line = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            passphrase = line != null ? line.toCharArray() : null;
        }
        if (passphrase == null || passphrase.length < MIN_PASSPHRASE) {
            throw new IllegalArgumentException("The passphrase has at least " + MIN_PASSPHRASE + " characters");
        }
        return passphrase;
    }

    private static byte[] encrypt(byte[] pkcs8, char[] passphrase) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        random.nextBytes(salt);
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance(PBE);
        cipher.init(Cipher.ENCRYPT_MODE, pbeKey(passphrase),
                new PBEParameterSpec(salt, ITERATIONS, new IvParameterSpec(iv)));
        // The cipher's parameters as PBES2's, the name the JDK writes in EncryptedPrivateKeyInfo.
        AlgorithmParameters pbes2 = AlgorithmParameters.getInstance("PBES2");
        pbes2.init(cipher.getParameters().getEncoded());
        return new EncryptedPrivateKeyInfo(pbes2, cipher.doFinal(pkcs8)).getEncoded();
    }

    private static PrivateKey decrypt(String pem, char[] passphrase) throws Exception {
        EncryptedPrivateKeyInfo info = new EncryptedPrivateKeyInfo(der(pem));
        AlgorithmParameters parameters = info.getAlgParameters();
        PKCS8EncodedKeySpec spec;
        try {
            Cipher cipher = Cipher.getInstance(PBE);
            cipher.init(Cipher.DECRYPT_MODE, pbeKey(passphrase), parameters);
            spec = info.getKeySpec(cipher);
        }
        catch (java.security.GeneralSecurityException ex) {
            throw new IllegalArgumentException("The passphrase is wrong, or the private key is not one of keygen's");
        }
        return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
    }

    private static SecretKey pbeKey(char[] passphrase) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase);
        try {
            return SecretKeyFactory.getInstance(PBE).generateSecret(spec);
        }
        finally {
            spec.clearPassword();
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Expected --name value pairs, at " + args[i]);
            }
            String name = args[i].substring(2);
            if (!OPTIONS.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name);
            }
            if (options.putIfAbsent(name, args[i + 1]) != null) {
                throw new IllegalArgumentException("--" + name + " is given twice");
            }
        }
        return options;
    }

    static String sign(Map<String, String> options) throws Exception {
        String edition = required(options, "edition");
        if (!edition.equals("PRO") && !edition.equals("ENTERPRISE")) {
            throw new IllegalArgumentException("--edition is PRO or ENTERPRISE");
        }
        String seats = options.get("seats");
        String companies = options.get("companies");
        if (edition.equals("PRO") && (seats == null || companies != null)) {
            throw new IllegalArgumentException("A Pro key needs --seats and takes no --companies");
        }
        LocalDate starts = LocalDate.parse(required(options, "starts"));
        LocalDate ends = LocalDate.parse(required(options, "ends"));
        if (ends.isBefore(starts)) {
            throw new IllegalArgumentException("--ends is before --starts");
        }
        // The server's rules (LicenceKeyFormat), so that no key is signed that every server refuses.
        String keyId = required(options, "key-id");
        if (!keyId.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(
                    "--key-id is up to 64 lower-case letters, digits or '-', from a letter or digit");
        }
        String id = required(options, "id");
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) {
            throw new IllegalArgumentException(
                    "--id is up to 100 letters, digits, '.', '_' or '-', from a letter or digit");
        }
        String customer = required(options, "customer");
        if (customer.length() > 200) {
            throw new IllegalArgumentException("--customer is up to 200 characters");
        }
        Path publicKeyFile = Path.of(options.getOrDefault("public-key", PUBLIC_KEYS + keyId + ".pub"));
        if (!Files.isRegularFile(publicKeyFile)) {
            throw new IllegalArgumentException("No public key of " + keyId + " at " + publicKeyFile
                    + ": run from the repository's root, or give --public-key");
        }
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(der(Files.readString(publicKeyFile))));
        String payload = "{\"v\":2,\"kid\":" + json(keyId) + ",\"id\":" + json(id) + ",\"rev\":"
                + count(required(options, "rev")) + ",\"customer\":"
                + json(customer) + ",\"edition\":\"" + edition + "\",\"seats\":"
                + count(seats) + ",\"companies\":" + count(companies) + ",\"startsAt\":\"" + starts
                + "\",\"endsAt\":\"" + ends + "\"}";
        String pem = Files.readString(Path.of(required(options, "private-key")));
        PrivateKey key;
        if (pem.contains(ENCRYPTED)) {
            char[] passphrase = passphrase(false);
            try {
                key = decrypt(pem, passphrase);
            }
            finally {
                Arrays.fill(passphrase, '\0');
            }
        }
        else if (pem.contains(UNENCRYPTED)) {
            System.err.println("WARNING: the private key is not encrypted. Encrypt it with: "
                    + "java ee/tools/LicenceKeyTool.java protect " + options.get("private-key"));
            key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der(pem)));
        }
        else {
            throw new IllegalArgumentException("The private key is not a PKCS#8 PEM (ENCRYPTED PRIVATE KEY, or "
                    + "PRIVATE KEY)");
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(key);
        signer.update(bytes);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(bytes);
        if (!verifier.verify(signature)) {
            throw new IllegalArgumentException("The private key is not the one of " + keyId + " (" + publicKeyFile
                    + "): the server would refuse the key");
        }
        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        return "TINFIRM1." + base64url.encodeToString(bytes) + "." + base64url.encodeToString(signature);
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value;
    }

    private static String count(String value) {
        if (value == null) {
            return "null";
        }
        int count = Integer.parseInt(value);
        if (count < 1 || count > 1_000_000) {
            throw new IllegalArgumentException("A count is from 1 to 1000000");
        }
        return Integer.toString(count);
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static byte[] der(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").strip());
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(der) + "\n-----END " + type + "-----\n";
    }
}
