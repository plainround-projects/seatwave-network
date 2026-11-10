# Networking and TLS in Java (addresses, TCP, sockets, HTTP, TLS)

## What it is

This file teaches the network ideas that the repository's Java code uses, from zero, with the repository's own code as the examples. The ideas are: an IP address and a port, the loopback address, a TCP connection and its handshake, sockets and server sockets, HTTP and the JDK's HTTP server and HTTP client, and TLS (the handshake, certificates, keys, key stores and trust stores) with `SSLContext`. It ends with timeouts, and with what this JDK's client and server do and do not support (HTTP/2 and HTTP/3).

A **network** is a way for two programs, usually on two machines, to send bytes to each other. A **protocol** is an agreed set of rules for what those bytes mean and in which order they go. TCP, HTTP and TLS are protocols, and each one is built on the one below it.

## Why this repository uses it

The repository answers one question for Module 5 of the course: where does the time go before the first byte of a SeatWave response arrives? Every answer is a network idea: finding an address (DNS), opening a connection (the TCP handshake), setting up encryption (the TLS handshake), asking and waiting (HTTP), and the distance between the two sides (the round-trip time). The code runs a real server, a real client and real TLS, all on one machine, and adds the distance with a link that holds bytes back.

## Where it appears

| Idea | Where in the repository |
|---|---|
| IP address, port, loopback | `server/SeatWaveServer.java` (`loopback`), `link/DelayLink.java` (`start`), `config/serve.properties` |
| TCP connection, sockets, server sockets | `link/DelayLink.java` (`acceptLoop`, `serve`, `forward`, `deliver`) |
| HTTP server | `server/SeatWaveServer.java`, `server/ServeCli.java` |
| HTTP client | `timing/ReuseProbe.java`, `tls/TlsFailures.java` |
| TLS contexts, key stores, trust stores | `tls/TlsMaterial.java` |
| TLS versions the server allows | `server/SeatWaveServer.java` (`startTls`), `config/serve.properties`, `config/tls-failures.properties` |
| Refused certificates | `tls/TlsFailures.java`, `tls/TlsScenario.java` |
| Timeouts | `link/DelayLink.java`, `timing/ReuseProbe.java`, `tls/TlsFailures.java`, `config/timing.properties` |

## How it works underneath

### An IP address and a port

Every machine on a network has an **IP address**: a number that says where it is. An IPv4 address is four numbers from 0 to 255 separated by dots, such as `127.0.0.1` (32 bits in all). An IPv6 address is longer and written in hexadecimal. A program on that machine listens on a **port**: a number from 0 to 65,535 (16 bits). The address says which machine; the port says which program on it. So `127.0.0.1:8080` is "the program that listens on port 8080 on this machine". In Java, `InetAddress` is an address and `InetSocketAddress` is an address plus a port.

Ports below 1,024 have traditionally needed administrator rights to listen on, so the settings reader in `ServeCli` allows only 1,024 and above.

**Port 0** means "any free port": when a program asks to listen on port 0, the operating system picks one that is free, and the program reads it back. Tests and the timing client use it so that they never collide with another program.

### The loopback address

The **loopback address** is `127.0.0.1` (IPv6: `::1`). A message sent to it never leaves the machine: the operating system hands it straight back to a program on the same machine. It is the address for "myself". Every server and every link in this repository listens on the loopback address only, so nobody on the network can reach it. I asked the JDK for it and got:

```text
localhost/127.0.0.1
```

The text before the slash is the host name `localhost`, and the number after it is the address. The code uses the number and not the name `localhost`: a name needs a lookup (DNS), and `localhost` can mean the IPv6 address too. The certificate of the TEST server also names the number `127.0.0.1`, and the client checks that name.

### DNS

**DNS** (the Domain Name System) turns a name such as `www.example.com` into an IP address. It is the first phase of a request to a public site. This repository never needs it for SeatWave, because it uses the number directly, and that is why the `dns` reading of a loopback request is a few microseconds. (Module 6 of the course is about DNS.)

### A TCP connection and its handshake

**TCP** (Transmission Control Protocol) gives two programs a reliable, ordered stream of bytes in each direction. Bytes arrive in the order they were sent, with none lost and none repeated, or the connection fails. Everything else here (TLS, HTTP) travels inside TCP.

Before any byte can be sent, the two sides do the **three-way handshake**:

1. The client sends a SYN message: "I want a connection."
2. The server sends SYN-ACK: "Agreed, and here is mine."
3. The client sends ACK: "Got it."

The client can send data as soon as it sends the third message. So a new connection costs **one round trip** before the first byte of the request can leave. A **round trip** is a message going to the other side and the answer coming back. With a fan 80 ms away, that is 80 ms spent before anything useful happens.

The operating system does this work. A Java program only asks for a connection (`new Socket()` then `connect(...)`), and `connect` returns when the handshake is done.

A connection ends when one side closes it. Closing one direction only, while the other still works, is a **half-close**: `socket.shutdownOutput()` says "I will send no more," and the other side reads the end of the stream.

### Sockets and server sockets

A **socket** is the program's handle to one end of a TCP connection. In Java, `java.net.Socket` is one end. You read bytes from `socket.getInputStream()` and write bytes to `socket.getOutputStream()`. `in.read(buffer)` waits until some bytes arrive, fills the buffer with up to its length, and returns how many bytes it read. It returns **-1** when the other side has finished sending.

A **server socket** waits for connections. `new ServerSocket(port, backlog, address)` starts listening. `accept()` waits until a client has completed the handshake with it, and returns a `Socket` for that one client. The **backlog** is the length of the queue of finished handshakes waiting for `accept`.

The link in this repository uses both:

```java
  public static DelayLink start(int port, int upstreamPort, LinkSettings settings)
      throws IOException {
    Objects.requireNonNull(settings, "settings");
    InetAddress loopback = InetAddress.getLoopbackAddress();
    ServerSocket listener = new ServerSocket(port, SYSTEM_DEFAULT_BACKLOG, loopback);
    return new DelayLink(listener, new InetSocketAddress(loopback, upstreamPort), settings);
  }
```

```java
  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        if (!openSlots.tryAcquire()) {
          // Over the limit: refuse at once rather than accept and stall (failure mode 5).
          connectionsRefused.incrementAndGet();
          client.close();
          continue;
        }
        connectionsAccepted.incrementAndGet();
        threads.submit(() -> serve(client));
      } catch (IOException acceptFailed) {
        // accept fails when close() closed the listener, which ends the loop; any other failure
        // also ends it, and clients then see "connection refused" instead of a hang.
        return;
      }
    }
  }
```

The operating system completes the handshake with the listening socket **before** `accept` returns. That has a consequence the link has to handle. A program that sits on the loopback address cannot delay a handshake that the operating system has already finished. So `HandshakeDelay.ONE_ROUND_TRIP` holds the first bytes the client sends one extra round trip instead, so that the total time of a new connection looks like a real distant one.

**Nagle's algorithm.** To use the network well, TCP can hold a small write for a moment, hoping to merge it with the next one. That is bad for a request-and-wait protocol, where every small message needs an answer. `socket.setTcpNoDelay(true)` turns it off, and the link does that for both of its sockets:

```java
      // Small TLS and HTTP messages must leave at once; Nagle's algorithm would hold them back.
      client.setTcpNoDelay(true);
      server.setTcpNoDelay(true);
```

### HTTP

**HTTP** (HyperText Transfer Protocol) is the protocol of the web. The client sends a **request**: a line with a method and a path, some header lines, an empty line, and sometimes a body. The server sends a **response**: a status line, header lines, an empty line and a body. Here is a real request and response between `curl` and the SeatWave server (the lines that start with `>` were sent, and `<` were received):

```text
> GET /events HTTP/1.1
> Host: 127.0.0.1:8080
> User-Agent: curl/8.7.1
> Accept: */*
>
< HTTP/1.1 200 OK
< Date: Tue, 10 Nov 2026 23:10:29 GMT
< Content-type: application/json
< Server-timing: app;dur=0.71
< Content-length: 61
<
[{"eventId":1,"name":"Arena Night","startsAt":1796761251783}]
```

- `GET` is the method: "give me this resource". `HEAD` is the same, but the server sends only the headers. `/events` is the path. A **query** such as `?section=A` follows the path.
- `200` is the **status code**: 200 OK, 400 bad request, 404 not found, 405 method not allowed. The first digit says the class: 2xx success, 4xx the client's fault, 5xx the server's.
- `Content-length: 61` tells the receiver how many bytes of body follow. The body here is JSON, a text format for data.
- `Server-timing: app;dur=0.71` is a header SeatWave adds: how many milliseconds the handler took.
- HTTP/1.1 keeps the connection open after the response (**keep-alive**), so the next request can reuse it and skip the handshakes. This is the saving that the timing client measures.

### The JDK's HTTP server

The JDK ships a small web server in the package `com.sun.net.httpserver` (the module `jdk.httpserver`). `HttpServer.create(address, backlog)` makes one, `createContext("/", handler)` says which code answers which path, `setExecutor(...)` says which threads run the handlers, and `start()` starts it. A handler gets an `HttpExchange`: the request (`getRequestMethod()`, `getRequestURI()`) and the response (`getResponseHeaders()`, `sendResponseHeaders(status, length)`, `getResponseBody()`).

```java
  private SeatWaveServer(HttpServer server, String scheme) {
    this.server = server;
    this.scheme = scheme;
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }
```

```java
  private static void reply(
      HttpExchange exchange, long startNanos, int status, byte[] body, boolean isHead)
      throws IOException {
    exchange
        .getResponseHeaders()
        .set("Content-Type", status == OK ? "application/json" : "application/problem+json");
    double serverMillis = (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    exchange
        .getResponseHeaders()
        .set("Server-Timing", String.format(Locale.ROOT, "app;dur=%.2f", serverMillis));
    if (isHead) {
      exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
      exchange.sendResponseHeaders(status, NO_BODY);
      return;
    }
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }
```

The server's public class `HttpsServer` is the same server with TLS. In this repository, the server answers only **HTTP/1.1**. It does not speak HTTP/2 (see the end of this section).

### The JDK's HTTP client

The JDK ships an HTTP client in the package `java.net.http` (Java 11). `HttpClient.newBuilder()...build()` makes one. `HttpRequest.newBuilder(uri).timeout(...).GET().build()` makes a request. `client.send(request, HttpResponse.BodyHandlers.ofString())` sends it, waits for the whole response, and returns an `HttpResponse`. A **body handler** says what to do with the body: `ofString()` makes text, `ofByteArray()` makes bytes. The response tells the status (`statusCode()`), the body (`body()`), the HTTP version that was used (`version()`), and the TLS session (`sslSession()`).

A client keeps a **pool** of open connections and reuses them for later requests to the same server. That is why the timing client builds a **new** client for each run: a new client has an empty pool.

```java
    HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeouts.request()).GET().build();
    List<ReuseSample> samples = new ArrayList<>(runs);
    for (int runIndex = 0; runIndex < runs; runIndex++) {
      try (HttpClient client =
          HttpClient.newBuilder()
              .version(version)
              .sslContext(clientContext)
              .connectTimeout(timeouts.connect())
              .build()) {
        Timed first = timed(client, request);
        Timed second = timed(client, request);
        samples.add(new ReuseSample(first.elapsed(), second.elapsed(), second.protocol()));
      }
    }
    return List.copyOf(samples);
  }
```

To see exactly what the JDK client sends, I wrote a small program (not part of the repository) with a raw server socket on the loopback address that printed the lines of the request, and sent one request with `HttpClient.Version.HTTP_2` asked for over plain HTTP. It received:

```text
GET /health?x=1 HTTP/1.1
Connection: Upgrade, HTTP2-Settings
Host: 127.0.0.1:58703
HTTP2-Settings: AAEAAEAAAAIAAAAAAAMAAAAAAAQBAAAAAAUAAEAAAAYABgAA
Upgrade: h2c
User-Agent: Java-http-client/25.0.2
```

It is an ordinary HTTP/1.1 request with an extra header, `Upgrade: h2c`, which means: "if you speak HTTP/2 over plain TCP, switch now". A server that does not understand it (like the JDK server) ignores it and answers HTTP/1.1.

### What TLS adds

Plain HTTP travels as readable text. Anyone on the path can read it and change it, and the client cannot tell whether the server is the one it meant to reach. **TLS** (Transport Layer Security) fixes both. It adds three things on top of TCP:

1. **Privacy**: the bytes are encrypted, so a listener sees noise.
2. **Integrity**: a change to the bytes is detected.
3. **Identity**: the client checks that the server is who it claims to be, using a **certificate**.

**HTTPS** is HTTP inside TLS inside TCP. The address starts with `https://` and the default port is 443 (this repository uses 8443).

**A key pair.** The server has a **private key**, which it never shows, and a **public key**, which anyone may see. Something signed with the private key can be checked with the public key, and only the owner of the private key could have made the signature.

**A certificate** is a signed statement: "this public key belongs to this name, from this date until that date". A **certificate authority** (CA) signs it with the CA's own private key. A client that trusts the CA can check the signature. The certificate of the SeatWave test server, seen by curl:

```text
*  subject: CN=SeatWave TEST server (valid)
*  start date: Nov 10 23:05:26 2026 GMT
*  expire date: Dec 10 23:05:26 2026 GMT
*  subjectAltName: host "127.0.0.1" matched cert's IP address!
*  issuer: CN=SeatWave TEST CA - never trust outside this lab
*  SSL certificate verify ok.
```

- `subject` is the owner. `issuer` is the CA that signed it.
- `start date` and `expire date` are its validity period (30 days here).
- `subjectAltName` is the list of names the certificate is valid for. Here it includes the IP address `127.0.0.1`.

**The TLS handshake (TLS 1.3).** After the TCP handshake, the client and the server exchange these messages (the names are curl's, from its verbose output):

```text
(OUT), TLS handshake, Client hello (1)
(IN),  TLS handshake, Server hello (2)
(IN),  TLS handshake, Unknown (8)           <- the server's extensions
(IN),  TLS handshake, Certificate (11)
(IN),  TLS handshake, CERT verify (15)
(IN),  TLS handshake, Finished (20)
(OUT), TLS handshake, Finished (20)
SSL connection using TLSv1.3 / AEAD-AES256-GCM-SHA384
```

- The **Client hello** lists the TLS versions and ciphers the client offers, and a share of a key.
- The **Server hello** picks a version and a cipher suite, and sends its share. From here on both sides can compute the same secret key without ever sending it, and everything after is encrypted.
- The server sends its **Certificate**, and a **CERT verify**: a signature made with its private key, which proves it owns the key in the certificate.
- Both sides send **Finished**: a check over the whole handshake.

TLS 1.3 needs **one round trip** for this (the client's first message and the server's answer). TLS 1.2 needs two. Over plain HTTP there is no TLS handshake, so the `tls` phase of a request is 0. A **cipher suite** is the named set of algorithms used to protect the connection, such as `TLS_AES_256_GCM_SHA384` (the one the Java client reported).

**Why a client refuses a certificate.** A client checks four things. If any fails, it ends the handshake with an **alert** (a TLS message that says why) and sends no request.

| Check | Refused when | Alert seen in this repository |
|---|---|---|
| The signature | The issuer is not a CA the client trusts (an **unknown issuer**). | curl with no `--cacert` said `self signed certificate in certificate chain`. (The Java lab does not include this case.) |
| The dates | The current time is after the end date (**expired**) or before the start date. | `certificate_expired` |
| The name | The name the client connected to is not in the certificate (**wrong name**). | `certificate_unknown` with "No subject alternative names matching IP address 127.0.0.1 found" |
| The version | The two sides share no TLS version. | `protocol_version` |

### `SSLContext`, key stores and trust stores

In Java, `javax.net.ssl.SSLContext` is the object that holds everything TLS needs for one side: the **key managers** (this side's own keys and certificates: "who am I") and the **trust managers** (the CAs this side accepts: "whom do I trust"), plus the random source. You make one with `SSLContext.getInstance("TLS")` and fill it with `context.init(keyManagers, trustManagers, random)`. A `null` argument means "use the JDK's default". A server is built with a `SSLContext` and starts accepting TLS connections; a client passes its context to the `HttpClient` builder with `.sslContext(...)`.

A **key store** is a password-protected file that holds private keys with their certificates. A **trust store** is a store that holds only certificates of CAs. Both are `java.security.KeyStore` objects, so the same class and the same file format serve both. The format here is **PKCS12** (`.p12`), the JDK's default since Java 9. The JDK also has a built-in trust store of public CAs, `lib/security/cacerts` inside the JDK folder (a 187,607-byte file on the machine used here). This repository **does not use it**: the client builds its own trust store with one entry, the TEST CA, so nothing but the TEST CA can vouch for a server.

The two builders, in `TlsMaterial`:

```java
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

```java
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

Look at what is **not** there: there is no `TrustManager` that accepts everything. Code that disables certificate checking is a common mistake, and it removes the whole point of TLS.

### Choosing the TLS versions

The JDK 25 default for a TLS client is TLS 1.3 and TLS 1.2. I printed the defaults of a fresh context:

```text
default client [TLSv1.3, TLSv1.2]
```

The context also knows older versions (`TLSv1.1`, `TLSv1`, `SSLv3`) but the JDK does not enable them by default. The SeatWave server narrows the list further with an `HttpsConfigurator`, so a server can accept TLS 1.3 only:

```java
  public static SeatWaveServer startTls(int port, SSLContext context, List<String> protocols)
      throws IOException {
    Objects.requireNonNull(context, "context");
    String[] accepted = List.copyOf(protocols).toArray(String[]::new);
    if (accepted.length == 0) {
      throw new IllegalArgumentException("at least one TLS version is needed");
    }
    HttpsServer https = HttpsServer.create(loopback(port), SYSTEM_DEFAULT_BACKLOG);
    https.setHttpsConfigurator(
        new HttpsConfigurator(context) {
          @Override
          public void configure(HttpsParameters parameters) {
            SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
            // Only the listed versions: an older version a client offers is refused in the
            // handshake, which is the fail-safe answer (Module 5, Section 9).
            ssl.setProtocols(accepted);
            parameters.setSSLParameters(ssl);
          }
        });
    return new SeatWaveServer(https, "https");
  }
```

A client that offers only TLS 1.2 to this server is refused with `protocol_version`: this is the `old_version` scenario of `tls/TlsFailures.java`.

### Name checking is the client's job

A `SSLContext` verifies the certificate chain and the dates. Checking that the **name** matches is a separate step called **endpoint identification**. The JDK's `HttpClient` turns it on by itself for every TLS connection (it sets the identification algorithm `HTTPS`; I confirmed that string in the JDK's own class). A hand-written client that uses a raw `SSLSocket` must set it. A default `SSLContext` does not: its default parameters showed `null` for that setting. That is why the wrong-name certificate is refused by the `HttpClient`.

### Timeouts on every network call

A network call can wait forever: the other side may be dead, or the path may be cut, and TCP does not give up by itself for a long time. So **every network call has a timeout**. Here is each one in this repository:

| Where | What it limits | Value |
|---|---|---|
| `ReuseProbe`: `HttpClient...connectTimeout(...)` | Opening the TCP connection | 5,000 ms (`client.connect.timeout.millis`) |
| `ReuseProbe`: `HttpRequest...timeout(...)` | Waiting for a whole response | 10,000 ms (`client.request.timeout.millis`) |
| `TlsFailures`: `connectTimeout` and the request `timeout` | The same two limits in the TLS lab | 5,000 ms (`client.timeout.millis`) |
| `DelayLink.serve`: `server.connect(upstream, timeout)` | Reaching the real server from the link | 5 seconds |
| `DelayLink.forward`: `inFlight.offer(chunk, timeout, ...)` | Waiting for room in a full queue | 30 seconds |
| `scripts/curl-timings.sh`: `--connect-timeout 5 --max-time 10` | curl's connect and its whole transfer | 5 s and 10 s |

I tried what a timeout looks like: a server that accepts a connection but never answers, with a 300 ms request timeout:

```text
java.net.http.HttpTimeoutException: request timed out after 305 ms
```

And a connection to a port where nothing listens:

```text
java.net.ConnectException: null
```

One honest gap: the link's `in.read(buffer)` has no read timeout. A client that opens a connection through the link and then says nothing keeps its slot and its threads until it closes the connection or the link is closed. The link bounds the number of connections (`link.max.connections`) instead.

### What this JDK's client and server support

| | JDK 25 server (`com.sun.net.httpserver`) | JDK 25 client (`java.net.http`) |
|---|---|---|
| HTTP/1.1 | Yes, with keep-alive. | Yes. |
| HTTP/2 | **No.** | Yes: it may be asked for. |
| HTTP/3 | **No.** | **No.** |

How I know:

- **Client.** `HttpClient.Version.values()` printed `[HTTP_1_1, HTTP_2]`. There is no HTTP_3 constant in JDK 25. (A later JDK adds HTTP/3 to the client; I could not check that here, because only JDK 25 is installed. This repository's `switch` over the version has two cases and would stop compiling if a third constant appeared.)
- **Server.** I saw it behave as an HTTP/1.1 server: with HTTP/2 asked for, over plain HTTP the client sends `Upgrade: h2c` and the JDK server ignores it, and over HTTPS, curl, which offers `h2` and `http/1.1`, printed `ALPN: server did not agree on a protocol` and `using HTTP/1.x`.
- **The timing table** shows both columns: `requested HTTP_2`, `answered HTTP/1.1`, for every scenario, over HTTP and over HTTPS.

**ALPN** (Application-Layer Protocol Negotiation) is a field in the TLS handshake where the client lists the application protocols it can speak (`h2`, `http/1.1`) and the server picks one. A server that picks none leaves the connection at HTTP/1.1.

**What HTTP/2 and HTTP/3 are, briefly.** HTTP/2 sends many requests at once over **one** TCP connection, as separate **streams**. Because TCP delivers bytes in order, one lost packet holds back every stream (head-of-line blocking). HTTP/3 uses **QUIC**, a protocol that runs over UDP and gives each stream its own order, so a loss holds back only its own stream. QUIC also combines the transport and TLS handshakes in one round trip. The real servers of the public sites in the README answer HTTP/2. HTTP/3 is not measured anywhere in this repository: the page model (`java-model-and-config-walkthrough.md`) shows what it would change, as arithmetic.

## Walkthrough of this repository's use

The code that carries these ideas is walked through line by line in:

- `java-server-and-link-walkthrough.md`: the HTTP server, the delay link (server sockets, sockets, queues).
- `java-timing-walkthrough.md`: the HTTP client and its timeouts.
- `java-tls-walkthrough.md`: `SSLContext`, key stores, trust stores and the three refusals.

The pieces that tie the ideas together, in one place:

1. **ServeCli starts three listeners** (`serve`): plain HTTP on port 8080, HTTPS on port 8443 (TLS 1.3 only), and the delay link on port 8444 that forwards to port 8443.
2. **The TEST certificates** are made by `./gradlew testCertificates` (see `keytool-test-certificates.md`) into `build/tls`. The server's key store and the CA's public certificate are the files `TlsMaterial` reads.
3. **The timing client** builds a new `HttpClient` per run and times two requests, the first on a new connection and the second on the reused one.
4. **The delay link** adds the distance: 40 ms to every chunk in each direction, and one extra round trip to the first client bytes (to stand for the TCP handshake).
5. **The TLS failure lab** starts a server with a bad certificate and has the client refuse it.

## Try it yourself

All commands run in the repository folder and use only the loopback address. Start the server in one terminal with `./gradlew -q serve --args='config/serve.properties'` and use a second terminal for the others. Press Ctrl-C in the first one when you have finished.

**1. Watch HTTP as text.**

```text
curl -sv http://127.0.0.1:8080/events
```

You should see the `>` lines (the request), the `<` lines (the response) and the JSON body shown above. `Content-length: 61` matches the 61 bytes of the body.

**2. Watch the TLS handshake and the certificate.**

```text
curl -sv --cacert build/tls/seatwave-test-ca.pem https://127.0.0.1:8443/health
```

Look for the handshake lines, `SSL connection using TLSv1.3`, the `subject`, `issuer` and dates of the certificate, `host "127.0.0.1" matched cert's IP address!`, `SSL certificate verify ok.`, and `ALPN: server did not agree on a protocol`.

**3. Refuse an unknown issuer.**

```text
curl -s -o /dev/null -w 'no cacert: %{http_code} %{errormsg}\n' https://127.0.0.1:8443/health
```

```text
no cacert: 000 SSL certificate problem: self signed certificate in certificate chain
```

**4. Refuse an old TLS version.**

```text
curl -s --tlsv1.2 --tls-max 1.2 --cacert build/tls/seatwave-test-ca.pem -o /dev/null -w 'tls12 %{http_code} %{errormsg}\n' https://127.0.0.1:8443/health
```

```text
tls12 000 LibreSSL/3.3.6: error:1404B42E:SSL routines:ST_CONNECT:tlsv1 alert protocol version
```

**5. The three refusals, from Java.**

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

**6. The cost of a new connection against a reused one.**

```text
./gradlew -q timing --args='config/lab2-distances.properties'
```

A new HTTPS connection costs about two round trips more than a request on a reused one. One real run printed about 71, 140 and 257 ms for the new connections against about 23, 45 and 85 ms for the reused ones, at round-trip times of 20, 40 and 80 ms (digits differ on every run).

## Mistakes people make

- **Switching off certificate checks.** A client that accepts any certificate is encrypted but not secure: anyone can be the server. This repository has no such option.
- **Trusting a CA that is not needed.** A trust store with one entry is smaller to reason about than the JDK's list of public CAs.
- **Checking the certificate but not the name.** A valid certificate for another name proves nothing about this server.
- **A call with no timeout.** It can hang forever.
- **Listening on all addresses.** `0.0.0.0` listens on every network interface. This code listens on the loopback address only.
- **Forgetting that a new connection is expensive.** One TCP handshake plus one TLS handshake is about two round trips before the first request. Reuse the connection (keep-alive), and build a client once in service code.
- **Expecting HTTP/2 from a server that cannot speak it.** The requested and the answered version are different things.
- **Putting a private key or a password in the repository.** The keys are generated into `build/tls`, which git ignores.
- **Measuring one request.** One request is a sample; the median of at least three is a measurement.

## Check yourself

1. What are the three steps of the TCP handshake, and how many round trips does it cost before the first byte of a request can leave? (SYN, SYN-ACK, ACK. One round trip.)
2. What does port 0 mean, and what is the loopback address? (Port 0: any free port, chosen by the operating system. Loopback: `127.0.0.1`, an address that never leaves the machine.)
3. What does a TLS certificate say, and which three checks does a client make? (That a public key belongs to a name for a period, signed by a CA. The client checks the signature of a trusted CA, the dates and the name.)
4. In `TlsMaterial.clientContext`, why is the JDK's default trust store not used? (So that only the TEST CA can vouch for the lab's server. The default store trusts public CAs.)
5. What does the table of the timing client show in the "requested" and "answered" columns when HTTP/2 is requested, and why? (`HTTP_2` and `HTTP/1.1`. The JDK server does not speak HTTP/2, so the answer stays on HTTP/1.1.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25), and the protocol specifications:

- `com.sun.net.httpserver`: https://docs.oracle.com/en/java/javase/25/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html
- `java.net.http` (the HTTP client): https://docs.oracle.com/en/java/javase/25/docs/api/java.net.http/java/net/http/package-summary.html
- `java.net.ServerSocket` and `java.net.Socket`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/package-summary.html
- JSSE Reference Guide (`SSLContext`, key stores, trust stores, default protocols): https://docs.oracle.com/en/java/javase/25/security/java-secure-socket-extension-jsse-reference-guide.html
- TCP (RFC 9293): https://www.rfc-editor.org/rfc/rfc9293
- HTTP semantics (RFC 9110) and HTTP/1.1 (RFC 9112): https://www.rfc-editor.org/rfc/rfc9110 and https://www.rfc-editor.org/rfc/rfc9112
- HTTP/2 (RFC 9113) and HTTP/3 (RFC 9114): https://www.rfc-editor.org/rfc/rfc9113 and https://www.rfc-editor.org/rfc/rfc9114
- QUIC (RFC 9000): https://www.rfc-editor.org/rfc/rfc9000
- TLS 1.3 (RFC 8446): https://www.rfc-editor.org/rfc/rfc8446
- ALPN (RFC 7301): https://www.rfc-editor.org/rfc/rfc7301
