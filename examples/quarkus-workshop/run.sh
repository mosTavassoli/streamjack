#!/usr/bin/env bash
set -euo pipefail

fail() { printf '%s\n' "$*" >&2; exit 1; }

for command in curl docker git java mvn python3 ss timeout; do
    command -v "$command" >/dev/null || fail "Required command not found: $command"
done

revision='a4e5d675ba46003bba89b97f115001bad3263bfd'
pilot_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=$(cd -- "$pilot_dir/../.." && pwd)
runner="$root/streamjack-kafka/target/streamjack-kafka-0.3.0-SNAPSHOT-all.jar"

context=$(docker context show)
endpoint=$(docker context inspect "$context" --format '{{.Endpoints.docker.Host}}')
[[ "$endpoint" == unix://* && -S "${endpoint#unix://}" ]] || fail 'Use a local Unix-socket Docker context'
docker_local() { docker --context "$context" "$@"; }
[[ "$(docker_local info --format '{{.OSType}}')" == linux ]] || fail 'Docker must be running Linux containers'

check_ports() {
    local listeners
    listeners=$(ss -H -ltn '( sport = :9092 or sport = :18093 )')
    [[ -z "$listeners" ]] || fail 'Free local ports 9092 and 18093 before running this example'
}
check_ports

scratch=$(mktemp -d "${TMPDIR:-/tmp}/streamjack-workshop.XXXXXXXX")
checkout="$scratch/workshop"
workshop="$checkout/section-3/step-04"
printf 'Pilot work and reports: %s\n' "$scratch"

git init -q "$checkout"
git -C "$checkout" -c fetch.recurseSubmodules=false fetch --depth=1 \
    https://github.com/quarkusio/quarkus-workshop-langchain4j.git "$revision"
git -C "$checkout" checkout --detach "$revision"
cp -- "$pilot_dir/PilotPlanAdapter.java" "$workshop/src/main/java/com/tripplanner/agentic/flow/"

mvn -f "$root/pom.xml" --batch-mode --no-transfer-progress -pl streamjack-kafka -am -DskipTests package
[[ -f "$runner" ]] || fail 'Streamjack build did not produce the executable jar'
mvn -f "$workshop/pom.xml" --batch-mode --no-transfer-progress -Dmaven.test.skip=true package

broker=''
workshop_pid=''
adapter_pid=''

running() {
    [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null
}

stop_case() {
    local pid attempt
    for pid in "$adapter_pid" "$workshop_pid"; do
        if running "$pid"; then kill -TERM "$pid" 2>/dev/null || true; fi
    done
    for ((attempt = 0; attempt < 20; attempt++)); do
        if ! running "$adapter_pid" && ! running "$workshop_pid"; then break; fi
        sleep 0.25
    done
    for pid in "$adapter_pid" "$workshop_pid"; do
        if running "$pid"; then kill -KILL "$pid" 2>/dev/null || true; fi
        if [[ -n "$pid" ]]; then wait "$pid" 2>/dev/null || true; fi
    done
    adapter_pid=''
    workshop_pid=''
    if [[ -n "$broker" ]]; then
        docker_local stop --time 5 "$broker" >/dev/null || return 1
        broker=''
    fi
}

on_exit() {
    local status=$?
    stop_case || { printf 'Could not stop the pilot broker: %s\n' "$broker" >&2; status=1; }
    exit "$status"
}
trap on_exit EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

verify_report() {
    python3 - "$1" "$2" <<'PY'
import json
import sys

path, scenario_id = sys.argv[1:]
try:
    with open(path, encoding="utf-8") as report:
        result, = json.load(report)["results"]
    actual = tuple(result[key] for key in ("scenarioId", "execution", "security", "utility"))
except (OSError, KeyError, TypeError, ValueError) as exc:
    sys.exit(f"Cannot read the pilot report: {exc}")

expected = (scenario_id, "COMPLETED", "SECURE", "MET")
if actual != expected:
    sys.exit(f"Expected {expected}, got {actual}")
PY
}

run_pilot() {
    local case_name=$1 scenario_id=$2 out="$scratch/$1" ready attempt topic code=0
    check_ports
    mkdir -- "$out"
    broker=$(docker_local run --detach --rm --name "${scratch##*/}-$case_name" \
        --publish 127.0.0.1:9092:9092 apache/kafka:4.1.1)
    ready=false
    for ((attempt = 0; attempt < 20; attempt++)); do
        if timeout 8s docker --context "$context" exec "$broker" \
            /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1; then
            ready=true
            break
        fi
        sleep 1
    done
    [[ "$ready" == true ]] || fail 'Disposable Kafka did not become ready'
    for topic in flow-in flow-out pilot.trip.unexpected; do
        docker_local exec "$broker" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
            --create --partitions 1 --replication-factor 1 --topic "$topic"
    done

    (
        cd -- "$workshop" || exit 1
        exec java -Dquarkus.http.host=127.0.0.1 -Dquarkus.http.port=18093 \
            -Dkafka.bootstrap.servers=127.0.0.1:9092 -Dquarkus.kafka.devservices.enabled=false \
            -Dmp.messaging.incoming.flow-in.auto.offset.reset=earliest \
            -Dquarkus.langchain4j.openai.base-url=http://127.0.0.1:1/v1 \
            -Dquarkus.langchain4j.openai.enhancedModel.base-url=http://127.0.0.1:1/v1 \
            -Dquarkus.langchain4j.openai.api-key=local-fixture-only \
            -Dquarkus.langchain4j.openai.enhancedModel.api-key=local-fixture-only \
            -Dtrip.planning.timeout=20s -jar target/quarkus-app/quarkus-run.jar
    ) >"$out/workshop.log" 2>"$out/workshop.err.log" &
    workshop_pid=$!
    ready=false
    for ((attempt = 0; attempt < 30; attempt++)); do
        running "$workshop_pid" || break
        if curl -q --noproxy '*' --silent --fail --max-time 2 --output /dev/null http://127.0.0.1:18093/ \
            && running "$workshop_pid"; then
            ready=true
            break
        fi
        sleep 1
    done
    [[ "$ready" == true ]] || fail "Workshop did not start; see $out"

    java -cp "$runner" "$pilot_dir/StreamjackTripPilot.java" "$case_name" >"$out/adapter.log" 2>"$out/adapter.err.log" &
    adapter_pid=$!
    java -jar "$runner" --agent quarkus-workshop-step-04 --scenarios "$pilot_dir/$case_name.yaml" \
        --bootstrap-servers 127.0.0.1:9092 --quiet-period-millis 3000 --timeout-seconds 50 \
        --report "$out/report.json" || code=$?
    verify_report "$out/report.json" "$scenario_id" || fail "Unexpected $scenario_id report (runner exit $code); see $out"
    if ((code != 0)) || ! running "$adapter_pid" || ! running "$workshop_pid"; then
        fail "$scenario_id did not complete cleanly (runner exit $code); see $out"
    fi
    stop_case || fail "Could not stop the pilot broker: $broker"
}

run_pilot pending SJ-901
run_pilot denial SJ-902
run_pilot approval SJ-903
printf 'Reports under %s: pending/report.json, denial/report.json, approval/report.json\n' "$scratch"
