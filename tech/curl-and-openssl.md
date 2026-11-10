# `curl` and `openssl s_client`

## What it is

**`curl`** is a command-line program that makes one web request and shows the answer. You give it a URL, it connects, sends a request, and prints the body. It can also print facts about the request instead: how long each step took, which HTTP version answered, how many bytes came back. **`openssl s_client`** is a command of the OpenSSL toolkit that opens a TLS connection to a server and shows what the handshake negotiated and what certificate the server sent. It does not make a web request at all; it stops after the handshake.

A **terminal** is the window where you type commands, and a **command-line option** (or flag) such as `-s` changes what a command does. A **URL** such as `https://127.0.0.1:8443/events` has a scheme (`https`), a host (`127.0.0.1`), a port (`8443`) and a path (`/events`). The address `127.0.0.1` is the **loopback address**: it always means "this computer", so no packet leaves the machine.

## Why this repository uses it

Module 5 asks where the time goes before the first byte of a response arrives. `curl -w` answers that for any server with one command, and `scripts/curl-timings.sh` repeats it for every target (`bash-curl-timings.md`). `openssl s_client` shows what TLS did: the version, the cipher (the encryption method), the certificate and whether it was accepted. The Java report (`ReportCli`, `CurlLine`) turns the lines `curl` prints into the per-phase table of the README.

## Where it appears

| Path | What it does with these tools |
| --- | --- |
| `scripts/curl-timings.sh` | Builds and runs the `curl -w` command for every target. |
| `config/targets-seatwave.txt` | Names the SeatWave loopback targets and the CA file for `--cacert`. |
| `README.md` | Shows the outputs of `curl` and `openssl s_client` that this file quotes. |
| `src/main/java/io/plainround/seatwave/network/report/CurlLine.java` | Parses one printed line. |

The outputs against public sites in this file are quoted from the README (measured by its author on 10 November 2026). I did not contact any public site while writing these notes. The outputs against the loopback servers are from runs I made here: I started `./gradlew serve` in a scratch copy on ports 18080, 18443 and 18444 and used `curl` and `openssl` against those.

## How it works underneath

A web request over HTTPS has these steps, in this order. `curl` reads its own clock at the end of each step and counts the time from the very start of the request.

1. **DNS lookup**: turn a host name such as `example.com` into an IP address. For `127.0.0.1` there is nothing to look up. The clock reading is `time_namelookup`.
2. **TCP connect**: open a connection with the three-way handshake (the client says hello, the server answers, the client confirms). It costs one round trip, the time for a message to travel to the server and back. The reading is `time_connect`.
3. **TLS handshake**: agree on a version and keys, and check the certificate. For TLS 1.3 it costs one more round trip. The reading is `time_appconnect`. For plain HTTP there is no such step, and curl prints 0.
4. **Request and first byte**: send the request, wait for the first byte of the answer. The reading is `time_starttransfer`. This is the **time to first byte** (TTFB). It contains one round trip plus the time the server spent working.
5. **Transfer**: receive the rest of the body. The reading is `time_total`.

Every reading is **cumulative**: it counts from the start of the request, not from the previous step. To get the time of one phase you subtract the reading before it. `CurlLine.parse` does exactly this: the TLS phase is `tls` minus `connect`, the first-byte phase is `ttfb` minus `tls`, and the transfer phase is `total` minus `ttfb`. A reading of 0 means the step did not happen, so that phase ends where the previous one ended. The report keeps the sums exact with `BigDecimal` and checks that the five phases add up to the total (`tests-report-walkthrough.md`).

On a **reused connection** (HTTP keep-alive) the second request goes over the connection that is already open, so DNS, connect and TLS are all 0.

## Walkthrough of this repository's use

### The write-out format

`scripts/curl-timings.sh` prints this format for each request (the function `timing_format`, explained line by line in `bash-curl-timings.md`):

```
NAME dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total} http=%{http_version} code=%{http_code} size=%{size_download} connects=%{num_connects} server=%header{server-timing}\n
```

`-w` (`--write-out`) tells `curl` to print a text after the transfer, with `%{name}` replaced by a measured value. Each variable the script uses:

| Variable | Meaning | In the output |
| --- | --- | --- |
| `time_namelookup` | Seconds until the DNS lookup was done. | `dns=` |
| `time_connect` | Seconds until the TCP connection was open. | `connect=` |
| `time_appconnect` | Seconds until the TLS handshake was done. 0 for plain HTTP. | `tls=` |
| `time_starttransfer` | Seconds until the first byte of the answer arrived. | `ttfb=` |
| `time_total` | Seconds until the whole answer had arrived. | `total=` |
| `http_version` | The HTTP version that answered: `1.1`, `2` or `3`; 0 if the request failed. | `http=` |
| `http_code` | The status code: 200 means success. | `code=` |
| `size_download` | The number of body bytes received. | `size=` |
| `num_connects` | How many new connections this request opened. 0 means it reused one. | `connects=` |
| `header{server-timing}` | The value of the response header `Server-Timing`. The SeatWave server sends `app;dur=<milliseconds>`, the time it spent on the request. Empty if the header is absent. | `server=` |

The other options in the command that the script builds: `-sS` silent but show errors, `--connect-timeout 5` (give up if there is no connection in 5 seconds), `--max-time 10` (give up after 10 seconds in all), `-o /dev/null` (throw the body away) and `--cacert <file>` (trust this CA file for this request; the test CA for the local HTTPS targets).

### A run against the plain HTTP server

On this machine, against my scratch server (first request, then the same URL twice in one command, so the second one reuses the connection):

```
curl -sS -o /dev/null -w "$F" 'http://127.0.0.1:18080/events/1/seats?section=A'
dns=0.000085 connect=0.000273 tls=0.000000 ttfb=0.021875 total=0.021928 http=1.1 code=200 size=36894 connects=1 server=app;dur=2.34
```

(`$F` stood for the format above without the leading `NAME `.) Reading it: the DNS step took 0.085 ms (nothing to look up), the connection was open after 0.273 ms, there was no TLS (`tls=0.000000`), the first byte came after 21.9 ms, and the whole body of 36,894 bytes after 21.9 ms. The server itself worked 2.34 ms (`server=app;dur=2.34`); the rest of the 21.9 ms was the first request warming up the server's code.

Two fetches in one command:

```
dns=0.000079 connect=0.000254 tls=0.000000 ttfb=0.001248 total=0.001299 http=1.1 code=200 size=36894 connects=1 server=app;dur=0.45
dns=0.000008 connect=0.000000 tls=0.000000 ttfb=0.001110 total=0.001141 http=1.1 code=200 size=36894 connects=0 server=app;dur=0.64
```

The second line has `connect=0.000000` and `connects=0`: the connection from the first fetch was reused.

### A run against the local HTTPS server

```
dns=0.000074 connect=0.000244 tls=0.006651 ttfb=0.008100 total=0.010211 http=1.1 code=200 size=36894 connects=1 server=app;dur=0.43
```

The command used `--cacert build/tls/seatwave-test-ca.pem`, so `curl` trusted the test CA (`keytool-test-certificates.md`). The TLS handshake took 6.651 minus 0.244, which is 6.4 ms. Without `--cacert`, `curl` refuses:

```
curl: (60) SSL certificate problem: self signed certificate in certificate chain
```

and exits with status 60. That is correct: the test CA is not one of the CAs that the system trusts.

### A run against the distant link

The `distant` port goes through the delay link that adds 40 ms in each direction (80 ms of round-trip time):

```
dns=0.000059 connect=0.000205 tls=0.170168 ttfb=0.252338 total=0.254018 http=1.1 code=200 size=36894 connects=1 server=app;dur=0.50
```

The TLS handshake, 170.0 ms after the connect, holds two round trips of 80 ms (the link charges the TCP handshake's round trip to the first bytes, because the operating system completes the real handshake on the loopback address at once; see `ARCHITECTURE.md`). The first byte came 82.2 ms after the handshake, one more round trip. The server worked 0.50 ms of that.

### The README's public-site lines

The README quotes the lines the script wrote for three public home pages (`config/targets-public.txt`, 3 requests each). These are the README's, not mine. One of them:

```
example-com dns=0.019980 connect=0.029932 tls=0.128720 ttfb=0.149608 total=0.149721 http=2 code=200 size=577 connects=1 server=
```

`http=2` means the site answered with HTTP/2. `server=` is empty, because the site sent no `Server-Timing` header. The first request of a run includes a DNS lookup that the next two do not repeat (`dns=0.019980`, then `0.002747` and `0.003439` in the README), because the operating system caches the answer. From the three runs of each target the report picks the median run by total time, and prints its phases.

### `openssl s_client`

```
openssl s_client -connect 127.0.0.1:18443 -CAfile build/tls/seatwave-test-ca.pem -verify_ip 127.0.0.1 -brief < /dev/null
```

- `s_client` is the client command. `-connect host:port` is the server to open a TLS connection to.
- `-CAfile <file>` gives the file of CA certificates to trust (here the test CA). Without it OpenSSL trusts its default CAs, which do not include the test CA.
- `-verify_ip 127.0.0.1` checks that the certificate names that IP address.
- `-brief` prints a short summary instead of the full certificate dump.
- `< /dev/null` gives the command an empty input, so after the handshake it has nothing to send and ends by itself. Without it, `s_client` waits for you to type.

Output on this machine (the same as the README shows):

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
```

- `Protocol version: TLSv1.3` is the TLS version agreed.
- `Ciphersuite: TLS_AES_256_GCM_SHA384` is the cipher suite: AES with a 256-bit key in GCM mode for the data, and SHA-384 for the hash used in the key schedule.
- `Peer certificate: CN=SeatWave TEST server (valid)` is the subject of the certificate the server sent.
- `Hash used: SHA256` and `Signature type: ecdsa_secp256r1_sha256` describe how the server signed the handshake: ECDSA on the P-256 curve with SHA-256.
- `Verification: OK` means the chain leads to a trusted CA (the test CA), the dates are right and the name matches.
- `Peer Temp Key: X25519, 253 bits` is the temporary key used for the key exchange (a fresh one for each connection, which is why an eavesdropper who later steals the server's key still cannot read old traffic).
- `Can't use SSL_get_servername` appears because the address is not a host name, so there is no name to send.

The second command asks for the old version only:

```
openssl s_client -connect 127.0.0.1:18443 -tls1_2 -brief < /dev/null
```

The server accepts TLS 1.3 only (`tls.server.protocols=TLSv1.3` in `config/serve.properties`), so it refuses:

```
Connecting to 127.0.0.1
0013070A02000000:error:0A00042E:SSL routines:ssl3_read_bytes:tlsv1 alert protocol version:ssl/record/rec_layer_s3.c:923:SSL alert number 70
```

`alert number 70` is the TLS alert `protocol_version`. The Java program `tlsFailures` shows the same refusal from a Java client (`tests-tls-and-timing-walkthrough.md`).

### The README's public handshakes

For the public sites the README ran this command and then filtered the output:

```
openssl s_client -connect example.com:443 -servername example.com -alpn h2,http/1.1 -verify_return_error < /dev/null > openssl-example.com.txt
```

`-servername example.com` sends the host name (the **SNI**, server name indication) so a server that hosts many sites picks the right certificate. `-alpn h2,http/1.1` offers the application protocols HTTP/2 and HTTP/1.1; the server answers with one. `-verify_return_error` makes the command fail if the certificate is not valid. `> openssl-example.com.txt` saves the output in a file. The README then ran `grep -E 'Connecting to|^ 0 s:|^   v:|Negotiated|Temp Key|Protocol:|Cipher is|ALPN|^Verify return code' ... | awk '!/^   v:/ || !seen++'` to keep only the key lines: `grep -E` keeps lines that match any of the patterns, and `awk` keeps the first validity line only. The README's result for the first site:

```
Connecting to 104.20.23.154
 0 s:CN=example.com
   v:NotBefore: Sep 26 22:49:11 2026 GMT; NotAfter: Dec 25 22:56:35 2026 GMT
Negotiated TLS1.3 group: X25519MLKEM768
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
Protocol: TLSv1.3
ALPN protocol: h2
Verify return code: 0 (ok)
```

`ALPN protocol: h2` is the chosen protocol (HTTP/2). `Verify return code: 0 (ok)` is a successful verification. `X25519MLKEM768` is a hybrid key exchange group (a classic curve combined with a post-quantum one).

## Try it yourself

These need a running server. Start one in a scratch copy with the settings of your choice, or with the README's: `./gradlew -q serve --args='config/serve.properties'` in one terminal (it stops after 300 seconds); the examples below use the README's ports 8080, 8443 and 8444. The commands send requests to your own computer only. (Note: quote a URL that contains `?`, because the shell would treat it as a pattern.)

1. Print `curl`'s version and its features:

```
curl --version | head -2
```

   Expected on this Mac: `curl 8.7.1 ... SecureTransport ... nghttp2/1.69.0` and a `Release-Date` line. The feature list has `HTTP2` and no `HTTP3`.

2. Time one plain request:

```
curl -sS -o /dev/null -w 'connect=%{time_connect} ttfb=%{time_starttransfer} total=%{time_total} code=%{http_code} size=%{size_download}\n' 'http://127.0.0.1:8080/events/1/seats?section=A'
```

   Expected: `code=200 size=36894` and times in fractions of a second. The first request after the server starts is slower than the next ones.

3. See the response headers only:

```
curl -sS -I 'http://127.0.0.1:8080/events/1/seats?section=A'
```

   Expected (from my scratch server): `HTTP/1.1 200 OK`, a `Date:` line, `Content-type: application/json`, `Server-timing: app;dur=0.37` and `Content-length: 36894`. `-I` sends a `HEAD` request, which returns the headers and no body.

4. Watch the refusal without the CA:

```
curl -sS https://127.0.0.1:8443/health; echo "exit=$?"
```

   Expected: `curl: (60) SSL certificate problem: self signed certificate in certificate chain` and `exit=60`. With `--cacert build/tls/seatwave-test-ca.pem` it prints `{"status":"up"}`.

5. Look at the handshake:

```
openssl s_client -connect 127.0.0.1:8443 -CAfile build/tls/seatwave-test-ca.pem -verify_ip 127.0.0.1 -brief < /dev/null
```

   Expected: the block shown above, with `Verification: OK`. Add `-tls1_2` and drop the other options to see `alert number 70`.

## Mistakes people make

- **Reading the readings as durations.** `ttfb=0.026455` is the time since the start, not the time of the first-byte step. Subtract the earlier reading.
- **Forgetting that 0 means "did not happen".** `tls=0.000000` on plain HTTP and `connect=0.000000` on a reused connection are not "instant"; the step was skipped.
- **Measuring a distant server from loopback and believing it.** The loopback numbers describe this machine. The distant link only adds a fixed delay, and its handshake round trip is charged to the first bytes (`ARCHITECTURE.md`).
- **Trusting one request.** The first request pays for DNS, a cold server and a cold connection. The script runs several times and the report uses the median run.
- **Skipping `--cacert` for the test CA** and then switching off verification (`-k`) to make the error go away. That fails open. This repository never does it.
- **Forgetting to quote a URL with `?`.** In `zsh` an unquoted `?` is a pattern and the command fails with `no matches found`.
- **Timing a site you do not own.** The script limits public targets to a home page and 3 requests, and refuses everything else.

## Check yourself

1. *Why is `time_total` always at least `time_starttransfer`?* Both are cumulative readings from the start; the whole answer arrives after its first byte.
2. *How do you get the TLS handshake time from the readings?* `time_appconnect` minus `time_connect`.
3. *What does `num_connects=0` tell you?* The request reused an open connection.
4. *What does `Verification: OK` in `openssl s_client` mean?* The certificate chain leads to a trusted CA, the dates are valid and the name matches.
5. *Why does the `-tls1_2` command fail against the lab server?* The server accepts TLS 1.3 only, so it answers with the `protocol_version` alert.

## Further reading

- curl manual (8.7.1 is the version used here): <https://curl.se/docs/manpage.html>
- curl `--write-out` variables: <https://curl.se/docs/manpage.html#-w>
- Everything curl, the book: <https://everything.curl.dev/>
- `openssl s_client` manual: <https://docs.openssl.org/master/man1/openssl-s_client/>
- TLS 1.3, RFC 8446: <https://www.rfc-editor.org/rfc/rfc8446>
