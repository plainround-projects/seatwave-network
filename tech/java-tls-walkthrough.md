# The `tls` package, line by line

## What it is

This file reads the Java code of the `tls` package, one line at a time. The package has six files: `TlsMaterial.java`, `TlsScenario.java`, `HandshakeOutcome.java`, `TlsSettings.java`, `TlsFailures.java` and `TlsFailuresCli.java`. Together they build the TLS settings for a server and a client, and then try three handshakes that a correct client must **refuse**.

**TLS** (Transport Layer Security) is the protocol that wraps a connection so that the other side is who it claims to be, and so that nobody on the path can read or change the bytes. HTTPS is HTTP carried inside TLS. `java-networking-and-tls.md` explains the ideas; this file reads the code.

## Why this repository uses it

Module 5 asks what TLS costs and what it protects. Failure mode 4 of the module is a TLS failure a client must not ignore: an expired certificate, a certificate for another name, a protocol version that is too old. The right behavior is to **fail safe**: refuse the connection and send no request. This package shows that on a real HTTPS server, using only the JDK's standard TLS. The code has no "trust every certificate" switch anywhere.

## Where it appears

| File | What it does |
|---|---|
| `tls/TlsMaterial.java` | Builds a server `SSLContext` from a key store and a client `SSLContext` that trusts one certificate authority only. |
| `tls/TlsScenario.java` | An enum of the four handshakes: valid, expired, wrong name, old version. |
| `tls/HandshakeOutcome.java` | A record: the scenario, accepted or refused, and the reason. |
| `tls/TlsSettings.java` | Reads and checks `config/tls-failures.properties`. |
| `tls/TlsFailures.java` | Starts a real HTTPS server for a scenario, sends `GET /health`, and reports what the client did. |
| `tls/TlsFailuresCli.java` | The `tlsFailures` command: runs the four scenarios and prints one line each. |

Other places that use `TlsMaterial`: `server/ServeCli.java`, `timing/TimingCli.java` (read `java-server-and-link-walkthrough.md` and `java-timing-walkthrough.md`).

## How it works underneath

**The files on disk.** The Gradle task `testCertificates` (see `keytool-test-certificates.md`) writes these files into `build/tls` before any run task:

- `seatwave-test-ca.pem`: the public certificate of a throwaway certificate authority (CA), in text form (PEM).
- `seatwave-test-ca.p12`, `seatwave-valid.p12`, `seatwave-expired.p12` and `seatwave-wrong-name.p12`: **key stores** in the PKCS12 format. A key store is a password-protected file that holds private keys and certificates. The three server key stores each hold one server key and the certificate the CA signed for it.
- `store.password`: the key store password, random for each run of the task.

This note never prints a key or the password, and nothing under `build/tls` is committed.

**What a certificate is.** A **certificate** is a signed statement: "this public key belongs to this name, from this date until that date". The CA signs it. A client that trusts the CA can check the signature, then check the dates, then check that the name it is connecting to appears in the certificate. If any of the three checks fails, the client refuses.

**Three ways to fail.**

1. **Expired**: the current time is after the certificate's end date.
2. **Wrong name**: the client connects to `127.0.0.1`, but the certificate lists only `seatwave.test`. A certificate for another name proves nothing about this server.
3. **Old version**: the client offers only TLS 1.2, and the server accepts only TLS 1.3. They share no version, so the server refuses.

**The trust store.** A client decides whom to trust with a **trust store**: the list of CA certificates it accepts. The JDK's default trust store holds the public CAs. This client builds its own store with exactly one entry, the TEST CA. A certificate from any other issuer is refused, and the TEST CA is not trusted by anything else on the machine.

**Order of events in one attempt.** The program starts an HTTPS server on a free loopback port with the scenario's key store. It builds an `HttpClient`. The client connects (TCP), and the TLS handshake starts. If the handshake fails, `client.send` throws an `IOException` (in fact `javax.net.ssl.SSLHandshakeException`, which I checked) and **no HTTP request was sent**. If it succeeds, the request `GET /health` is sent and the status is read.

## Walkthrough of this repository's use

### `tls/TlsMaterial.java`

```java
package io.plainround.seatwave.network.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Objects;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
```

- `Files` and `Path` read files; `InputStream` is a stream of bytes from a file.
- `KeyStore` is the key store type. `Certificate` and `CertificateFactory` (package `java.security.cert`) read certificates.
- `KeyManagerFactory` makes the objects that hold this side's keys. `TrustManagerFactory` makes the objects that decide whom to trust. `SSLContext` ties them together.
- `GeneralSecurityException` is the parent class of the errors these classes throw (a bad file, a wrong password, a bad certificate).

```java
/**
 * Builds the two TLS contexts of the lab from the files the {@code testCertificates} build task
 * writes into {@code build/tls}: a server context that holds one TEST server key, and a client
 * context that trusts the TEST certificate authority and nothing else.
 *
 * <p>Only the JDK's standard TLS is used. There is deliberately no "trust every certificate" option
 * anywhere in this project: a client that cannot verify the server refuses to talk to it.
 * Thread-safe: it holds no state.
 */
public final class TlsMaterial {

  // "TLS" asks the JDK for its default TLS implementation, which supports TLS 1.3 and 1.2.
  private static final String TLS = "TLS";
  private static final String PKCS12 = "PKCS12";
  private static final String TRUSTED_CA_ALIAS = "seatwave-test-ca";

  private TlsMaterial() {}
```

- The class comment says the key rule of this project: there is **no** "trust every certificate" option anywhere. A client that cannot verify the server refuses to talk to it.
- `"TLS"` asks the JDK for its default TLS implementation, which supports TLS 1.3 and TLS 1.2. `"PKCS12"` is the key store format. `TRUSTED_CA_ALIAS` is the label of the one trusted entry.
- The private constructor stops anyone from making an object: the class only has static methods.

```java
  /**
   * Builds a server context from a PKCS12 keystore with one key and its certificate chain.
   *
   * @param keystore the server keystore, for example {@code build/tls/seatwave-valid.p12}
   * @param passwordFile the file that holds the keystore password
   * @return a context for {@code SeatWaveServer.startTls}
   * @throws IOException if a file cannot be read
   * @throws GeneralSecurityException if the keystore cannot be opened
   */
  public static SSLContext serverContext(Path keystore, Path passwordFile)
      throws IOException, GeneralSecurityException {
    char[] password = readPassword(passwordFile);
    KeyStore store = KeyStore.getInstance(PKCS12);
    try (InputStream in = Files.newInputStream(keystore)) {
      store.load(in, password);
    }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, password);
    SSLContext context = SSLContext.getInstance(TLS);
    // null trust managers and randomness: the JDK's defaults, since the server checks no client.
    context.init(keys.getKeyManagers(), null, null);
    return context;
  }
```

- `serverContext` builds the server side. It reads the password (`readPassword`, below) into a `char[]`. Java's security classes take passwords as character arrays, not as `String`, so the array can be overwritten after use.
- `KeyStore.getInstance(PKCS12)` makes an empty key store object. `store.load(in, password)` reads the file and decrypts it with the password. `try (InputStream in = Files.newInputStream(keystore))` closes the file afterwards.
- `KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())` asks for the JDK's default key manager. `keys.init(store, password)` loads the key store into it.
- `SSLContext.getInstance(TLS)` makes a context. `context.init(keyManagers, trustManagers, random)` fills it. The server passes `null` for the other two: the JDK's defaults are used, because this server does not ask the client for a certificate.

```java
  /**
   * Builds a client context that trusts exactly one certificate authority.
   *
   * @param certificateAuthorityPem the CA certificate in PEM form
   * @return a context for {@code HttpClient.Builder.sslContext}
   * @throws IOException if the file cannot be read
   * @throws GeneralSecurityException if the file is not a certificate
   */
  public static SSLContext clientContext(Path certificateAuthorityPem)
      throws IOException, GeneralSecurityException {
    Objects.requireNonNull(certificateAuthorityPem, "certificateAuthorityPem");
    Certificate authority;
    try (InputStream in = Files.newInputStream(certificateAuthorityPem)) {
      authority = CertificateFactory.getInstance("X.509").generateCertificate(in);
    }
    KeyStore trusted = KeyStore.getInstance(PKCS12);
    // An empty in-memory store with one trusted entry: the JDK's default trust store, with the
    // public certificate authorities, is not used, so only the TEST CA can vouch for a server.
    trusted.load(null, null);
    trusted.setCertificateEntry(TRUSTED_CA_ALIAS, authority);
    TrustManagerFactory trust =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trust.init(trusted);
    SSLContext context = SSLContext.getInstance(TLS);
    context.init(null, trust.getTrustManagers(), null);
    return context;
  }
```

- `clientContext` builds the client side from the PEM file of the CA.
- `CertificateFactory.getInstance("X.509").generateCertificate(in)` reads one certificate. X.509 is the standard format of certificates. The factory accepts the PEM text form.
- `trusted.load(null, null)` makes an **empty** key store in memory (a `null` input stream means "start empty"). `trusted.setCertificateEntry(alias, authority)` adds the one trusted CA. The JDK's default trust store is not used. Only the TEST CA can vouch for a server.
- `TrustManagerFactory ... trust.init(trusted)` builds the trust managers from that store. `context.init(null, trust.getTrustManagers(), null)` passes no key (the client has none) and our trust managers.
- A trust manager performs the signature and date checks. The check of the name is done by the HTTP client (see `TlsFailures` below).

```java
  private static char[] readPassword(Path passwordFile) throws IOException {
    Objects.requireNonNull(passwordFile, "passwordFile");
    String password = Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
    if (password.isEmpty()) {
      throw new IOException("the password file is empty: " + passwordFile);
    }
    return password.toCharArray();
  }
}
```

- `readPassword` reads the password file as UTF-8 text, strips spaces and line breaks from both ends, and refuses an empty file with an `IOException`. `toCharArray()` converts the text to a `char[]`. (A `String` copy exists briefly in memory and stays until the garbage collector reclaims it; a character array is the usual habit, not a guarantee.)

### `tls/TlsScenario.java`

```java
package io.plainround.seatwave.network.tls;

/**
 * The four handshakes of failure mode 4 (Module 5, Section 9): one that must succeed and three that
 * a correct client must refuse.
 */
public enum TlsScenario {
  /** A certificate signed by the trusted CA, in date, for 127.0.0.1: accepted. */
  VALID("seatwave-valid.p12"),
  /** Signed by the trusted CA and for the right name, but it expired two days ago: refused. */
  EXPIRED("seatwave-expired.p12"),
  /** Signed by the trusted CA and in date, but only for seatwave.test: refused at 127.0.0.1. */
  WRONG_NAME("seatwave-wrong-name.p12"),
  /** The valid certificate, but the client offers only TLS 1.2 to a TLS 1.3 server: refused. */
  OLD_VERSION("seatwave-valid.p12");

  private final String keystoreFile;

  TlsScenario(String keystoreFile) {
    this.keystoreFile = keystoreFile;
  }

  /**
   * Returns the file name of the server keystore, inside the TLS folder.
   *
   * @return for example {@code seatwave-expired.p12}
   */
  public String keystoreFile() {
    return keystoreFile;
  }
}
```

- An **enum with a constructor and a field.** Each value is declared with an argument in brackets: `VALID("seatwave-valid.p12")`. The constructor stores it in `keystoreFile`, and the method `keystoreFile()` returns it.
- `VALID` and `OLD_VERSION` use the **same** key store (`seatwave-valid.p12`). The difference is in the client: `OLD_VERSION` offers only TLS 1.2.
- The comment on each value says the expected result: accepted, or refused.

### `tls/HandshakeOutcome.java`

```java
package io.plainround.seatwave.network.tls;

import java.util.Objects;

/**
 * What happened when the client tried one scenario.
 *
 * @param scenario the scenario
 * @param isAccepted true if the handshake completed and the request was answered
 * @param detail the TLS version and cipher suite when accepted, or the client's reason to refuse
 */
public record HandshakeOutcome(TlsScenario scenario, boolean isAccepted, String detail) {

  /** Checks the fields. */
  public HandshakeOutcome {
    Objects.requireNonNull(scenario, "scenario");
    Objects.requireNonNull(detail, "detail");
  }
}
```

- A record of three values: the scenario, `isAccepted`, and a `detail` text. The detail is the TLS version and cipher suite when accepted, or the client's own reason to refuse. A **cipher suite** is the named set of algorithms used to protect the connection, for example `TLS_AES_256_GCM_SHA384`.
- The compact constructor refuses nulls.

### `tls/TlsSettings.java`

```java
package io.plainround.seatwave.network.tls;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Where the TEST certificates are and which TLS versions each side allows. Read from a settings
 * file with no defaults.
 *
 * @param directory the folder the {@code testCertificates} task writes, {@code build/tls}
 * @param serverProtocols the TLS versions the server accepts, for example {@code TLSv1.3}
 * @param oldClientProtocols the versions the client offers in {@link TlsScenario#OLD_VERSION}
 * @param timeout the connect and request timeout of every client call
 */
public record TlsSettings(
    Path directory,
    List<String> serverProtocols,
    List<String> oldClientProtocols,
    Duration timeout) {
```

- A record of four components: the directory of the certificates, the TLS versions the server accepts, the versions the old client offers, and a timeout. `Path` is a file path.

```java
  // The JDK's names for the TLS versions it implements; anything else is a typo.
  private static final List<String> KNOWN_VERSIONS = List.of("TLSv1.3", "TLSv1.2");
  private static final int MAX_TIMEOUT_MILLIS = 60_000;

  /**
   * Creates the settings.
   *
   * @throws IllegalArgumentException if a list is empty or names an unknown TLS version
   */
  public TlsSettings {
    Objects.requireNonNull(directory, "directory");
    Objects.requireNonNull(timeout, "timeout");
    serverProtocols = List.copyOf(serverProtocols);
    oldClientProtocols = List.copyOf(oldClientProtocols);
    if (serverProtocols.isEmpty() || oldClientProtocols.isEmpty()) {
      throw new IllegalArgumentException("each side needs at least one TLS version");
    }
    if (!KNOWN_VERSIONS.containsAll(serverProtocols)
        || !KNOWN_VERSIONS.containsAll(oldClientProtocols)) {
      throw new IllegalArgumentException("TLS versions must be from " + KNOWN_VERSIONS);
    }
  }
```

- `KNOWN_VERSIONS` lists the JDK's names for the two versions. Anything else (`TLSv1.4`, `SSLv3`) is a typo.
- The compact constructor copies both lists (`List.copyOf`) and refuses empty lists and unknown names. `containsAll` is true only if every name in the argument is in `KNOWN_VERSIONS`.

```java
  /**
   * Reads the settings.
   *
   * @param reader the settings reader
   * @return the settings, or one line per missing or invalid key
   */
  public static Result<TlsSettings, List<String>> from(SettingsReader reader) {
    Objects.requireNonNull(reader, "reader");
    String directory = reader.text("tls.directory");
    List<String> server = reader.wordsIn("tls.server.protocols", KNOWN_VERSIONS);
    List<String> oldClient = reader.wordsIn("tls.old.client.protocols", KNOWN_VERSIONS);
    Duration timeout = reader.millisBetween("client.timeout.millis", 1, MAX_TIMEOUT_MILLIS);
    if (!reader.problems().isEmpty()) {
      return Result.error(reader.problems());
    }
    return Result.success(new TlsSettings(Path.of(directory), server, oldClient, timeout));
  }
```

- `from` reads four keys: `tls.directory`, `tls.server.protocols`, `tls.old.client.protocols` and `client.timeout.millis` (1 to 60,000). It returns a `Result`: the settings, or one line per problem. Problems come back as a list, so a person sees every mistake in one run.
- `Path.of(directory)` converts the text to a path.

```java
  /**
   * Returns the CA certificate the client trusts.
   *
   * @return {@code <directory>/seatwave-test-ca.pem}
   */
  public Path certificateAuthority() {
    return directory.resolve("seatwave-test-ca.pem");
  }

  /**
   * Returns the keystore password file.
   *
   * @return {@code <directory>/store.password}
   */
  public Path passwordFile() {
    return directory.resolve("store.password");
  }
}
```

- Two helper methods return the files the other classes need: `seatwave-test-ca.pem` and `store.password`, both inside the directory. `directory.resolve("name")` appends a name to a path.

### `tls/TlsFailures.java`

```java
package io.plainround.seatwave.network.tls;

import io.plainround.seatwave.network.server.SeatWaveServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
```

- `HttpClient`, `HttpRequest` and `HttpResponse` are the JDK HTTP client types. `SSLSession` describes the finished handshake (version, cipher suite, certificates). `SSLParameters` holds the choices of one side.

```java
/**
 * Tries each {@link TlsScenario} against a real HTTPS server on the loopback address, with a client
 * that trusts only the TEST certificate authority and checks dates and names as every browser does.
 *
 * <p>The point is the refusals: an expired certificate, a certificate for another name and an old
 * TLS version each stop the request before a byte of HTTP is sent. That is failing safe. Thread
 * safety: each call starts its own server and client and shares nothing.
 */
public final class TlsFailures {

  // Any free port: the server lives only as long as one attempt.
  private static final int ANY_FREE_PORT = 0;

  private final TlsSettings settings;
  private final SSLContext clientContext;
```

- The class comment says the client "checks dates and names as every browser does", and trusts only the TEST CA.
- The comment also says why the refusals matter: each one stops the request before a byte of HTTP is sent. That is failing safe.
- The two fields are the settings and the client's TLS context, built once.

```java
  /**
   * Prepares the client side once.
   *
   * @param settings where the certificates are and which versions each side allows
   * @throws IOException if the CA certificate cannot be read
   * @throws GeneralSecurityException if it is not a certificate
   */
  public TlsFailures(TlsSettings settings) throws IOException, GeneralSecurityException {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clientContext = TlsMaterial.clientContext(settings.certificateAuthority());
  }
```

- The constructor stores the settings and builds the client context from the CA file. It can throw if the file is missing or not a certificate; the `throws` clause says so.

```java
  /**
   * Starts a server for the scenario, sends {@code GET /health} and reports what happened.
   *
   * @param scenario the scenario
   * @return accepted with the TLS version and cipher suite, or refused with the client's reason
   * @throws IOException if the server cannot start
   * @throws GeneralSecurityException if the server keystore cannot be opened
   * @throws InterruptedException if the thread is interrupted while waiting for the reply
   */
  public HandshakeOutcome attempt(TlsScenario scenario)
      throws IOException, GeneralSecurityException, InterruptedException {
    SSLContext serverContext =
        TlsMaterial.serverContext(
            settings.directory().resolve(scenario.keystoreFile()), settings.passwordFile());
    try (SeatWaveServer server =
            SeatWaveServer.startTls(ANY_FREE_PORT, serverContext, settings.serverProtocols());
        HttpClient client = client(scenario)) {
      HttpRequest request =
          HttpRequest.newBuilder(server.baseUri().resolve(URI.create("health")))
              .timeout(settings.timeout())
              .GET()
              .build();
      try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String negotiated =
            response.sslSession().map(TlsFailures::describe).orElse("no TLS session");
        return new HandshakeOutcome(scenario, true, response.statusCode() + " over " + negotiated);
      } catch (IOException refused) {
        // The expected failure of a bad certificate or version: the client refuses, and the
        // reason is the result, not an error of this program.
        return new HandshakeOutcome(scenario, false, refused.getMessage());
      }
    }
  }
```

- `attempt` runs one scenario.
- `TlsMaterial.serverContext(...)` loads the key store named by the scenario (`scenario.keystoreFile()`) and the shared password file.
- `try (SeatWaveServer server = ...; HttpClient client = client(scenario))` starts a real HTTPS server on any free port, with the allowed TLS versions, and builds the client. Both close at the end.
- `server.baseUri().resolve(URI.create("health"))` builds the address `https://127.0.0.1:<port>/health`: resolving the relative address `health` against the base address that ends in a slash.
- `.timeout(settings.timeout())` is the request timeout. The client has a connect timeout too (set in `client(...)`).
- `client.send(request, BodyHandlers.ofString())` sends the request. If the handshake works, the body is read as text.
- `response.sslSession()` is an `Optional<SSLSession>`: the TLS session if the response came over TLS. `.map(TlsFailures::describe)` turns it into text, and `.orElse("no TLS session")` handles the empty case. `TlsFailures::describe` is a method reference.
- The result is `new HandshakeOutcome(scenario, true, "200 over TLSv1.3 with TLS_AES_256_GCM_SHA384")`.
- `catch (IOException refused)` is the interesting part. For a bad certificate or version, the client refuses, and the reason is the result, not an error of this program. The outcome is `isAccepted = false` and the detail is the exception's message.

```java
  private HttpClient client(TlsScenario scenario) {
    HttpClient.Builder builder =
        HttpClient.newBuilder().sslContext(clientContext).connectTimeout(settings.timeout());
    if (scenario == TlsScenario.OLD_VERSION) {
      SSLParameters oldOnly = clientContext.getDefaultSSLParameters();
      oldOnly.setProtocols(settings.oldClientProtocols().toArray(String[]::new));
      builder.sslParameters(oldOnly);
    }
    return builder.build();
  }

  private static String describe(SSLSession session) {
    return session.getProtocol() + " with " + session.getCipherSuite();
  }
}
```

- `client(scenario)` builds the HTTP client: the TLS context, and a connect timeout.
- For `OLD_VERSION`, it starts from `clientContext.getDefaultSSLParameters()` and `setProtocols(...)` to offer only the old versions (`TLSv1.2`), then `builder.sslParameters(oldOnly)`. `toArray(String[]::new)` converts the list to an array.
- The HTTP client also turns on **host name checking** by itself: the JDK sets the endpoint identification algorithm `HTTPS` on every TLS connection it makes. That is why the wrong-name certificate is refused: `127.0.0.1` is not among the names in the certificate. (The default `SSLParameters` of a context have no such check, which is why a client that uses a raw `SSLSocket` must set it by hand.)
- `describe` joins the protocol and cipher suite into text.

### `tls/TlsFailuresCli.java`

```java
package io.plainround.seatwave.network.tls;

import com.github.sviperll.result4j.Result;
import io.plainround.seatwave.network.config.SettingsReader;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
```

- `Result` is from `result4j`. The other imports are this repository's settings reader and standard classes.

```java
/**
 * Command-line entry point of failure mode 4: tries the four TLS scenarios and prints what the
 * client did with each. Exit status 2 means bad usage or settings; 1 means a scenario ended the
 * wrong way (a bad certificate accepted, or the good one refused).
 */
public final class TlsFailuresCli {

  // Exit status 2 is the shell convention for "the command was used incorrectly".
  private static final int EXIT_BAD_USAGE = 2;
  private static final int EXIT_WRONG_OUTCOME = 1;

  private TlsFailuresCli() {}
```

- The class comment gives the exit statuses: 2 for bad usage or settings, and 1 when a scenario ended the wrong way (a bad certificate was accepted, or the good one was refused).

```java
  /**
   * Prints one line per scenario.
   *
   * @param arguments the settings file
   * @throws InterruptedException if interrupted while a request is in flight
   */
  public static void main(String[] arguments) throws InterruptedException {
    if (arguments.length != 1) {
      System.err.println("usage: tlsFailures <tls-failures.properties>");
      System.exit(EXIT_BAD_USAGE);
    }
    try {
      switch (TlsSettings.from(new SettingsReader(SettingsReader.load(Path.of(arguments[0]))))) {
        case Result.Success<TlsSettings, List<String>>(TlsSettings settings) -> run(settings);
        case Result.Error<TlsSettings, List<String>>(List<String> problems) -> {
          problems.forEach(problem -> System.err.println("invalid setting: " + problem));
          System.exit(EXIT_BAD_USAGE);
        }
      }
    } catch (IOException | GeneralSecurityException unusable) {
      System.err.println("cannot use the test certificates: " + unusable.getMessage());
      System.exit(EXIT_BAD_USAGE);
    }
  }
```

- `main` checks that there is exactly one argument, reads the settings file, and uses a pattern-matching `switch` over the `Result`: on success it calls `run`, on error it prints each problem line and exits with 2.
- `catch (IOException | GeneralSecurityException unusable)` covers an unreadable or invalid certificate file.

```java
  private static void run(TlsSettings settings)
      throws IOException, GeneralSecurityException, InterruptedException {
    System.out.println(
        "Server accepts "
            + String.join(", ", settings.serverProtocols())
            + "; the client trusts only the TEST CA and checks dates and names.");
    TlsFailures failures = new TlsFailures(settings);
    boolean isEveryOutcomeRight = true;
    for (TlsScenario scenario : TlsScenario.values()) {
      HandshakeOutcome outcome = failures.attempt(scenario);
      boolean shouldBeAccepted = scenario == TlsScenario.VALID;
      isEveryOutcomeRight &= outcome.isAccepted() == shouldBeAccepted;
      System.out.printf(
          Locale.ROOT,
          "%-12s %-9s %s%n",
          scenario.name().toLowerCase(Locale.ROOT),
          outcome.isAccepted() ? "accepted" : "refused",
          outcome.detail());
    }
    if (!isEveryOutcomeRight) {
      System.exit(EXIT_WRONG_OUTCOME);
    }
  }
}
```

- `run` prints a first line that says what the server accepts.
- `for (TlsScenario scenario : TlsScenario.values())` goes over the four enum values in declaration order. `values()` returns an array of all of them.
- `shouldBeAccepted = scenario == TlsScenario.VALID`: only the valid scenario should succeed.
- `isEveryOutcomeRight &= outcome.isAccepted() == shouldBeAccepted;` is `a = a & b`. The flag becomes false the first time an outcome is not what it should be, and stays false.
- `printf("%-12s %-9s %s%n", ...)` prints three columns: the scenario name in lower case padded to 12 characters on the right (`-` means left-justified), `accepted` or `refused` padded to 9, and the detail.
- `System.exit(EXIT_WRONG_OUTCOME)` at the end exits with 1 if any outcome was wrong. A refused bad certificate is **not** an error: that is the right result.

## Try it yourself

All commands run in the repository folder and use only the loopback address.

**1. Run the four handshakes.**

```text
./gradlew -q tlsFailures --args='config/tls-failures.properties'
```

```text
Server accepts TLSv1.3; the client trusts only the TEST CA and checks dates and names.
valid        accepted  200 over TLSv1.3 with TLS_AES_256_GCM_SHA384
expired      refused   (certificate_expired) PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed
wrong_name   refused   (certificate_unknown) No subject alternative names matching IP address 127.0.0.1 found
old_version  refused   (protocol_version) Received fatal alert: protocol_version
```

Read the three reasons. `certificate_expired` is the date check. `certificate_unknown` with "No subject alternative names matching IP address" is the name check. `protocol_version` is the version check. The program exits with status 0: all four outcomes were the right ones.

**2. Look at the TEST certificate authority (a public certificate, safe to print).**

```text
keytool -printcert -file build/tls/seatwave-test-ca.pem | head -4
```

```text
Owner: CN=SeatWave TEST CA - never trust outside this lab
Issuer: CN=SeatWave TEST CA - never trust outside this lab
Serial number: 60481f18c92ca90d
Valid from: Tue Nov 10 23:05:26 GMT 2026 until: Thu Dec 10 23:05:26 GMT 2026
```

The owner and issuer are the same: a CA signs its own certificate. The serial number and dates differ in every run of `testCertificates`. Never import this file into a browser or an operating system.

**3. See a TLS client refuse a server it does not trust.** Start the server (`./gradlew -q serve --args='config/serve.properties'`) and in a second terminal call it without telling curl about the TEST CA:

```text
curl -s -o /dev/null -w 'no cacert: %{http_code} %{errormsg}\n' https://127.0.0.1:8443/health
```

```text
no cacert: 000 SSL certificate problem: self signed certificate in certificate chain
```

Status 000 means no HTTP reply was received. With `--cacert build/tls/seatwave-test-ca.pem`, the same call returns `{"status":"up"} 200 1.1`. Stop the server with Ctrl-C.

**4. A TLS 1.2 client against the TLS 1.3 server.**

```text
curl -s --tlsv1.2 --tls-max 1.2 --cacert build/tls/seatwave-test-ca.pem -o /dev/null -w 'tls12 %{http_code} %{errormsg}\n' https://127.0.0.1:8443/health
```

```text
tls12 000 LibreSSL/3.3.6: error:1404B42E:SSL routines:ST_CONNECT:tlsv1 alert protocol version
```

The server's answer is the same `protocol_version` alert that the Java client printed.

## Mistakes people make

- **Switching off certificate checks to "make it work".** This project has no such option. A client that cannot verify refuses.
- **Trusting the system's CAs for a lab.** The client here builds a trust store with one entry, so the TEST CA vouches for nothing outside this lab.
- **Checking the dates but not the name.** A valid certificate for another name proves nothing about this server. The JDK client checks the name (`HTTPS` endpoint identification).
- **Committing keys or passwords.** Everything is generated into `build/tls`, which git ignores, and the password is random on each run, written to a file with owner-only permissions, never put in code or on a command line.
- **Offering old TLS versions.** The server accepts TLS 1.3 only (`tls.server.protocols`); a TLS 1.2-only client is refused.
- **Treating the refusals as test failures.** The CLI exits with 1 only if an outcome is not the expected one: the refusals are the expected ones.

## Check yourself

1. What three checks does a client make of a server's certificate? (The signature of a trusted CA, the dates, and that the name it connects to is in the certificate.)
2. Why is `store.password` a random file and not a value in the code? (A password in code or on a command line leaks into version control and process lists; a random file next to the key stores, readable by its owner only, does not.)
3. What does `clientContext` put in the trust store, and why not use the JDK's default store? (One entry: the TEST CA. The default store trusts public CAs, so using it would let any of them vouch for the lab's server.)
4. In `TlsFailures.attempt`, why does `catch (IOException refused)` return a normal `HandshakeOutcome`? (A refusal is the expected result for three of the four scenarios, so the reason is data, not an error of the program.)
5. Why does `OLD_VERSION` use the valid key store? (The certificate is fine; only the version is wrong. This shows that the version check refuses a good certificate too.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- JSSE Reference Guide (`SSLContext`, key managers, trust managers): https://docs.oracle.com/en/java/javase/25/security/java-secure-socket-extension-jsse-reference-guide.html
- `javax.net.ssl.SSLContext`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/net/ssl/SSLContext.html
- `java.security.KeyStore`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/KeyStore.html
- TLS 1.3 (RFC 8446): https://www.rfc-editor.org/rfc/rfc8446
- The Internet X.509 certificate profile (RFC 5280): https://www.rfc-editor.org/rfc/rfc5280
- HTTP over TLS, name checking (RFC 9110, Section 4.3.4): https://www.rfc-editor.org/rfc/rfc9110
