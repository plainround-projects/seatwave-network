# seatwave-network

SeatWave's read endpoints served over plain HTTP and over HTTPS on the loopback address, a link that adds the round-trip time of a distant fan, a Java timing client that measures a new connection against a reused one, a `curl -w` script and a report that splits every request into DNS, connect, TLS, first byte and transfer, the TLS failures a client must refuse, and a model of page loads over HTTP/1.1, HTTP/2 and HTTP/3 with packet loss.

## Which module this belongs to

This is the hands-on project of Module 5, "How bytes travel", of the course System Design Fundamentals. The running case is SeatWave, a ticket-booking service with four operations: list events, view seats, hold a seat and confirm a booking.

Module 5 asks where the time goes before the first byte of a SeatWave response arrives, what keep-alive, HTTP/2 and HTTP/3 change, and what TLS costs and protects. This repository holds:

- SeatWave's `GET /events`, `GET /events/1/seats?section=A` (Module 1's 36,894-byte seat map) and `GET /health` on the JDK's own HTTP server, over HTTP and HTTPS, with a `Server-Timing` header on every response (`serve` task).
- A delay link on the loopback address that holds every chunk of bytes 40 ms in each direction, so one machine can show what a fan 80 ms away pays.
- The Java timing client (`timing` task): the JDK `HttpClient` asks for HTTP/1.1 and HTTP/2, and the table shows the median first request on a new connection, the median request on the reused connection, and how many connections the runs opened.
- `scripts/curl-timings.sh` and the report (`report` task): `curl -w` lines turned into a per-phase table of median runs.
- The TLS failures (`tlsFailures` task): an expired certificate, a certificate for another name and a TLS 1.2-only client, each refused.
- The page model (`pageModel` task): round trips per protocol, and what 2 percent packet loss does to each (Lab 3).

## Prerequisites

- JDK 25. The build was verified on Eclipse Temurin 25.0.2 on macOS on 10 November 2026. The current JDK 25 update on that date was 25.0.5 (build 25.0.5+8), which was not installed on the verification machine. The JDK's `keytool` makes the test certificates.
- `curl` and `openssl` for the shell commands. The commands below ran with curl 8.7.1 (the macOS system curl, built with nghttp2 for HTTP/2 and without HTTP/3) and OpenSSL 4.0.3.
- Nothing else. The Gradle wrapper (9.9.1) downloads Gradle and the libraries below on the first build.

## Dependencies and build plugins

Every version is exact. Each was checked on Maven Central on 10 November 2026 and against the OSV database on the same day, with no known vulnerability at the pinned version. The versions live in `gradle/libs.versions.toml`, and `gradle.lockfile` pins the transitive ones. There is no new runtime library: the servers, the TLS, the HTTP client and the link are the JDK's own.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `com.github.sviperll:result4j` | 1.2.0 | 10 Nov 2026 | Apache-2.0 | The JDK has no success-or-typed-error type, so a bad settings file or a bad curl line would have to be an exception. |
| `org.junit:junit-bom` and `org.junit.jupiter:junit-jupiter` | 6.1.3 | 10 Nov 2026 | EPL-2.0 | The test engine. |
| `org.assertj:assertj-core` | 3.27.7 | 10 Nov 2026 | Apache-2.0 | Fluent assertions with clear failure messages. |
| `com.diffplug.spotless` (Gradle plugin) | 8.11.0 | 10 Nov 2026 | Apache-2.0 | Runs the formatter in the build. |
| `com.google.googlejavaformat:google-java-format` (used by Spotless) | 2.0.0 | 10 Nov 2026 | Apache-2.0 | One enforced code style. |
| `org.jacoco:org.jacoco.agent` and the JaCoCo Gradle plugin | 0.8.16 | 10 Nov 2026 | EPL-2.0 | Measures line coverage and enforces the floor. |

`org.junit.platform:junit-platform-launcher` is also on the test runtime path; its version comes from the JUnit BOM.

## The test certificates

`./gradlew testCertificates` (every run task and the tests call it first) uses the JDK's `keytool` to make, in `build/tls`:

- `seatwave-test-ca.p12` and `seatwave-test-ca.pem`: a **TEST** certificate authority, "SeatWave TEST CA - never trust outside this lab".
- `seatwave-valid.p12`, `seatwave-expired.p12` and `seatwave-wrong-name.p12`: three server keys with certificates that CA signed. The valid one names `localhost` and `127.0.0.1`; the expired one ran out two days before the build; the wrong-name one names only `seatwave.test`.
- `store.password`: a random keystore password, new on every run, readable by its owner only.

These are throwaway test certificates for this lab. Never import the CA into a browser or an operating system. Nothing in `build/` is committed, and `.gitignore` also refuses `*.p12`, `*.jks`, `*.pem`, `*.key`, `*.crt` and `*.password` anywhere in the tree. The task runs again on a new day, so the certificates never expire under a reader.

## Build and test

```
./gradlew clean check
```

This makes the test certificates, checks formatting with Spotless, compiles with `-Xlint:all -Werror`, runs the tests and fails if line coverage drops below 0.80. Run `./gradlew spotlessApply` first to fix formatting. Every test talks only to servers it starts on the loopback address.

Result on 10 November 2026 (Temurin 25.0.2, macOS): `BUILD SUCCESSFUL` in 5 seconds, 62 tests, 0 failures, line coverage 86.7 percent (1,034 of 1,192 lines). The untested lines are mostly the `main` methods of the command-line classes, which print and call `System.exit`.

## The measuring machine and the network

Every number below came from one machine: an Apple M2 Max MacBook Pro (12 cores, 32 GB of memory), macOS 27.2, Eclipse Temurin 25.0.2, with other programs running. The course's reference machine is 4 vCPU and 16 GB in one cloud region; the loopback numbers describe this Mac, not that machine. The public sites were measured on 10 November 2026, 22:37 UTC over the Mac's ordinary internet connection, which the course brief describes as home broadband. **Your numbers will differ.**

## The Java timing client

```
./gradlew -q timing --args='config/timing.properties'
```

```
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave on the JDK HTTP server at 127.0.0.1; every request: GET /events/1/seats?section=A
7 measured runs per row after 3 warm-up runs; each run = a new client, one request on a new connection, then one on the same connection

scenario       requested  answered   runs  new conn p50 ms  reused p50 ms  saved by reuse ms  connections opened
simple         HTTP_1_1   HTTP/1.1      7             1.64           1.20               0.44                   7
simple         HTTP_2     HTTP/1.1      7             1.47           1.11               0.36                   7
local-tls      HTTP_1_1   HTTP/1.1      7             7.72           1.83               5.89                   7
local-tls      HTTP_2     HTTP/1.1      7             6.45           1.51               4.94                   7
distant-http   HTTP_1_1   HTTP/1.1      7           166.62          84.81              81.81                   7
distant-http   HTTP_2     HTTP/1.1      7           164.51          83.37              81.14                   7
distant-tls    HTTP_1_1   HTTP/1.1      7           255.96          84.04             171.92                   7
distant-tls    HTTP_2     HTTP/1.1      7           257.19          82.91             174.28                   7
```

`simple` is plain HTTP on this machine; `local-tls` is HTTPS on this machine; `distant-http` and `distant-tls` go through the delay link with 40 ms each way and the TCP handshake's round trip charged to the first bytes, as for a fan 80 ms away. Every run opened exactly one connection for its two requests, so the second request reused it. The JDK server does not offer HTTP/2, so the requests that asked for it were answered with HTTP/1.1.

Lab 2 runs the same client at three round-trip times:

```
./gradlew -q timing --args='config/lab2-distances.properties'
```

```
Java 25.0.2+10-LTS on Mac OS X aarch64; SeatWave on the JDK HTTP server at 127.0.0.1; every request: GET /events/1/seats?section=A
7 measured runs per row after 3 warm-up runs; each run = a new client, one request on a new connection, then one on the same connection

scenario       requested  answered   runs  new conn p50 ms  reused p50 ms  saved by reuse ms  connections opened
rtt-20ms       HTTP_1_1   HTTP/1.1      7            73.32          23.33              49.99                   7
rtt-40ms       HTTP_1_1   HTTP/1.1      7           145.28          46.03              99.25                   7
rtt-80ms       HTTP_1_1   HTTP/1.1      7           262.52          86.83             175.69                   7
```

## Serve SeatWave, and time it with curl

```
./gradlew -q serve --args='config/serve.properties'
```

```
plain HTTP: http://127.0.0.1:8080/
HTTPS:      https://127.0.0.1:8443/
distant:    https://127.0.0.1:8444/ (adds 80 ms of round-trip time)
serving for 300 s; TLS versions TLSv1.3; at most 256 connections each
```

In a second terminal, while it serves (it stops by itself after 300 seconds):

```
scripts/curl-timings.sh config/targets-seatwave.txt 5 build/timings/seatwave.txt --run --yes --reuse
```

```
plan: 5 run(s) x 3 target(s) x 2 request(s) = 30 requests
  seatwave-http  http://127.0.0.1:8080/events/1/seats?section=A
  seatwave-https  https://127.0.0.1:8443/events/1/seats?section=A
  seatwave-distant  https://127.0.0.1:8444/events/1/seats?section=A
wrote 30 lines to build/timings/seatwave.txt; audit line added to build/timings/audit.log
```

The script is a dry run unless `--run` is given, asks before sending unless `--yes` is given, accepts only SeatWave on 127.0.0.1 or a public home page, refuses to overwrite an output file, checks the line count afterwards and appends an audit line to `build/timings/audit.log`. Its first lines of output:

```
seatwave-http dns=0.000073 connect=0.000260 tls=0.000000 ttfb=0.026455 total=0.026523 http=1.1 code=200 size=36894 connects=1 server=app;dur=2.54
seatwave-http dns=0.000008 connect=0.000000 tls=0.000000 ttfb=0.001335 total=0.001374 http=1.1 code=200 size=36894 connects=0 server=app;dur=0.53
seatwave-http dns=0.000055 connect=0.000223 tls=0.000000 ttfb=0.001164 total=0.001256 http=1.1 code=200 size=36894 connects=1 server=app;dur=0.49
seatwave-http dns=0.000006 connect=0.000000 tls=0.000000 ttfb=0.001131 total=0.001212 http=1.1 code=200 size=36894 connects=0 server=app;dur=0.56
```

Inspect the handshake and the certificate:

```
openssl s_client -connect 127.0.0.1:8443 -CAfile build/tls/seatwave-test-ca.pem -verify_ip 127.0.0.1 -brief < /dev/null
openssl s_client -connect 127.0.0.1:8443 -tls1_2 -brief < /dev/null
```

```
Connecting to 127.0.0.1
Can't use SSL_get_servername
CONNECTION ESTABLISHED
Protocol version: TLSv1.3
Ciphersuite: TLS_AES_256_GCM_SHA384
Peer certificate: CN=SeatWave TEST server (valid)
Hash used: SHA256
Signature type: ecdsa_secp256r1_sha256
Verification: OK
Peer Temp Key: X25519, 253 bits
DONE

Connecting to 127.0.0.1
0013070A02000000:error:0A00042E:SSL routines:ssl3_read_bytes:tlsv1 alert protocol version:ssl/record/rec_layer_s3.c:923:SSL alert number 70
```

## Three public sites

Measured on 10 November 2026, 22:37 UTC: `example.com`, `www.wikipedia.org` and `www.kernel.org`, three long-lived, non-commercial sites. Each got 3 `GET` requests of its home page from curl and one TLS handshake from `openssl s_client`, and nothing else.

```
scripts/curl-timings.sh config/targets-public.txt 3 build/timings/public.txt --run --yes
```

```
example-com dns=0.019980 connect=0.029932 tls=0.128720 ttfb=0.149608 total=0.149721 http=2 code=200 size=577 connects=1 server=
example-com dns=0.002747 connect=0.015617 tls=0.037433 ttfb=0.056798 total=0.056929 http=2 code=200 size=577 connects=1 server=
example-com dns=0.003439 connect=0.016103 tls=0.033832 ttfb=0.051233 total=0.051313 http=2 code=200 size=577 connects=1 server=
wikipedia-org dns=0.019270 connect=0.029171 tls=0.066634 ttfb=0.091192 total=0.142217 http=2 code=200 size=122506 connects=1 server=cache;desc="hit-front", host;desc="cp3073",co_id;desc="823599087"
wikipedia-org dns=0.003581 connect=0.016496 tls=0.048905 ttfb=0.074003 total=0.207434 http=2 code=200 size=122506 connects=1 server=cache;desc="hit-front", host;desc="cp3073",co_id;desc="3453129331"
wikipedia-org dns=0.004221 connect=0.016793 tls=0.049926 ttfb=0.076657 total=0.117214 http=2 code=200 size=122506 connects=1 server=cache;desc="hit-front", host;desc="cp3073",co_id;desc="3841431895"
kernel-org dns=0.195118 connect=0.206349 tls=0.239645 ttfb=0.350070 total=0.362529 http=2 code=200 size=20436 connects=1 server=
kernel-org dns=0.004081 connect=0.017853 tls=0.049194 ttfb=0.069300 total=0.083861 http=2 code=200 size=20436 connects=1 server=
kernel-org dns=0.004081 connect=0.015063 tls=0.047454 ttfb=0.070719 total=0.083307 http=2 code=200 size=20436 connects=1 server=
```

```
openssl s_client -connect example.com:443 -servername example.com -alpn h2,http/1.1 -verify_return_error < /dev/null > openssl-example.com.txt
```

```
$ grep -E 'Connecting to|^ 0 s:|^   v:|Negotiated|Temp Key|Protocol:|Cipher is|ALPN|^Verify return code' openssl-example.com.txt | awk '!/^   v:/ || !seen++'
Connecting to 104.20.23.154
 0 s:CN=example.com
   v:NotBefore: Sep 26 22:49:11 2026 GMT; NotAfter: Dec 25 22:56:35 2026 GMT
Negotiated TLS1.3 group: X25519MLKEM768
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Protocol: TLSv1.3
ALPN protocol: h2
Verify return code: 0 (ok)
```

The same command for `www.wikipedia.org` and `www.kernel.org` printed:

```
$ grep -E 'Connecting to|^ 0 s:|^   v:|Negotiated|Temp Key|Protocol:|Cipher is|ALPN|^Verify return code' openssl-www.wikipedia.org.txt | awk '!/^   v:/ || !seen++'
Connecting to 185.15.59.224
 0 s:CN=*.wikipedia.org
   v:NotBefore: Oct  4 18:34:51 2026 GMT; NotAfter: Jan  2 18:34:50 2027 GMT
Peer Temp Key: X25519, 253 bits
New, TLSv1.3, Cipher is TLS_AES_128_GCM_SHA256
Protocol: TLSv1.3
ALPN protocol: h2
Verify return code: 0 (ok)
$ grep -E 'Connecting to|^ 0 s:|^   v:|Negotiated|Temp Key|Protocol:|Cipher is|ALPN|^Verify return code' openssl-www.kernel.org.txt | awk '!/^   v:/ || !seen++'
Connecting to 199.232.53.55
 0 s:CN=cdn.kernel.org
   v:NotBefore: Oct 13 00:27:12 2026 GMT; NotAfter: Apr 29 23:27:12 2027 GMT
Negotiated TLS1.3 group: X25519MLKEM768
New, TLSv1.3, Cipher is TLS_AES_128_GCM_SHA256
Protocol: TLSv1.3
ALPN protocol: h2
Verify return code: 0 (ok)
```

[not run] HTTP/3: the curl on the verification machine has no HTTP3 feature (`curl --version` lists `HTTP2` and not `HTTP3`), so no request used HTTP/3. [not run] The browser's developer tools: a waterfall is captured by hand in a browser, which this run did not drive; Module 5, Section 6 says how to read one.

## The report

```
./gradlew -q report --args='build/timings/public.txt build/timings/seatwave.txt'
```

```
target            connection protocol  runs   dns ms connect ms   tls ms first byte ms transfer ms   total ms   fastest  slowest  dominant
example-com       new        HTTP/2       3     2.75      12.87    21.82         19.37        0.13      56.93     51.31   149.72  tls
wikipedia-org     new        HTTP/2       3    19.27       9.90    37.46         24.56       51.03     142.22    117.21   207.43  transfer
kernel-org        new        HTTP/2       3     4.08      13.77    31.34         20.11       14.56      83.86     83.31   362.53  tls
seatwave-http     new        HTTP/1.1     5     0.05       0.16     0.00          0.89        0.07       1.16      0.94    26.52  first byte
seatwave-http     reused     HTTP/1.1     5     0.01       0.00     0.00          0.78        0.03       0.82      0.76     1.37  first byte
seatwave-https    new        HTTP/1.1     5     0.08       0.17     6.46          1.17        1.51       9.39      6.97    40.95  tls
seatwave-https    reused     HTTP/1.1     5     0.01       0.00     0.00          0.71        1.61       2.34      1.38     2.73  transfer
seatwave-distant  new        HTTP/1.1     5     0.08       0.18   170.05         84.01        1.05     255.36    252.53   258.13  tls
seatwave-distant  reused     HTTP/1.1     5     0.03       0.00     0.00         83.28        1.31      84.61     82.28    85.17  first byte

example-com, new connection: tls dominates the median run, 21.82 of 56.93 ms (38 percent).
wikipedia-org, new connection: transfer dominates the median run, 51.03 of 142.22 ms (36 percent).
kernel-org, new connection: tls dominates the median run, 31.34 of 83.86 ms (37 percent).
seatwave-http, new connection: first byte dominates the median run, 0.89 of 1.16 ms (77 percent).
seatwave-http, reused connection: first byte dominates the median run, 0.78 of 0.82 ms (95 percent).
seatwave-https, new connection: tls dominates the median run, 6.46 of 9.39 ms (69 percent).
seatwave-https, reused connection: transfer dominates the median run, 1.61 of 2.34 ms (69 percent).
seatwave-distant, new connection: tls dominates the median run, 170.05 of 255.36 ms (67 percent).
seatwave-distant, reused connection: first byte dominates the median run, 83.28 of 84.61 ms (98 percent).
```

Every row is the median run of its target and kind of connection, chosen by total time, with that run's own phases, so the phases sum to the total. A group with fewer than 3 runs, or a line whose readings are out of order or whose status is not 2xx, is listed as refused, never printed as a row.

## The TLS failures

```
./gradlew -q tlsFailures --args='config/tls-failures.properties'
```

```
Server accepts TLSv1.3; the client trusts only the TEST CA and checks dates and names.
valid        accepted  200 over TLSv1.3 with TLS_AES_256_GCM_SHA384
expired      refused   (certificate_expired) PKIX path validation failed: java.security.cert.CertPathValidatorException: validity check failed
wrong_name   refused   (certificate_unknown) No subject alternative names matching IP address 127.0.0.1 found
old_version  refused   (protocol_version) Received fatal alert: protocol_version
```

## The page model (Lab 3)

```
./gradlew -q pageModel --args='config/round-trips.properties'
./gradlew -q pageModel --args='config/page-model.properties'
./gradlew -q pageModel --args='config/page-model-six-maps.properties'
```

```
MODEL, not a measurement. Distance 8,000 km: the speed-of-light floor in fiber is 80.0 ms round trip; the model uses 80.0 ms.
Page: 6 responses of 2000, 2000, 2000, 2000, 2000, 36894 bytes; 1460-byte segments; window 10 segments new, 64 reused; TLS 1 round trip(s) over TCP.
Loss 0.0 percent per packet; repair after 80 ms (3 packets behind) or 160 ms (tail); 1 pages per row, seed 20261110. Times in ms after the click.

protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50  small p50  small p95  largest p50  largest p95  held back %
HTTP/1.1, 1 connection   new                  2       720       720          9.0        560        560          720          720          0.0
HTTP/1.1, 6 connections  new                  2       320       320          4.0        240        240          320          320          0.0
HTTP/2                   new                  2       400       400          5.0        320        320          400          400          0.0
HTTP/3                   new                  1       320       320          4.0        240        240          320          320          0.0
HTTP/1.1, 1 connection   reused               0       480       480          6.0        400        400          480          480          0.0
HTTP/1.1, 6 connections  reused               0        80        80          1.0         80         80           80           80          0.0
HTTP/2                   reused               0        80        80          1.0         80         80           80           80          0.0
HTTP/3                   reused               0        80        80          1.0         80         80           80           80          0.0

MODEL, not a measurement. Distance 8,000 km: the speed-of-light floor in fiber is 80.0 ms round trip; the model uses 80.0 ms.
Page: 6 responses of 2000, 2000, 2000, 2000, 2000, 36894 bytes; 1460-byte segments; window 10 segments new, 64 reused; TLS 1 round trip(s) over TCP.
Loss 2.0 percent per packet; repair after 80 ms (3 packets behind) or 160 ms (tail); 10,000 pages per row, seed 20261110. Times in ms after the click.

protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50  small p50  small p95  largest p50  largest p95  held back %
HTTP/1.1, 1 connection   new                  2       720       960          9.0        560        720          720          960          0.0
HTTP/1.1, 6 connections  new                  2       320       480          4.0        240        400          320          480          0.0
HTTP/2                   new                  2       400       560          5.0        320        400          400          560         20.1
HTTP/3                   new                  1       320       480          4.0        240        320          320          480          0.0
HTTP/1.1, 1 connection   reused               0       560       720          7.0        400        560          560          720          0.0
HTTP/1.1, 6 connections  reused               0       160       240          2.0         80        240           80          240          0.0
HTTP/2                   reused               0       160       240          2.0         80        160          160          240         20.1
HTTP/3                   reused               0       160       240          2.0         80        160           80          240          0.0

MODEL, not a measurement. Distance 8,000 km: the speed-of-light floor in fiber is 80.0 ms round trip; the model uses 80.0 ms.
Page: 6 responses of 36894, 36894, 36894, 36894, 36894, 36894 bytes; 1460-byte segments; window 10 segments new, 64 reused; TLS 1 round trip(s) over TCP.
Loss 2.0 percent per packet; repair after 80 ms (3 packets behind) or 160 ms (tail); 10,000 pages per row, seed 20261110. Times in ms after the click.

protocol                 connection  setup RTTs  page p50  page p95  RTTs at p50  small p50  small p95  largest p50  largest p95  held back %
HTTP/1.1, 1 connection   new                  2       960      1200         12.0        960       1200          320          480          0.0
HTTP/1.1, 6 connections  new                  2       400       480          5.0        400        480          320          480          0.0
HTTP/2                   new                  2       560       720          7.0        560        720          560          640         95.9
HTTP/3                   new                  1       480       640          6.0        480        640          480          480          0.0
HTTP/1.1, 1 connection   reused               0       720       960          9.0        720        960           80          240          0.0
HTTP/1.1, 6 connections  reused               0       160       320          2.0        160        320           80          240          0.0
HTTP/2                   reused               0       240       320          3.0        240        320          240          320         95.9
HTTP/3                   reused               0       240       320          3.0        240        320          160          240          0.0
```

This is a model, not a measurement. To measure the same thing, run SeatWave behind a real lossy link: on Linux, `sudo tc qdisc add dev <interface> root netem delay 40ms loss 2%` on each side. [not run] macOS has no `tc`, and its packet filter needs administrator rights to change the system's network settings, which this run did not do.

## Ephemeral ports and TIME_WAIT

```
$ sysctl net.inet.ip.portrange.first net.inet.ip.portrange.last net.inet.tcp.msl
net.inet.ip.portrange.first: 49152
net.inet.ip.portrange.last: 65535
net.inet.tcp.msl: 15000
$ netstat -an -p tcp | grep -c "127.0.0.1.8080 .*TIME_WAIT"   # before
5
$ for request in $(seq 1 20); do curl -s -o /dev/null http://127.0.0.1:8080/events; done
$ netstat -an -p tcp | grep "TIME_WAIT" | grep -c "127.0.0.1.8080"   # after 20 new connections
25
$ netstat -an -p tcp | grep "TIME_WAIT" | grep "127.0.0.1.8080" | head -3
tcp4       0      0  127.0.0.1.55187        127.0.0.1.8080         TIME_WAIT  
tcp4       0      0  127.0.0.1.55188        127.0.0.1.8080         TIME_WAIT  
tcp4       0      0  127.0.0.1.55189        127.0.0.1.8080         TIME_WAIT  
```

Each new connection that the client closed left one socket in `TIME_WAIT` on the client's side for twice the maximum segment lifetime (2 × 15,000 ms on macOS), holding one of the 16,384 ports from 49152 to 65535.

## The settings

Every program reads one settings file in `config/`, and every key is required; there are no defaults in code. A missing or invalid key stops the program with one line per problem and exit status 2.

| File | What it sets |
|---|---|
| `timing.properties`, `lab2-distances.properties` | The TEST certificates' folder, the server's TLS versions, the path, runs and warm-up runs, timeouts, the HTTP versions asked for, and each scenario's scheme, one-way delay and handshake rule |
| `serve.properties` | The ports on 127.0.0.1, the distant link's one-way delay, the connection limit and how long to serve |
| `tls-failures.properties` | The server's TLS versions, what the old client offers, and the timeout |
| `round-trips.properties`, `page-model.properties`, `page-model-six-maps.properties` | The page model: distance, round-trip time, response sizes, segment size, windows, TLS round trips, loss, recovery times, trials and seed |
| `targets-seatwave.txt`, `targets-public.txt` | The curl script's targets: a name, a URL and, for the TEST certificates, the CA file |

## Project structure

```
seatwave-network/
  build.gradle.kts            Build: Java 25, -Werror, Spotless, JaCoCo floor, the test certificates, one task per program
  settings.gradle.kts         Project name and the single repository (Maven Central)
  gradle/                     Wrapper files and libs.versions.toml, the one place for versions
  gradle.lockfile             Pinned transitive versions (settings-gradle.lockfile does the same for settings)
  config/                     Every setting of every program, and the curl targets
  scripts/curl-timings.sh     curl -w for every target, with a dry run, a confirmation and an audit line
  src/main/java/io/plainround/seatwave/network/
    config/                   SettingsReader
    server/                   SeatWave's read endpoints on the JDK server, and the serve command
    link/                     The delay link
    tls/                      The TLS contexts from the test certificates, and the TLS failures
    timing/                   The Java timing client
    report/                   The phase and keep-alive tables, the curl line parser, and the invariant
    model/                    The page model of Lab 3
  src/test/java/io/plainround/seatwave/network/
    config/ server/ link/ tls/ timing/ report/ model/   Tests for each package
```

`ARCHITECTURE.md` explains the packages, one request step by step, the decisions and the invariant.

## What is simplified for teaching

- The seat map is built in memory with every seat available; Module 1 read it from H2. This module studies the bytes on the wire.
- The server is the JDK's, which speaks HTTP/1.1 only; production SeatWave would sit behind a proxy or load balancer that speaks HTTP/2 and HTTP/3 (Modules 9 and 10).
- The delay link adds a fixed delay and never loses a packet; it charges the TCP handshake's round trip to the first client bytes, because the operating system completes the real handshake on the loopback address at once.
- The test certificates come from a test CA made for this lab; production certificates come from a public certificate authority and are renewed automatically.
- The page model counts round trips and loss on a timeline; it does not shrink the congestion window after a loss, and it does not model bandwidth.

## Continuous integration

There is no CI workflow in this repository on purpose. `./gradlew check` is the gate: run it before every commit.

## Docker Compose (optional)

`compose.yaml` runs `./gradlew --no-daemon check` inside `eclipse-temurin:25.0.5_8-jdk-noble`, with the repository mounted at `/workspace` and a named volume for `/root/.gradle`. The image tag was checked on Docker Hub on 10 November 2026.

```
docker compose up
```

[not run] Docker was not running on the verification machine, so this path was not tested. It would also use JDK 25.0.5, which is newer than the 25.0.2 used for every result above. Do not time anything inside the container to describe your network: a container adds its own network layer.

## License

MIT. Copyright 2026 Sumit Srivastava. See `LICENSE`.
