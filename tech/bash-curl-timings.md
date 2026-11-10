# Bash, and `scripts/curl-timings.sh` line by line

## What it is

A **shell** is a program that reads the commands you type in a terminal and runs them. **Bash** is a very common shell. A **shell script** is a text file of commands that Bash runs from top to bottom, so you can repeat the same steps without typing them. A script is a program like any other: it has variables, decisions, loops and functions, and it ends with a number called the **exit status**. By convention 0 means success and any other number means failure. The shell remembers the last exit status of a command in the special variable `$?`.

Bash is not the same as the plain POSIX shell that `gradlew` uses (`posix-shell.md`). Bash adds `[[ ... ]]` tests, arrays and `=~` pattern matching, which this script uses.

## Why this repository uses it

The lesson asks: where does the time go before the first byte of a response arrives? `curl` can print the clock reading of each step of a request. The script `scripts/curl-timings.sh` runs `curl` for every target in a list, several times, and writes one line per request in the format the Java report reads (`CurlLine.java`). It is also the part of the project that can touch the public internet, so it carries guardrails: it is a dry run unless you say otherwise, it asks before it sends, it accepts only SeatWave on `127.0.0.1` or a public home page, it limits the number of requests to a public site, and it writes an audit line. `curl-and-openssl.md` explains the `curl` side.

## Where it appears

| Path | What it does with Bash |
| --- | --- |
| `scripts/curl-timings.sh` | The script (129 lines). |
| `config/targets-seatwave.txt`, `config/targets-public.txt` | The target lists the script reads (`targets-files.md` explains the files). |
| `build/timings/*.txt` and `build/timings/audit.log` | What the script writes. |
| `src/main/java/io/plainround/seatwave/network/report/CurlLine.java` | Reads the lines the script writes. |

## How it works underneath

1. You type `scripts/curl-timings.sh config/targets-seatwave.txt 5 build/timings/seatwave.txt`. The shell asks the operating system to run the file. The first line of the file tells the system which program should read it.
2. Bash reads the file one command at a time and runs each. A command is a program name followed by **arguments**, separated by spaces.
3. The arguments you gave the script appear inside it as `$1` for the first, `$2` for the second and `$3` for the third. `$#` is how many there are, and `"$@"` is all of them.
4. A **variable** is a named piece of text: `runs="$2"` stores the second argument. You read it back with a dollar sign.
5. The script ends with `exit` and a status, or when it runs out of lines.

**Quoting.** In Bash, spaces split words. `"$runs"` in double quotes keeps the value as one word and still expands the variable. Single quotes, as in `'^[a-z0-9-]{1,40}$'`, keep every character exactly, with no expansion. A `\` before a character takes it literally.

## Walkthrough of this repository's use

### The header comments

```bash
#!/usr/bin/env bash
# Times each target of a targets file with curl -w and writes one line per request, in the format
# the report task reads (src/main/java/io/plainround/seatwave/network/report/CurlLine.java).
#
# Usage: scripts/curl-timings.sh <targets file> <runs> <output file> [--run] [--yes] [--reuse]
#   default   a dry run: print the plan and the curl command, send nothing
#   --run     send the requests; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#   --reuse   each run fetches the URL twice on one connection (loopback targets only)
#
# Guardrails: a target is either SeatWave on 127.0.0.1 (any SeatWave path) or a public site's home
# page "https://<host>/" and nothing else; a public site gets at most 3 runs and never --reuse, so
# one invocation sends it at most 3 GET requests; the output file must not exist yet; every run
# appends one audit line; any failure exits non-zero.
```

- `#!/usr/bin/env bash` is the **shebang** line. When the file is run directly, the system reads it to find the program: `env` looks for `bash` on the `PATH` (the list of folders where programs live). On this Mac that finds Bash 5.3.9 from Homebrew; the macOS system `/bin/bash` is version 3.2.57. I ran the script with both in dry-run mode and both printed the same plan, so it works with the old one too.
- Every line that starts with `#` is a comment. The Bash interpreter skips it. These comments are the script's manual: they give the usage, what the three options do, and the guardrails.
- `Usage: scripts/curl-timings.sh <targets file> <runs> <output file> [--run] [--yes] [--reuse]` has three required arguments and three optional **flags**. The first says which targets, the second how many runs for each, the third where to write.
- The default is "a dry run: print the plan and the curl command, send nothing". `--run` sends the requests and asks for confirmation first. `--yes` skips that question. `--reuse` fetches the URL twice on one connection, and is allowed for loopback targets only.
- The guardrails sentence lists the rules this file enforces: a target is either SeatWave on 127.0.0.1 or a public site's home page `https://<host>/` and nothing else; a public site gets at most 3 runs and never `--reuse`, so one invocation sends it at most 3 GET requests; the output file must not exist yet; every run appends one audit line; any failure exits non-zero.

### `set -euo pipefail`

```bash
set -euo pipefail
```

One line, three safety switches.

- `-e` (errexit): stop the script at the first command that fails (exit status not 0), except in the tests of `if`, `&&` and `||`. I checked: `bash -c 'set -e; echo one; false; echo two'` printed `one` and stopped with status 1; `two` never appeared.
- `-u` (nounset): using a variable that was never set is an error, not silently empty text. `bash -c 'set -u; echo "$nothing"; echo after'` printed `bash: line 1: nothing: unbound variable` and did not print `after`.
- `-o pipefail`: a **pipeline** is commands joined by `|`, where the output of one is the input of the next. Normally the status of a pipeline is the status of its last command. With `pipefail` it is the first failure. `false | cat` gave status 0 without the switch and 1 with it.

### The constants

```bash
readonly MAX_PUBLIC_RUNS=3
readonly MAX_LOCAL_RUNS=50
readonly CONNECT_TIMEOUT_SECONDS=5
readonly MAX_TIME_SECONDS=10
readonly NAME_PATTERN='^[a-z0-9-]{1,40}$'
readonly LOCAL_PATTERN='^https?://127\.0\.0\.1:[0-9]{4,5}/[A-Za-z0-9/?=]*$'
readonly PUBLIC_PATTERN='^https://[a-z0-9.-]+\.[a-z]{2,}/$'
```

- `readonly NAME=value` makes a variable that can never be changed, even by a later line of the same script. Names in capitals are the convention for constants.
- `MAX_PUBLIC_RUNS=3` is the most runs any public site gets. `MAX_LOCAL_RUNS=50` is the most for any target.
- `CONNECT_TIMEOUT_SECONDS=5` is how long `curl` waits to connect; `MAX_TIME_SECONDS=10` is the longest a whole request may take.
- `NAME_PATTERN='^[a-z0-9-]{1,40}$'` is a **regular expression**, a pattern for text. `^` is the start of the text, `$` the end, `[a-z0-9-]` is one lower-case letter, digit or dash, and `{1,40}` means "1 to 40 of these". So a name is 1 to 40 characters of letters, digits and dashes: `seatwave-http` fits, `Seat_Wave` does not.
- `LOCAL_PATTERN='^https?://127\.0\.0\.1:[0-9]{4,5}/[A-Za-z0-9/?=]*$'` describes a loopback URL. `https?` is `http` with an optional `s`. `127\.0\.0\.1` has a backslash before each dot, because an unescaped dot means "any character". `:[0-9]{4,5}` is a port of 4 or 5 digits. After the slash come any number of letters, digits, `/`, `?` and `=`. A space, a `.` or `@` in the path is not allowed. I tested it: `http://127.0.0.1:8080/events/1/seats?section=A` matches; `http://127.0.0.1:80/` (port too short), `http://localhost:8080/` and `https://127.0.0.1:8443/x y` do not.
- `PUBLIC_PATTERN='^https://[a-z0-9.-]+\.[a-z]{2,}/$'` allows only `https`, a host name (letters, digits, dots, dashes, ending in a dot and at least two letters, such as `.org`), and exactly one `/` at the end: a home page, no path and no query.

### The usage function and the argument check

```bash
usage() {
  echo "usage: $0 <targets file> <runs> <output file> [--run] [--yes] [--reuse]" >&2
  exit 2
}

[[ $# -ge 3 ]] || usage
targets_file="$1"
runs="$2"
out_file="$3"
shift 3
mode="dry-run"
confirmed="no"
reuse="no"
```

- `usage() { ... }` defines a **function**: a named group of commands that runs when you write its name. Inside, `$0` is the name the script was started with.
- `echo "usage: ..." >&2` prints the text. `>&2` sends it to **standard error**, the second output channel meant for error messages, so a script that captures the normal output does not capture the complaint. `exit 2` ends the script with status 2, a common code for "wrong usage".
- `[[ $# -ge 3 ]] || usage` is a test: `[[ ... ]]` is Bash's test command, `$#` is the number of arguments, and `-ge` means "greater than or equal". The `||` means "if the left side failed, run the right side". With fewer than three arguments the script prints the usage line and exits 2. I ran it with no arguments and got `usage: scripts/curl-timings.sh <targets file> <runs> <output file> [--run] [--yes] [--reuse]` and status 2.
- `targets_file="$1"`, `runs="$2"` and `out_file="$3"` give the three arguments readable names.
- `shift 3` removes the first three arguments, so `$@` now holds only the options.
- `mode="dry-run"`, `confirmed="no"` and `reuse="no"` are the defaults. The script is safe unless the reader changes them.

### The options loop and `case`

```bash
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    --reuse) reuse="yes" ;;
    *) usage ;;
  esac
done
```

- `for option in "$@"; do ... done` repeats the block once for each remaining argument, with `option` holding the current one. `"$@"` in quotes keeps each argument as one word.
- `case "$option" in ... esac` compares the value with patterns, one by one. `--run) mode="run" ;;` means: if it is exactly `--run`, set `mode` to `run`; `;;` ends that branch. `--yes)` sets `confirmed`, `--reuse)` sets `reuse`.
- `*) usage ;;` is the catch-all: `*` matches anything, so any other word, such as `--fast`, prints the usage and exits 2. I ran it with `--fast` and got the usage line.

### The checks before anything runs

```bash
# Check before: the tool, the inputs and the output.
command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
[[ -f "$targets_file" ]] || { echo "no targets file: $targets_file" >&2; exit 2; }
[[ "$runs" =~ ^[0-9]+$ ]] && (( runs >= 1 && runs <= MAX_LOCAL_RUNS )) \
  || { echo "runs must be 1 to $MAX_LOCAL_RUNS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }
```

- The comment says what the block does: "Check before: the tool, the inputs and the output."
- `command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }`: `command -v curl` prints where `curl` is, or fails if it is not installed. `>/dev/null` throws the output away (`/dev/null` is a file that swallows everything). `|| { ...; }` runs the braces group only if the test failed: message, then exit 1.
- `[[ -f "$targets_file" ]] || {...}`: `-f` is true if the path is a regular file. If not: `no targets file: ...` and exit 2. I ran it with `config/nope.txt` and got `no targets file: config/nope.txt`.
- The runs check spans two lines. `[[ "$runs" =~ ^[0-9]+$ ]]` is a pattern test: `=~` means "matches this regular expression", here "only digits". `&&` means "and, if that was true". `(( runs >= 1 && runs <= MAX_LOCAL_RUNS ))` is **arithmetic**: double round brackets calculate and compare numbers, without needing `$` in front of variable names. The `\` at the end of a line means "the command continues on the next line". `|| { echo "runs must be 1 to $MAX_LOCAL_RUNS" >&2; exit 2; }` runs if either test failed. The number test comes first because comparing the text `abc` as a number would be an error. With `0` runs the script printed `runs must be 1 to 50`. (ShellCheck, a script checker, reports an informational note, SC2015, that `A && B || C` is not an if-then-else. Here that is fine: both parts only decide whether to stop.)
- `[[ ! -e "$out_file" ]] || {...}`: `-e` is true if the path exists, and `!` negates it. If the output file exists, the script stops with `refusing to overwrite ...` so an old result is never lost. I ran it with `README.md` as the output name and got `refusing to overwrite README.md`.

### Reading the targets file

```bash
names=()
urls=()
cacerts=()
while read -r name url cacert extra; do
  [[ -z "${name:-}" || "$name" == \#* ]] && continue
  [[ -z "${extra:-}" ]] || { echo "too many fields for $name" >&2; exit 2; }
  [[ "$name" =~ $NAME_PATTERN ]] || { echo "invalid name: $name" >&2; exit 2; }
```

- `names=()`, `urls=()` and `cacerts=()` make three empty **arrays**: numbered lists of values. They are parallel: entry 0 of each belongs to the first target.
- `while read -r name url cacert extra; do ... done < "$targets_file"` is a loop that runs once per line of the file. `read` takes one line and splits it at spaces into the named variables: the first word into `name`, the second into `url`, the third into `cacert`, and everything left into `extra`. `-r` keeps backslashes as they are. `< "$targets_file"` feeds the file to the loop as input. A line with only two words leaves `cacert` empty.
- `[[ -z "${name:-}" || "$name" == \#* ]] && continue` skips blank lines and comment lines. `-z` is true for an empty text. `${name:-}` means "the value of `name`, or empty if it is unset"; it is needed because `set -u` would otherwise stop the script on an unset variable. `"$name" == \#*` is a pattern comparison: the text starts with `#`. `continue` goes to the next line. So the comment lines of `config/targets-seatwave.txt` are skipped.
- `[[ -z "${extra:-}" ]] || {...}` refuses a line with a fourth word: `too many fields for ...` and exit 2.
- `[[ "$name" =~ $NAME_PATTERN ]] || {...}` checks the name against the pattern. The variable is written without quotes on the right of `=~` on purpose: unquoted, Bash treats it as a pattern; quoted, it would be literal text. A bad name stops with `invalid name: Bad_Name`.

### The two kinds of allowed target

```bash
  if [[ "$url" =~ $LOCAL_PATTERN ]]; then
    if [[ -n "${cacert:-}" && ! -f "$cacert" ]]; then
      echo "no CA file $cacert for $name: run ./gradlew testCertificates" >&2
      exit 2
    fi
  elif [[ "$url" =~ $PUBLIC_PATTERN ]]; then
    [[ -z "${cacert:-}" ]] || { echo "a public site uses the system's CAs only: $name" >&2; exit 2; }
    (( runs <= MAX_PUBLIC_RUNS )) || { echo "at most $MAX_PUBLIC_RUNS runs for $name" >&2; exit 2; }
    [[ "$reuse" == "no" ]] || { echo "--reuse is for loopback targets only: $name" >&2; exit 2; }
  else
    echo "not an allowed target (SeatWave on 127.0.0.1, or a home page): $url" >&2
    exit 2
  fi
```

- `if [[ "$url" =~ $LOCAL_PATTERN ]]; then ... elif [[ "$url" =~ $PUBLIC_PATTERN ]]; then ... else ... fi` is a decision with three branches.
- A loopback target may have a CA file. `[[ -n "${cacert:-}" && ! -f "$cacert" ]]` is true if there is a CA name (`-n` is "not empty") and the file does not exist. Then the script says `no CA file ... for ...: run ./gradlew testCertificates` and exits 2, because the CA file is made by that task (`keytool-test-certificates.md`).
- A public target must have no CA file: `a public site uses the system's CAs only`. It must not exceed 3 runs: `(( runs <= MAX_PUBLIC_RUNS )) || {...}` prints `at most 3 runs for ...`. And `--reuse` is refused: `[[ "$reuse" == "no" ]] || {...}`.
- Anything else: `not an allowed target (SeatWave on 127.0.0.1, or a home page): ...` and exit 2.

I tried each rejection with small targets files that I wrote in a scratch folder, and every one stopped with status 2 before any request: a URL on an unlisted host (`not an allowed target ...`), a name with an underscore, a four-word line, a file with only comments (`the targets file lists no target`), a missing CA file, a home page URL with 4 runs (`at most 3 runs for ...`) and a home page with `--reuse` (`--reuse is for loopback targets only: ...`).

### Recording the targets

```bash
  names+=("$name")
  urls+=("$url")
  cacerts+=("${cacert:-}")
done < "$targets_file"
(( ${#names[@]} > 0 )) || { echo "the targets file lists no target" >&2; exit 2; }

```

- `names+=("$name")` appends one item to the array. The same for `urls` and for `cacerts`; `"${cacert:-}"` appends an empty text when there is no CA file, so the three arrays stay the same length.
- `done < "$targets_file"` closes the loop and names its input.
- `(( ${#names[@]} > 0 )) || {...}`: `${#names[@]}` is the number of items in the array. If there is none, `the targets file lists no target`.

### The plan

```bash
per_run=1
[[ "$reuse" == "yes" ]] && per_run=2
expected_lines=$(( ${#names[@]} * runs * per_run ))
echo "plan: $runs run(s) x ${#names[@]} target(s) x $per_run request(s) = $expected_lines requests"
for index in "${!names[@]}"; do
  echo "  ${names[$index]}  ${urls[$index]}"
done

```

- `per_run=1` and `[[ "$reuse" == "yes" ]] && per_run=2`: each run makes one request, or two with `--reuse`. The `&&` runs the assignment only if the test is true.
- `expected_lines=$(( ${#names[@]} * runs * per_run ))`: `$(( ... ))` calculates and gives the result as text: targets times runs times requests per run. The script uses this number twice: in the plan, and at the end as the number of lines the output file must hold.
- `echo "plan: ..."` prints the first output line. With the loopback file, 5 runs and no `--reuse`, I got `plan: 5 run(s) x 3 target(s) x 1 request(s) = 15 requests`.
- `for index in "${!names[@]}"; do ... done`: `"${!names[@]}"` is the list of **indexes** of the array (0, 1, 2). Each round prints two spaces, the name, two more spaces and the URL: `${names[$index]}  ${urls[$index]}`. The curly brackets are needed so Bash reads the array name and the index correctly.

### The curl format function

```bash
timing_format() {
  # One line per request; the Server-Timing header comes last because it may hold spaces.
  printf '%s dns=%%{time_namelookup} connect=%%{time_connect} tls=%%{time_appconnect}' "$1"
  printf ' ttfb=%%{time_starttransfer} total=%%{time_total} http=%%{http_version}'
  printf ' code=%%{http_code} size=%%{size_download} connects=%%{num_connects}'
  printf ' server=%%header{server-timing}\\n'
}
```

- `timing_format() { ... }` is a function that prints the format string for `curl -w`. The comment says why the header comes last: "One line per request; the Server-Timing header comes last because it may hold spaces."
- Inside, `$1` is the first argument of the function, the target name. `printf` prints text from a format. `'%s dns=%%{time_namelookup} connect=%%{time_connect} tls=%%{time_appconnect}'` has one `%s`, replaced by `$1`, and `%%` that `printf` turns into a single `%`. So the output is `NAME dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect}`. The `%{...}` words are the variables of `curl -w` that curl itself fills in later (`curl-and-openssl.md`).
- The next three `printf` calls add `ttfb=%{time_starttransfer} total=%{time_total} http=%{http_version}`, then `code=%{http_code} size=%{size_download} connects=%{num_connects}`, then ` server=%header{server-timing}` and `\\n`. In single quotes `\\` reaches `printf` as two characters, and `printf` turns them into one backslash, so the text that curl receives ends in `\n`, which curl in turn turns into a newline. That is why every request writes exactly one line.
- Each call starts its text with a space, so the pieces join into one line with single spaces.

### The dry run, which is the default

```bash
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each request would be:"
  echo "  curl -sS --connect-timeout $CONNECT_TIMEOUT_SECONDS --max-time $MAX_TIME_SECONDS -o /dev/null -w '$(timing_format NAME)' URL"
  echo "add --run to send them"
  exit 0
fi
```

- `if [[ "$mode" == "dry-run" ]]; then ... fi` runs only when no `--run` was given.
- The first `echo` says `dry run: nothing sent. Each request would be:`. The second prints the `curl` command that would run. `'$(timing_format NAME)'` is a **command substitution**: `$(...)` runs the function and pastes its output, with the placeholder `NAME` instead of a real name. The inner single quotes are part of the printed text, so you can paste the printed command into a terminal. The words `NAME` and `URL` stay as placeholders.
- `echo "add --run to send them"` tells the reader the next step.
- `exit 0` ends the script with success. Nothing was sent. This is the safe default: running the script with no flags can never touch a network.

The whole dry run printed this for the loopback file (command output, not a quote of the script):

```
plan: 5 run(s) x 3 target(s) x 1 request(s) = 15 requests
  seatwave-http  http://127.0.0.1:8080/events/1/seats?section=A
  seatwave-https  https://127.0.0.1:8443/events/1/seats?section=A
  seatwave-distant  https://127.0.0.1:8444/events/1/seats?section=A
dry run: nothing sent. Each request would be:
  curl -sS --connect-timeout 5 --max-time 10 -o /dev/null -w 'NAME dns=%{time_namelookup} connect=%{time_connect} tls=%{time_appconnect} ttfb=%{time_starttransfer} total=%{time_total} http=%{http_version} code=%{http_code} size=%{size_download} connects=%{num_connects} server=%header{server-timing}\n' URL
add --run to send them
```

with exit status 0.

### The confirmation step

```bash
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Send $expected_lines requests to the targets above? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing sent" >&2; exit 1; }
fi
```

- `if [[ "$confirmed" != "yes" ]]; then ... fi` runs unless `--yes` was given.
- `read -r -p "Send $expected_lines requests to the targets above? [y/N] " answer < /dev/tty` shows a question and reads the answer into `answer`. `-p` sets the prompt text. `< /dev/tty` reads from the terminal itself, not from the script's input, so the question works even when the script's own input is a pipe. `[y/N]` means the capital letter is the default: no.
- `[[ "$answer" == "y" ]] || { echo "not confirmed; nothing sent" >&2; exit 1; }` accepts only a lower-case `y`. Anything else, including an empty answer, prints `not confirmed; nothing sent` and exits 1. I did not run this path, because it needs a terminal to answer on and it would send requests.

### The runs

```bash
mkdir -p "$(dirname "$out_file")"
audit_file="$(dirname "$out_file")/audit.log"
for index in "${!names[@]}"; do
  name="${names[$index]}"
  url="${urls[$index]}"
  arguments=(-sS --connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$MAX_TIME_SECONDS")
  [[ -n "${cacerts[$index]}" ]] && arguments+=(--cacert "${cacerts[$index]}")
  arguments+=(-w "$(timing_format "$name")" -o /dev/null "$url")
  [[ "$reuse" == "yes" ]] && arguments+=(-o /dev/null "$url")
  for (( run = 1; run <= runs; run++ )); do
    curl "${arguments[@]}" >> "$out_file"
  done
done
```

- `mkdir -p "$(dirname "$out_file")"` makes the output folder if it is missing. `dirname` prints the folder part of a path. `-p` means: no error if it exists already.
- `audit_file="$(dirname "$out_file")/audit.log"` puts the audit log next to the output file.
- The `for index` loop goes over the targets. `name` and `url` are the current ones.
- `arguments=(-sS --connect-timeout "$CONNECT_TIMEOUT_SECONDS" --max-time "$MAX_TIME_SECONDS")` builds an **array of curl arguments**. An array keeps each argument as one word, even if it contains spaces. `-s` is silent (no progress bar), `-S` shows errors anyway. `--connect-timeout` and `--max-time` give the limits from the constants.
- `[[ -n "${cacerts[$index]}" ]] && arguments+=(--cacert "${cacerts[$index]}")` adds `--cacert <file>` only for a target that has a CA file, so `curl` trusts the test CA for that target.
- `arguments+=(-w "$(timing_format "$name")" -o /dev/null "$url")` adds the format, throws the body away (`-o /dev/null`) and ends with the URL.
- `[[ "$reuse" == "yes" ]] && arguments+=(-o /dev/null "$url")` adds the same URL a second time. When `curl` gets two URLs in one command it fetches them one after the other and **reuses the connection** for the second. The format applies to both, so each fetch prints one line. That is how a "new" line and a "reused" line come from one process. The `connects=0` field of the second line proves the reuse.
- `for (( run = 1; run <= runs; run++ )); do ... done` is a counting loop: `run` goes from 1 to `runs`.
- `curl "${arguments[@]}" >> "$out_file"` runs `curl` with all the arguments, and `>>` **appends** its output to the file. `curl` writes only the formatted line, because the body went to `/dev/null`.

### The check after, and the audit line

```bash
# Check after: one line per request, or the run failed somewhere.
written=$(wc -l < "$out_file" | tr -d ' ')
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $expected_lines requests to ${names[*]}; wrote $written lines to $out_file" >> "$audit_file"
if [[ "$written" != "$expected_lines" ]]; then
  echo "expected $expected_lines lines, but $out_file has $written" >&2
  exit 1
fi
echo "wrote $written lines to $out_file; audit line added to $audit_file"
```

- The comment: "Check after: one line per request, or the run failed somewhere."
- `written=$(wc -l < "$out_file" | tr -d ' ')` counts the lines of the output file. `wc -l` counts lines; on macOS it pads the number with spaces, so `tr -d ' '` deletes them.
- `echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent ... wrote $written lines to $out_file" >> "$audit_file"` appends one **audit line**: the UTC time (`date -u`, in the format of the year, month, day, `T`, hour, minute, second and `Z`), how many requests were sent, to which targets (`${names[*]}` joins the array with spaces), and how many lines were written. The audit file is never overwritten, so it is a record of every run.
- `if [[ "$written" != "$expected_lines" ]]; then ... exit 1; fi` compares the line count with the plan. A mismatch means a request failed to print its line; the script says `expected N lines, but ... has M` and exits 1.
- The last `echo` prints `wrote N lines to ...; audit line added to ...`. The README shows this line for 30 requests.

Because of `set -e`, if `curl` itself fails (for example, the server is not running) the script stops at that command with `curl`'s own error message and status. The README's `--run` outputs (`seatwave.txt`, `public.txt`) were produced on 10 November 2026. I did not send anything to a public site for these notes. The lines of curl output quoted in `curl-and-openssl.md` come from the README.

## Try it yourself

These ran in a scratch copy on 10 November 2026. None of them sends a request.

1. Check the syntax without running anything:

```
bash -n scripts/curl-timings.sh; echo "exit=$?"
```

   Expected: `exit=0`.

2. The dry run with the loopback targets:

```
scripts/curl-timings.sh config/targets-seatwave.txt 5 build/timings/x.txt; echo "exit=$?"
```

   Expected: the plan and the `curl` command printed above, then `exit=0`. The folder `build/timings` is not created.

3. Make it fail on purpose, one rule at a time:

```
scripts/curl-timings.sh; echo "exit=$?"
scripts/curl-timings.sh config/targets-seatwave.txt 0 build/timings/x.txt; echo "exit=$?"
scripts/curl-timings.sh config/targets-seatwave.txt 5 README.md; echo "exit=$?"
scripts/curl-timings.sh config/targets-seatwave.txt 5 build/timings/x.txt --fast; echo "exit=$?"
```

   Expected, in order: the usage line and `exit=2`; `runs must be 1 to 50` and `exit=2`; `refusing to overwrite README.md` and `exit=2`; the usage line and `exit=2`.

4. Try the `--reuse` plan (two requests per run):

```
scripts/curl-timings.sh config/targets-seatwave.txt 2 build/timings/x.txt --reuse | head -1
```

   Expected: `plan: 2 run(s) x 3 target(s) x 2 request(s) = 12 requests`.

5. Test a pattern by hand:

```
bash -c 'p="^[a-z0-9-]{1,40}$"; for n in seatwave-http Seat_Wave; do [[ "$n" =~ $p ]] && echo "ok $n" || echo "bad $n"; done'
```

   Expected: `ok seatwave-http` and `bad Seat_Wave`.

6. Run the script checker, if you have it (it is not required):

```
shellcheck scripts/curl-timings.sh
```

   Expected on this machine: one informational note, SC2015, about line 50, and exit status 1.

## Mistakes people make

- **Forgetting quotes around a variable.** `$out_file` without quotes splits at spaces. The script quotes every expansion.
- **Using `[ ]` instead of `[[ ]]` and then `=~`.** The old single-bracket test has no pattern matching. This script uses Bash on purpose.
- **Quoting the pattern on the right of `=~`.** A quoted pattern is matched as plain text, not as a regular expression. The patterns here are in variables and used without quotes.
- **Running with `sh`.** `sh script.sh` ignores the shebang line, and on some systems `sh` has no arrays. Run it as `scripts/curl-timings.sh`.
- **Hiding errors.** `set -euo pipefail` makes failures stop the script. Without it, a broken step lets the rest continue and produces a table that looks fine.
- **Pointing the script at a site you do not own.** The patterns refuse it. Even for public home pages the limit is 3 requests.
- **Overwriting a result.** The script refuses an output file that exists. Choose a new name or delete the old file yourself.

## Check yourself

1. *What does `set -euo pipefail` do?* It stops at the first failing command, treats an unset variable as an error, and makes a pipeline fail if any part of it fails.
2. *What is the default mode, and how do you send real requests?* A dry run that sends nothing. Add `--run`, and `--yes` to skip the question.
3. *What stops the script from timing a random website?* `LOCAL_PATTERN` and `PUBLIC_PATTERN`: a target must be SeatWave on 127.0.0.1, or `https://<host>/`. A public site is also limited to 3 runs and no `--reuse`.
4. *Why does `printf` in `timing_format` use `%%`?* To print a single `%`, so that curl's own `%{...}` variables reach curl.
5. *How does `--reuse` make a "reused" line?* The script gives `curl` the same URL twice in one command, so `curl` fetches both on one connection; the second line has `connects=0`.

## Further reading

- Bash reference manual: <https://www.gnu.org/software/bash/manual/bash.html>
- `[[` conditional expressions: <https://www.gnu.org/software/bash/manual/bash.html#Conditional-Constructs>
- The `set` builtin: <https://www.gnu.org/software/bash/manual/bash.html#The-Set-Builtin>
- curl manual, `--write-out`: <https://curl.se/docs/manpage.html#-w>
