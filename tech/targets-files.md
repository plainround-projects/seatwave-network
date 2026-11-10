# The targets files (`config/targets-seatwave.txt` and `config/targets-public.txt`)

## What it is

A **targets file** is a small plain text file that lists what `scripts/curl-timings.sh` may time. Each useful line is one **target**: a short name, a web address (URL), and, for a local HTTPS target, the file of the certificate authority to trust. Lines that start with `#` are comments, and blank lines are ignored.

The format is the repository's own. It is not JSON, YAML or CSV: the fields are separated by spaces, and the script reads them with the shell command `read`. There are two such files, and nothing else in the repository uses the format.

## Why this repository uses it

The script is the only program that may send requests to anything other than the loopback address. A fixed list in a file, checked line by line, keeps the list of targets in one reviewable place. The script then refuses any line that is not on one of two allowed shapes: SeatWave on `127.0.0.1`, or a public site's home page and nothing else. A person cannot point the script at an arbitrary address by adding a line.

## Where it appears

| File | What it lists | Who reads it |
|---|---|---|
| `config/targets-seatwave.txt` | The three SeatWave listeners that `./gradlew -q serve` starts: plain HTTP, HTTPS, and HTTPS behind the delay link. All on `127.0.0.1`. | `scripts/curl-timings.sh` |
| `config/targets-public.txt` | Three public sites' home pages: `example.com`, `www.wikipedia.org` and `www.kernel.org`. | `scripts/curl-timings.sh` |
| `scripts/curl-timings.sh` | Reads a targets file, checks every line, and times each target with `curl -w`. | You, from a terminal |
| `src/main/java/io/plainround/seatwave/network/report/CurlLine.java` | Reads the script's **output**, not the targets file. The target name is the first word of each output line. | `ReportCli` |

The Java `report` task never opens a targets file. The link between the two is the **name**: the script writes it at the start of every output line, and `CurlLine` checks it against the same rule (`[a-z0-9-]{1,40}`).

## How it works underneath

**The columns.** A line has up to three fields, separated by one or more spaces:

1. `name`: a short label, lower-case letters, digits and hyphens, 1 to 40 characters. It starts every output line and becomes a row label in the report.
2. `url`: the address to request.
3. `cacert` (optional): the path of a certificate authority file that curl should trust for this target. Only a local HTTPS target has one. It is the TEST CA that `./gradlew testCertificates` writes to `build/tls`.

A fourth field is refused (`too many fields`).

**How the script reads a line.** The shell loop `while read -r name url cacert extra; do ... done < "$targets_file"` reads one line at a time and splits it at spaces into the four variables. `-r` stops the shell from treating a backslash as an escape. If a line has more than three fields, the rest of the line goes into `extra`, which must stay empty. A line whose first word starts with `#`, and a blank line, are skipped.

**The rule: SeatWave locally, home pages publicly.** The script checks the URL of each line against two regular expressions:

- Local: `^https?://127\.0\.0\.1:[0-9]{4,5}/[A-Za-z0-9/?=]*$`. The host must be exactly `127.0.0.1` (the loopback address, which never leaves the machine), with a port of 4 or 5 digits, and a path made only of letters, digits, slashes, question marks and equals signs. Any SeatWave path is allowed.
- Public: `^https://[a-z0-9.-]+\.[a-z]{2,}/$`. It must be `https`, a host name, and **exactly a slash after it**: the home page and nothing else. A path such as `/page` does not match.

A public target also has these limits: no CA file (a public site uses the system's CAs), at most 3 runs (so one invocation sends a public site at most 3 requests), and never `--reuse`. A local target may use up to 50 runs.

**Dry run by default.** Without `--run`, the script prints the plan and the curl command and sends nothing. With `--run`, it asks for confirmation on the terminal unless `--yes` is given. It refuses to overwrite an existing output file, checks that it wrote exactly one line per request, and appends one line to an audit log next to the output file.

## Walkthrough of this repository's use

### `config/targets-seatwave.txt`

```text
# SeatWave, served by ./gradlew -q serve --args='config/serve.properties' on 127.0.0.1 only.
# name              URL                                              CA file (TEST CA, HTTPS only)
seatwave-http       http://127.0.0.1:8080/events/1/seats?section=A
seatwave-https      https://127.0.0.1:8443/events/1/seats?section=A  build/tls/seatwave-test-ca.pem
seatwave-distant    https://127.0.0.1:8444/events/1/seats?section=A  build/tls/seatwave-test-ca.pem
```

- Line 1 is a comment: it says that SeatWave is served by `./gradlew -q serve --args='config/serve.properties'`, on `127.0.0.1` only.
- Line 2 is a comment that names the three columns: name, URL, CA file.
- `seatwave-http` is plain HTTP on port 8080: no CA file, since there is no TLS.
- `seatwave-https` is HTTPS on port 8443. Its third field, `build/tls/seatwave-test-ca.pem`, is the TEST certificate authority. Curl is told to trust it for this target (`--cacert`), because the certificate was not signed by a public authority.
- `seatwave-distant` is HTTPS on port 8444, the link that adds 40 ms each way. Same CA file.
- All three ask for `/events/1/seats?section=A`, Module 1's 36,894-byte seat map. Each URL matches the local pattern (the `?` and `=` are allowed in the path part).
- The script checks that the CA file exists. If it does not, it prints `run ./gradlew testCertificates` and exits with status 2.

### `config/targets-public.txt`

```text
# Three long-lived, non-commercial public sites: their home pages only, at most 3 runs each.
example-com         https://example.com/
wikipedia-org       https://www.wikipedia.org/
kernel-org          https://www.kernel.org/
```

- The first line is a comment: three long-lived, non-commercial sites, their home pages only, at most 3 runs each.
- `example-com`, `wikipedia-org` and `kernel-org` are the names. Each URL has the shape `https://<host>/` and so matches the public pattern. None has a CA file.
- A single `GET` request for a home page of a long-lived, non-commercial site is the smallest request that shows a real internet path.
- This file is only read when a reader chooses to run the script against it. The notes in this folder never did: every command here stays on the loopback address.

## Try it yourself

All commands run in the repository folder. None sends a request to anything outside this machine: the first is a dry run, and the other two are refused before any request.

**1. A dry run against the SeatWave file.** Nothing is sent, and no output file is made:

```text
scripts/curl-timings.sh config/targets-seatwave.txt 5 build/timings/dry.txt --reuse
```

```text
plan: 5 run(s) x 3 target(s) x 2 request(s) = 30 requests
  seatwave-http  http://127.0.0.1:8080/events/1/seats?section=A
  seatwave-https  https://127.0.0.1:8443/events/1/seats?section=A
  seatwave-distant  https://127.0.0.1:8444/events/1/seats?section=A
dry run: nothing sent. Each request would be:
  curl -sS --connect-timeout 5 --max-time 10 -o /dev/null -w 'NAME dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total} http=%{http_version} code=%{http_code} size=%{size_download} connects=%{num_connects} server=%header{server-timing}\n' URL
add --run to send them
```

The plan is `5 runs x 3 targets x 2 requests = 30`: `--reuse` doubles the requests per run, because each run fetches the URL twice on one connection.

**2. See the home-page rule refuse a path.** Make a one-line file with a public URL that is not a home page, and dry-run it. The script refuses before it would send anything:

```text
printf 'bad-page   https://example.com/page\n' > build/bad-targets.txt
scripts/curl-timings.sh build/bad-targets.txt 1 build/timings/x.txt
```

```text
not an allowed target (SeatWave on 127.0.0.1, or a home page): https://example.com/page
```

The exit status is 2.

**3. See the run limit.** A public home page with 4 runs is refused:

```text
printf 'ok-home https://example.com/\n' > build/bad-targets.txt
scripts/curl-timings.sh build/bad-targets.txt 4 build/timings/x.txt
```

```text
at most 3 runs for ok-home
```

Delete `build/bad-targets.txt` afterwards. (`build/` is ignored by git.) To really time the local SeatWave, see `java-report-walkthrough.md` and `bash-curl-timings.md`.

## Mistakes people make

- **Pointing the script at a deep path of a public site.** Only the home page is allowed. The pattern ends in `/$`.
- **Adding a fourth column.** The line is refused with `too many fields`.
- **Using a CA file for a public site.** It is refused: a public site uses the system's authorities only.
- **Trusting the TEST CA outside this lab.** The file `build/tls/seatwave-test-ca.pem` is only for the local HTTPS targets. Never import it into a browser or an operating system.
- **Forgetting to start the server.** The three local targets only answer while `./gradlew -q serve` runs. With no server, curl fails and the script exits non-zero.
- **Expecting the Java report to read the targets file.** It reads the output lines the script wrote.
- **Using tabs or odd spacing and expecting columns to line up.** Any run of spaces works as a separator; the alignment in the file is only for people.

## Check yourself

1. What are the three columns of a line, and which is optional? (Name, URL, CA file; the CA file is optional and only a local HTTPS target has one.)
2. Which URLs may a public target have? (`https://<host>/`: the home page only.)
3. How many requests does `scripts/curl-timings.sh config/targets-seatwave.txt 5 out.txt --reuse --run` send, and why? (`5 x 3 x 2 = 30`: five runs, three targets, two requests per run because of `--reuse`.)
4. What connects the targets file to the Java report? (The name. The script writes it first on each output line, and `CurlLine` reads it with the same name rule.)
5. Why is a dry run the default? (So a reader sees the plan and the exact command before anything is sent.)

## Further reading

Official documentation:

- curl `-w` (`--write-out`) and its variables: https://curl.se/docs/manpage.html#-w
- curl `--cacert`: https://curl.se/docs/manpage.html#--cacert
- Bash `read` builtin and `while` loops: https://www.gnu.org/software/bash/manual/bash.html#Bourne-Shell-Builtins
- Bash regular-expression matching with `=~`: https://www.gnu.org/software/bash/manual/bash.html#Conditional-Constructs
