# The `.properties` format and the settings files under `config/`

## What it is

A **properties file** is a plain text file of settings. Each setting is one line with a **key** (the name) and a **value**, joined by an equals sign: `http.port=8080`. The format comes from Java. The JDK class `java.util.Properties` reads it into a table of text keys and text values.

Everything in a properties file is text. The program decides that `8080` is a number and converts it. If the text is not a number, the conversion fails, and the program must say so. In this repository the class `SettingsReader` does the conversion and the saying (see `java-model-and-config-walkthrough.md`).

## Why this repository uses it

Every command reads its numbers from a file in `config/`, not from the code. There are three reasons. A person can change a number without compiling anything. Every number has a comment that says why it has that value. And there are **no defaults in code**: a key that is missing is reported as a problem, never replaced silently by a hidden value, so the file is the only place a number lives.

## Where it appears

| File | Read by | What it sets |
|---|---|---|
| `config/serve.properties` | `ServeCli` (`serve` task) | The three ports, the link delay, the connection limit and how long SeatWave stays up. |
| `config/timing.properties` | `TimingCli` (`timing` task) | The four scenarios of the Java timing client: this machine and 80 ms away, over HTTP and HTTPS. |
| `config/lab2-distances.properties` | `TimingCli` | Lab 2: the same client over HTTPS at three round-trip times. |
| `config/tls-failures.properties` | `TlsFailuresCli` (`tlsFailures` task) | The TLS versions each side allows and the timeout. |
| `config/round-trips.properties` | `PageModelCli` (`pageModel` task) | The page model with no packet loss. |
| `config/page-model.properties` | `PageModelCli` | Lab 3: the same page with 2 percent packet loss. |
| `config/page-model-six-maps.properties` | `PageModelCli` | Lab 3, the heavier page of six seat maps with 2 percent loss. |
| `src/main/java/io/plainround/seatwave/network/config/SettingsReader.java` | all of the above | Loads the file as UTF-8 and reads typed values. |

The two other files in `config/` are `targets-seatwave.txt` and `targets-public.txt`. They are not properties files; `targets-files.md` explains them.

## How it works underneath

**The format.** The reader goes through the file line by line. I ran a small program on JDK 25 to check each rule.

- A line that starts with `#` or `!` is a **comment** and is ignored. These files use `#`.
- A blank line is ignored.
- A line like `key=value` sets a key. The separator may be `=`, `:` or white space. These files use `=`. I loaded `c: z` and got the key `c` with the value `z`.
- White space around the key and before the value is dropped. White space at the **end** of a value is kept. I loaded `sp = a b ` and got `a b ` with a trailing space. That is why `SettingsReader.text` calls `strip()`.
- A `#` in the middle of a line is **not** a comment: it is part of the value. I loaded `h=x # not` and got the value `x # not`. So a comment must be on a line of its own, as in every file here.
- If a key appears twice, the last one wins. I loaded `d=1` then `d=2` and got `2`.
- A backslash starts an escape (`\t` is a tab, and a backslash at the end of a line continues the value on the next line). These files have no backslashes.
- Dots in a key mean nothing to the reader. `client.connect.timeout.millis` is just that name. The dots are a naming habit that groups related keys. A name like `scenario.distant-tls.scheme` is built by the code from a prefix, the scenario name and a suffix.
- A lookup of a key that is not there returns `null`. `SettingsReader.text` turns that into the problem line `<key> is missing`.

**What the program does with it.** `SettingsReader.load` opens the file with `Files.newBufferedReader(path, StandardCharsets.UTF_8)` and passes the reader to `properties.load(reader)`. A `Reader` gives characters decoded as UTF-8. Passing a byte stream instead would make the JDK decode it as ISO-8859-1, which would corrupt any non-ASCII character. The path is relative to the project folder because each Gradle run task sets `workingDir = rootDir`.

**Units are in the key name.** A key that ends in `.millis` is a number of milliseconds, `.seconds` is seconds, `.bytes` is bytes, `.km` is kilometers and `.percent` is a percentage. A reader never has to guess a unit.

**A value can be a list.** A comma-separated value is read as a list: `response.bytes=2000,2000,2000,2000,2000,36894`. An enum list is written in lower case: `client.versions=http_1_1,http_2`.

**Every key is required.** The file's first comment says so. Delete a line and the command prints `invalid setting: <key> is missing` and exits with status 2. One run reports all problems.

## Walkthrough of this repository's use

### `config/serve.properties`

The whole file:

```properties
# SeatWave for curl and openssl (Module 5, Sections 6 and 8). Every port is on 127.0.0.1 only.
tls.directory=build/tls
tls.server.protocols=TLSv1.3
http.port=8080
https.port=8443
# HTTPS behind a link that adds 40 ms each way: a fan 80 ms away.
distant.port=8444
distant.one.way.delay.millis=40
# The JDK server's jdk.httpserver.maxConnections, and the link's own limit.
max.connections=256
# The servers stop by themselves after this long.
serve.seconds=300
```

Key by key. The code that reads them is `ServeCli.read`.

- `tls.directory=build/tls` is the folder with the TEST certificates, a path relative to the project folder. It is read as text.
- `tls.server.protocols=TLSv1.3` lists the TLS versions the HTTPS server accepts. Each word must be `TLSv1.3` or `TLSv1.2`. With only `TLSv1.3` listed, a client that offers only TLS 1.2 is refused.
- `http.port=8080`, `https.port=8443` and `distant.port=8444` are the three ports. Each must be a whole number from 1,024 to 65,535. They listen on `127.0.0.1` only. The three must differ, because two listeners cannot share a port.
- `distant.one.way.delay.millis=40` is the delay the link adds in each direction, 0 to 10,000 ms. 40 each way is an 80 ms round trip.
- `max.connections=256` is both the JDK server's `jdk.httpserver.maxConnections` and the link's own limit (1 to 10,000). The comment above it says so.
- `serve.seconds=300` is how long the servers stay up (1 to 3,600 seconds). They stop by themselves.

### `config/timing.properties`

The comments and the first group of keys:

```properties
# The Java timing client (Module 5, Section 8). Every key is required; there are no defaults in code.
# The TEST certificates written by ./gradlew testCertificates (any run task does it first).
tls.directory=build/tls
# TLS 1.3 only: one round trip for the handshake (RFC 8446, now RFC 9846).
tls.server.protocols=TLSv1.3
# Module 1's seat map of section A: 1,000 seats, 36,894 bytes.
probe.path=/events/1/seats?section=A
# Medians of 7 runs; the report refuses fewer than 3.
probe.runs=7
# Thrown away first: class loading, the JIT compiler and the first TLS setup are not network time.
probe.warmup.runs=3
# A budget far above the 3 round trips of a distant new connection (240 ms).
client.connect.timeout.millis=5000
client.request.timeout.millis=10000
# HTTP/2 is asked for too; the JDK server answers HTTP/1.1, and the table shows what was used.
client.versions=http_1_1,http_2
link.max.connections=64
scenario.names=simple,local-tls,distant-http,distant-tls
```

- `tls.directory` and `tls.server.protocols`: as above.
- `probe.path=/events/1/seats?section=A` is the path and query every request asks for. It must match the pattern in `TimingSettings` (a slash, lower-case letters, digits and slashes, and at most one `name=VALUE` query). It is Module 1's seat map of section A: 1,000 seats, 36,894 bytes.
- `probe.runs=7` is the number of measured runs per row: 3 to 1,000. The report takes the median of these. Seven is more than the minimum of 3, so a median is less shaky.
- `probe.warmup.runs=3` is the number of runs thrown away first: class loading, the JIT compiler and the first TLS setup are not network time. 0 to 1,000.
- `client.connect.timeout.millis=5000` and `client.request.timeout.millis=10000` are the two timeouts of every call, 1 to 60,000 ms each. The comment says why 5 seconds is generous: a distant new connection needs three round trips, 240 ms.
- `client.versions=http_1_1,http_2` is the list of HTTP versions the client asks for, in order. The names are the JDK's `HttpClient.Version` constants in lower case. JDK 25 has only these two.
- `link.max.connections=64` is the most connections each link allows at once (1 to 10,000).
- `scenario.names=simple,local-tls,distant-http,distant-tls` lists the scenarios, in order. Each name must match `[a-z][a-z0-9-]{0,19}`. The code reads three keys per scenario name.

The scenarios:

```properties
# SIMPLE: plain HTTP/1.1 on this machine.
scenario.simple.scheme=http
scenario.simple.one.way.delay.millis=0
scenario.simple.handshake.delay=none
scenario.local-tls.scheme=https
scenario.local-tls.one.way.delay.millis=0
scenario.local-tls.handshake.delay=none
# A fan 80 ms away (Module 5, Section 7): 40 ms each way, and the TCP handshake's round trip added.
scenario.distant-http.scheme=http
scenario.distant-http.one.way.delay.millis=40
scenario.distant-http.handshake.delay=one_round_trip
scenario.distant-tls.scheme=https
scenario.distant-tls.one.way.delay.millis=40
scenario.distant-tls.handshake.delay=one_round_trip
```

- `scenario.<name>.scheme` is `http` or `https`.
- `scenario.<name>.one.way.delay.millis` is the link's delay in each direction. 0 means no delay on this machine; 40 is a fan 80 ms away.
- `scenario.<name>.handshake.delay` is `none` or `one_round_trip`: whether the link charges the TCP handshake's round trip to the first bytes. This machine needs none. A distant fan pays one.
- Four scenarios: `simple` (plain HTTP here), `local-tls` (HTTPS here), `distant-http` (plain HTTP 80 ms away) and `distant-tls` (HTTPS 80 ms away).
- Each scenario is run once per requested version: 4 scenarios times 2 versions gives the 8 rows of the table.

### `config/lab2-distances.properties`

```properties
# Lab 2 (Module 5, Section 11): new against reused HTTPS connections at three round-trip times.
# Every key is required; the meaning of each is in timing.properties.
tls.directory=build/tls
tls.server.protocols=TLSv1.3
probe.path=/events/1/seats?section=A
probe.runs=7
probe.warmup.runs=3
client.connect.timeout.millis=5000
client.request.timeout.millis=10000
client.versions=http_1_1
link.max.connections=64
scenario.names=rtt-20ms,rtt-40ms,rtt-80ms
# A round-trip time of 20 ms: 10 ms each way, and the TCP handshake's round trip added.
scenario.rtt-20ms.scheme=https
scenario.rtt-20ms.one.way.delay.millis=10
scenario.rtt-20ms.handshake.delay=one_round_trip
scenario.rtt-40ms.scheme=https
scenario.rtt-40ms.one.way.delay.millis=20
scenario.rtt-40ms.handshake.delay=one_round_trip
scenario.rtt-80ms.scheme=https
scenario.rtt-80ms.one.way.delay.millis=40
scenario.rtt-80ms.handshake.delay=one_round_trip
```

- The first comment says every key is required and the meaning of each is in `timing.properties`.
- Only the scenarios differ. `scenario.names=rtt-20ms,rtt-40ms,rtt-80ms` names three scenarios, all over HTTPS, with one-way delays of 10, 20 and 40 ms, so round-trip times of 20, 40 and 80 ms. Each charges the TCP handshake's round trip (`one_round_trip`).
- `client.versions=http_1_1` asks for HTTP/1.1 only: HTTP/2 made no difference to the JDK server in the main table.
- Three scenarios times one version gives 3 rows.

### `config/tls-failures.properties`

```properties
# Failure mode 4 (Module 5, Section 9). Every key is required.
tls.directory=build/tls
tls.server.protocols=TLSv1.3
# What an old client offers: TLS 1.2 only, so a TLS 1.3-only server must refuse it.
tls.old.client.protocols=TLSv1.2
client.timeout.millis=5000
```

- `tls.directory=build/tls`: where the TEST certificates are.
- `tls.server.protocols=TLSv1.3`: the server accepts TLS 1.3 only.
- `tls.old.client.protocols=TLSv1.2` is what an old client offers: TLS 1.2 only. A TLS 1.3-only server must refuse it.
- `client.timeout.millis=5000` is the connect timeout and the request timeout of every client call: 1 to 60,000 ms.
- The reader is `TlsSettings.from`.

### `config/round-trips.properties`

The whole file. This is the base of the three page-model files:

```properties
# Round trips per protocol with no loss (Module 5, Sections 6 and 7): every value is an assumption.
# About 8,000 km: the straight-line distance whose fiber floor is the 80 ms used below.
distance.km=8000
rtt.millis=80
# Five small responses (style sheet, script, font, venue image, event list) and Module 1's 36,894-byte seat map.
response.bytes=2000,2000,2000,2000,2000,36894
# A typical TCP payload on an Ethernet path (1,500-byte packets less 40 bytes of IP and TCP headers).
segment.bytes=1460
# RFC 6928's initial window of 10 segments; a reused connection's window has already grown.
initial.window.segments=10
warm.window.segments=64
# TLS 1.3: one round trip.
tls.handshake.round.trips=1
loss.percent=0
# Fast retransmit after three duplicate ACKs: about one round trip late.
fast.recovery.millis=80
# A tail loss waits for a probe timeout, about two round trips (RFC 8985).
tail.recovery.millis=160
trials=1
random.seed=20261110
protocols=http1_one_connection,http1_six_connections,http2,http3
```

Key by key. The reader is `ModelSettings.from`.

- `distance.km=8000` is the straight-line distance to the fan (0 to 40,000). It is used for the speed-of-light **floor**: light in fiber covers about 200 km per millisecond, so 2 x 8,000 km is 80 ms.
- `rtt.millis=80` is the round-trip time the model uses (a decimal number, 1 to 2,000). It matches the floor.
- `response.bytes=2000,2000,2000,2000,2000,36894` lists the size of each response of the page, in request order, each from 1 to 100,000,000 bytes: five small responses (style sheet, script, font, venue image, event list) and Module 1's 36,894-byte seat map.
- `segment.bytes=1460` is the payload of one segment: 1,500-byte packets less 40 bytes of IP and TCP headers (1 to 9,000).
- `initial.window.segments=10` is a new connection's first window, from RFC 6928. `warm.window.segments=64` is a reused connection's window after earlier traffic. Both 1 to 100,000.
- `tls.handshake.round.trips=1` is TLS 1.3's cost over TCP: one round trip. TLS 1.2 would be 2. Allowed: 1 or 2.
- `loss.percent=0` is the chance a packet is lost, 0 to 50. Zero means no loss.
- `fast.recovery.millis=80` is the extra delay of a loss revealed by three later packets (fast retransmit): about one round trip. 0 to 10,000.
- `tail.recovery.millis=160` is the extra delay of a loss with fewer than three packets behind it: a probe timeout of about two round trips (RFC 8985). 0 to 10,000.
- `trials=1` is how many pages to simulate per row (1 to 1,000,000). With no loss every page is the same, so one is enough.
- `random.seed=20261110` is the seed of the random losses (0 to 2,147,483,647). A fixed seed means the same table on every run. The number is the date it was chosen.
- `protocols=http1_one_connection,http1_six_connections,http2,http3` lists the protocols to compare, in order. The names are the `Protocol` enum in lower case.

### `config/page-model.properties`

```properties
# Lab 3's model (Module 5, Section 11): a page of six requests, 80 ms away, 2 percent packet loss.
# Every value is an assumption the report prints. Every key is required.
# About 8,000 km: the straight-line distance whose fiber floor is the 80 ms used below.
distance.km=8000
rtt.millis=80
# Five small responses (style sheet, script, font, venue image, event list) and Module 1's 36,894-byte seat map.
response.bytes=2000,2000,2000,2000,2000,36894
# A typical TCP payload on an Ethernet path (1,500-byte packets less 40 bytes of IP and TCP headers).
segment.bytes=1460
# RFC 6928's initial window of 10 segments; a reused connection's window has already grown.
initial.window.segments=10
warm.window.segments=64
# TLS 1.3: one round trip.
tls.handshake.round.trips=1
loss.percent=2
# Fast retransmit after three duplicate ACKs: about one round trip late.
fast.recovery.millis=80
# A tail loss waits for a probe timeout, about two round trips (RFC 8985).
tail.recovery.millis=160
trials=10000
random.seed=20261110
protocols=http1_one_connection,http1_six_connections,http2,http3
```

The same file as `round-trips.properties` with two changes: `loss.percent=2` (two percent of packets are lost) and `trials=10000` (10,000 pages per row, so the p50 and p95 are steady). The first lines of comment are different, and say so.

### `config/page-model-six-maps.properties`

```properties
# Lab 3, the heavier page: six seat-map sections, 80 ms away, 2 percent packet loss.
# Every value is an assumption the report prints. Every key is required.
# About 8,000 km: the straight-line distance whose fiber floor is the 80 ms used below.
distance.km=8000
rtt.millis=80
# Six sections of the seat map, A to F: Module 1's 36,894 bytes each.
response.bytes=36894,36894,36894,36894,36894,36894
# A typical TCP payload on an Ethernet path (1,500-byte packets less 40 bytes of IP and TCP headers).
segment.bytes=1460
# RFC 6928's initial window of 10 segments; a reused connection's window has already grown.
initial.window.segments=10
warm.window.segments=64
# TLS 1.3: one round trip.
tls.handshake.round.trips=1
loss.percent=2
# Fast retransmit after three duplicate ACKs: about one round trip late.
fast.recovery.millis=80
# A tail loss waits for a probe timeout, about two round trips (RFC 8985).
tail.recovery.millis=160
trials=10000
random.seed=20261110
protocols=http1_one_connection,http1_six_connections,http2,http3
```

The same as `page-model.properties`, except `response.bytes` is six seat maps of 36,894 bytes each (sections A to F), a heavier page. Its first comment says so.

## Try it yourself

All commands run in the repository folder.

**1. Run a file that is correct.**

```text
./gradlew -q pageModel --args='config/round-trips.properties'
```

It prints the model table (see `java-model-and-config-walkthrough.md`).

**2. Break a file in four ways and see every problem at once.**

```text
sed -e 's/^rtt.millis=80/rtt.millis=abc/' -e '/^trials=/d' -e 's/^loss.percent=2/loss.percent=75/' -e 's/^protocols=.*/protocols=http1_one_connection,quic/' config/page-model.properties > build/bad-model.properties
./gradlew -q pageModel --args=build/bad-model.properties
```

```text
invalid setting: rtt.millis must be a decimal number, but was abc
invalid setting: loss.percent must be from 0.0 to 50.0, but was 75
invalid setting: trials is missing
invalid setting: protocols has an unknown value: quic
```

(Delete `build/bad-model.properties` afterwards. `build/` is ignored by git.)

**3. Break the timing file.** Remove the `scenario.names` line and see a missing key reported:

```text
grep -v '^scenario.names' config/timing.properties > build/bad-timing.properties
./gradlew -q timing --args=build/bad-timing.properties
```

```text
invalid setting: scenario.names is missing
invalid setting: scenario.names is missing
```

(The line appears twice because the code reads that key twice.)

**4. Check the properties rules yourself.** Save this as `build/Props.java` (any folder works) and run `java build/Props.java`:

```text
import java.io.StringReader;
import java.util.Properties;
import java.util.TreeMap;

public class Props {
  public static void main(String[] args) throws Exception {
    Properties p = new Properties();
    p.load(new StringReader("# c\nk=v\nh=x # not\nd=1\nd=2\nsp = a b \nc: z\n"));
    System.out.println(new TreeMap<>(p) + " sp=[" + p.getProperty("sp") + "]");
  }
}
```

```text
{c=z, d=2, h=x # not, k=v, sp=a b } sp=[a b ]
```

The comment line gave no key, `h` kept the `# not`, `d` is 2, and `sp` kept its trailing space.

## Mistakes people make

- **Putting a comment at the end of a value line.** `rtt.millis=80 # round trip` makes the value `80 # round trip`, which is not a number. This repository puts comments on their own lines.
- **Trailing spaces.** They stay in the value. `SettingsReader.text` strips them.
- **A typo in a key.** The key is then missing, and the reader reports `... is missing`. There is no silent default.
- **A backslash in a Windows path.** It is an escape. Use forward slashes (`build/tls`).
- **Hiding a default in code.** If the code had a fallback for `probe.runs`, nobody could tell from the file what a run used. Here every key is required.
- **Units guessed.** The unit is in the key name (`.millis`, `.bytes`).
- **A non-UTF-8 file.** `load` decodes as UTF-8, so save the file as UTF-8.

## Check yourself

1. How does the reader treat `rtt.millis=80 # round trip`? (The whole text `80 # round trip` is the value, so parsing it as a decimal number fails and the reader reports it.)
2. If the same key appears twice, which value is used? (The last one.)
3. What happens when you delete a key from `timing.properties`? (The reader records `<key> is missing`, the command prints every problem it found, and exits with status 2.)
4. Why is `tls.server.protocols=TLSv1.3` a safe choice, and which scenario proves a refusal? (A server that accepts only TLS 1.3 refuses older clients in the handshake. The `old_version` scenario of the `tlsFailures` task shows it.)
5. Why does `random.seed` exist? (So the simulated losses are the same on every run and every machine, and the table is reproducible.)

## Further reading

Official documentation, for the JDK this repository pins (Java 25):

- `java.util.Properties` and the exact file format (see the `load(Reader)` method): https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/Properties.html
- `java.nio.file.Files.newBufferedReader`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html
- `java.nio.charset.StandardCharsets`: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/charset/StandardCharsets.html
- RFC 6928 (initial window): https://www.rfc-editor.org/rfc/rfc6928 and RFC 8985 (probe timeout): https://www.rfc-editor.org/rfc/rfc8985
