package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * One WireMock server for the whole test run, standing in for a self-managed GitLab instance. A component's URL
 * must be {@code https://}, and the server derives the GitLab API from the URL's origin (ADR-0023), so the stub
 * serves HTTPS on {@code localhost} with a certificate made for this run. Loading this class makes the JVM's default
 * SSL context trust that certificate besides the usual CAs; it must be loaded before the server builds its GitLab
 * client, which {@link IntegrationTest} does. Tests stub and verify per project path, so they never interfere.
 * TEST FIXTURE ONLY.
 */
public final class GitLabStub {

    /** Password of the stub's throw-away key store (test fixture). */
    private static final String KEY_STORE_PASSWORD = "gitlab-stub-" + UUID.randomUUID();

    private static final WireMockServer SERVER;

    static {
        Path keyStore = createKeyStore();
        trustInThisJvm(keyStore);
        SERVER = new WireMockServer(StubServers.options()
            .httpDisabled(true)
            .dynamicHttpsPort()
            .keystorePath(keyStore.toString())
            .keystoreType("PKCS12")
            .keystorePassword(KEY_STORE_PASSWORD)
            .keyManagerPassword(KEY_STORE_PASSWORD));
        SERVER.start();
    }

    private GitLabStub() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** The instance's origin, {@code https://localhost:<port>}: a component URL is this plus the project path. */
    public static String origin() {
        return "https://localhost:" + SERVER.httpsPort();
    }

    /** A self-signed key pair for {@code localhost} and {@code 127.0.0.1}, made with the JDK's keytool. */
    private static Path createKeyStore() {
        try {
            Path directory = Files.createTempDirectory("tinfirm-gitlab-stub");
            Path keyStore = directory.resolve("gitlab-stub.p12");
            Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
            // Every argument is a constant of this class or a path this method created; nothing comes from outside.
            Process process = new ProcessBuilder(List.of(keytool.toString(), "-genkeypair", "-alias", "gitlab-stub", // nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder
                    "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost", "-ext",
                    "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                    "-storepass", KEY_STORE_PASSWORD, "-keypass", KEY_STORE_PASSWORD))
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool could not create the GitLab stub's key pair: " + output);
            }
            return keyStore;
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating the GitLab stub's key pair", ex);
        }
    }

    /** The default SSL context from now on trusts the JDK's trust anchors and the stub's certificate. */
    private static void trustInThisJvm(Path keyStore) {
        try {
            KeyStore stub = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keyStore)) {
                stub.load(in, KEY_STORE_PASSWORD.toCharArray());
            }
            KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
            trusted.load(null, null);
            trusted.setCertificateEntry("gitlab-stub", stub.getCertificate("gitlab-stub"));
            TrustManagerFactory defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            defaults.init((KeyStore) null);
            int n = 0;
            for (var manager : defaults.getTrustManagers()) {
                if (manager instanceof X509TrustManager x509) {
                    for (X509Certificate issuer : x509.getAcceptedIssuers()) {
                        trusted.setCertificateEntry("default-" + n++, issuer);
                    }
                }
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trusted);
            SSLContext context = SSLContext.getInstance("TLSv1.3");
            context.init(null, factory.getTrustManagers(), null);
            SSLContext.setDefault(context);
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException("The GitLab stub's certificate could not be trusted", ex);
        }
    }
}
