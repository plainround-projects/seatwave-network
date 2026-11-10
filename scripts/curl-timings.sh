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
set -euo pipefail

readonly MAX_PUBLIC_RUNS=3
readonly MAX_LOCAL_RUNS=50
readonly CONNECT_TIMEOUT_SECONDS=5
readonly MAX_TIME_SECONDS=10
readonly NAME_PATTERN='^[a-z0-9-]{1,40}$'
readonly LOCAL_PATTERN='^https?://127\.0\.0\.1:[0-9]{4,5}/[A-Za-z0-9/?=]*$'
readonly PUBLIC_PATTERN='^https://[a-z0-9.-]+\.[a-z]{2,}/$'

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
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    --reuse) reuse="yes" ;;
    *) usage ;;
  esac
done

# Check before: the tool, the inputs and the output.
command -v curl >/dev/null || { echo "curl is not installed" >&2; exit 1; }
[[ -f "$targets_file" ]] || { echo "no targets file: $targets_file" >&2; exit 2; }
[[ "$runs" =~ ^[0-9]+$ ]] && (( runs >= 1 && runs <= MAX_LOCAL_RUNS )) \
  || { echo "runs must be 1 to $MAX_LOCAL_RUNS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }

names=()
urls=()
cacerts=()
while read -r name url cacert extra; do
  [[ -z "${name:-}" || "$name" == \#* ]] && continue
  [[ -z "${extra:-}" ]] || { echo "too many fields for $name" >&2; exit 2; }
  [[ "$name" =~ $NAME_PATTERN ]] || { echo "invalid name: $name" >&2; exit 2; }
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
  names+=("$name")
  urls+=("$url")
  cacerts+=("${cacert:-}")
done < "$targets_file"
(( ${#names[@]} > 0 )) || { echo "the targets file lists no target" >&2; exit 2; }

per_run=1
[[ "$reuse" == "yes" ]] && per_run=2
expected_lines=$(( ${#names[@]} * runs * per_run ))
echo "plan: $runs run(s) x ${#names[@]} target(s) x $per_run request(s) = $expected_lines requests"
for index in "${!names[@]}"; do
  echo "  ${names[$index]}  ${urls[$index]}"
done

timing_format() {
  # One line per request; the Server-Timing header comes last because it may hold spaces.
  printf '%s dns=%%{time_namelookup} connect=%%{time_connect} tls=%%{time_appconnect}' "$1"
  printf ' ttfb=%%{time_starttransfer} total=%%{time_total} http=%%{http_version}'
  printf ' code=%%{http_code} size=%%{size_download} connects=%%{num_connects}'
  printf ' server=%%header{server-timing}\\n'
}

if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each request would be:"
  echo "  curl -sS --connect-timeout $CONNECT_TIMEOUT_SECONDS --max-time $MAX_TIME_SECONDS -o /dev/null -w '$(timing_format NAME)' URL"
  echo "add --run to send them"
  exit 0
fi

if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Send $expected_lines requests to the targets above? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing sent" >&2; exit 1; }
fi

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

# Check after: one line per request, or the run failed somewhere.
written=$(wc -l < "$out_file" | tr -d ' ')
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $expected_lines requests to ${names[*]}; wrote $written lines to $out_file" >> "$audit_file"
if [[ "$written" != "$expected_lines" ]]; then
  echo "expected $expected_lines lines, but $out_file has $written" >&2
  exit 1
fi
echo "wrote $written lines to $out_file; audit line added to $audit_file"
