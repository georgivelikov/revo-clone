#!/usr/bin/env bash
# task 09.12: cross-platform companion to tasks.ps1 - same command surface, same behaviour,
# ported function-for-function so the two stay in sync. See tasks.ps1's own header comment for
# the full rationale behind each command; not repeated here to avoid the two files drifting out
# of sync in explanation while staying in sync in behaviour.
#
#   tasks.sh up                  up the infra stack, wait for every container healthy
#   tasks.sh down                 stop the stack, keep volumes
#   tasks.sh reset                stop the stack AND drop its volumes (fresh Postgres/Kafka/
#                                  Keycloak data) - the only way Keycloak re-imports the realm,
#                                  since --import-realm only fires against an empty database
#   tasks.sh logs <svc> [-f]      tail a service's logs (-f / --follow to stream)
#   tasks.sh psql <db>            open psql in the postgres container as that database's owner
#   tasks.sh topics [--describe]  create every topic in the catalogue (idempotent), or describe
#   tasks.sh seed                 idempotent demo-data generator (tools/seed/seed.sh)
#   tasks.sh analytics-rebuild    drop + replay analytics-service's projection from offset zero
#   tasks.sh point-in-time <ts>   replay ledger.entry.v1 up to an ISO-8601 instant
#   tasks.sh demo [--no-pause]    scripted walkthrough: fund, transfer, exchange, card spend,
#                                  advance clock, settle, dashboard
#
# Requires: bash, curl, jq, docker (compose). jq is only needed for seed/demo/point-in-time, not
# for up/down/reset/logs/psql/topics.

set -euo pipefail

# Git Bash on Windows (MSYS2) rewrites a Unix-looking absolute path in an argument - like the
# /opt/kafka/... paths below - into a Windows path before docker ever sees it, breaking every
# `docker exec ... /opt/kafka/bin/...` call. A no-op on real Linux/macOS, where this script is
# primarily aimed, but harmless and necessary to set either way.
export MSYS_NO_PATHCONV=1

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
compose=(-f docker-compose.infra.yml)
compose_services=(-f docker-compose.infra.yml -f docker-compose.yml --profile services)
known_dbs=(identity account ledger payment fx card analytics notification)

say()  { echo -e "\033[36m[tasks] $*\033[0m"; }
warn() { echo -e "\033[33m[tasks] $*\033[0m"; }
die()  { echo -e "\033[31m[tasks] $*\033[0m" >&2; exit 1; }

gateway_base="http://localhost:8080"
ledger_base="http://localhost:8083"
simulator_base="http://localhost:8089"
token_endpoint="http://localhost:8180/realms/revolut/protocol/openid-connect/token"

# The topic catalogue lives in one place - infra/kafka/create-topics.sh - so `topics --describe`
# reads the names out of it rather than keeping a second copy here to drift.
get_topic_names() {
  grep -E '^\s*create\s+\S+' "$repo/infra/kafka/create-topics.sh" | awk '{print $2}'
}

# uuidgen doesn't exist on Windows (Git Bash included) and /proc/sys/kernel/random/uuid is
# Linux-only - found live testing this script. openssl (near-universal, ships with Git Bash too)
# is the portable fallback; the printf reassembles its random hex into proper UUIDv4 form
# (version nibble 4, variant nibble 8-b).
generate_uuid() {
  if command -v uuidgen >/dev/null 2>&1; then uuidgen; return; fi
  if [ -r /proc/sys/kernel/random/uuid ]; then cat /proc/sys/kernel/random/uuid; return; fi
  local hex
  hex=$(openssl rand -hex 16)
  printf '%s-%s-4%s-%s%s-%s\n' \
    "${hex:0:8}" "${hex:8:4}" "${hex:13:3}" \
    "$(printf '%x' $(( (16#${hex:16:1} & 3) | 8 )))" "${hex:17:3}" "${hex:20:12}"
}

get_access_token() {
  local email="$1" password="$2" resp token
  resp=$(curl -s -X POST "$token_endpoint" \
    -d grant_type=password -d client_id=revolut-web -d username="$email" -d password="$password" -d scope=openid)
  token=$(echo "$resp" | jq -r '.access_token // empty')
  if [ -z "$token" ]; then die "could not get an access token for $email: $resp"; fi
  echo "$token"
}

wait_for_container_healthy() {
  # wait_for_container_healthy SERVICE [COMPOSE_ARGS_STRING] [TIMEOUT_SECONDS]
  local service="$1"; shift
  local -a compose_args=("${!1}"); shift || true
  local timeout="${1:-180}"
  local deadline=$(( $(date +%s) + timeout ))
  while true; do
    local cid health
    cid=$(docker compose "${compose_args[@]}" ps -q "$service" 2>/dev/null | head -1)
    if [ -n "$cid" ]; then
      health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid")
      if [ "$health" = "healthy" ] || [ "$health" = "no-healthcheck" ]; then return 0; fi
    fi
    if [ "$(date +%s)" -gt "$deadline" ]; then die "timed out waiting for $service to report healthy"; fi
    sleep 2
  done
}

# plans/phase-07-resilience-and-scale.md's ### Replay, flavour 1. See tasks.ps1's
# Invoke-AnalyticsRebuild for the full rationale (why the service is stopped first, why the
# schema is dropped+recreated rather than the whole database, why Flyway re-migrates on restart).
analytics_rebuild() {
  local group_id="analytics-service"
  local topics=(ledger.entry.v1 card.authorization.v1)

  say 'stopping analytics-service (a consumer-group offset reset silently no-ops against a running member)...'
  docker compose "${compose_services[@]}" stop analytics-service

  say 'dropping and recreating the analytics schema...'
  docker compose "${compose[@]}" exec -T postgres psql -U analytics -d analytics -v ON_ERROR_STOP=1 \
    -c 'DROP SCHEMA public CASCADE; CREATE SCHEMA public;'

  say "resetting the $group_id consumer group to offset zero on ${topics[*]}..."
  local -a topic_args=()
  for t in "${topics[@]}"; do topic_args+=(--topic "$t"); done
  docker compose "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server 127.0.0.1:9092 \
    --group "$group_id" --reset-offsets --to-earliest "${topic_args[@]}" --execute

  say 'restarting analytics-service - Flyway re-migrates the schema, then the consumers replay from offset zero...'
  docker compose "${compose_services[@]}" up -d analytics-service
  wait_for_container_healthy analytics-service compose_services[@]
  say 'analytics-service is healthy again. Check the dashboard for identical aggregates, and confirm no new notification arrived.'
}

# plans/phase-07-resilience-and-scale.md's ### Replay, flavour 2. Read-only - PointInTimeService
# uses its own throwaway consumer group, same reasoning as common-messaging's DltAdminService.
point_in_time_query() {
  local timestamp="${1:-}"
  if [ -z "$timestamp" ]; then die "usage: tasks.sh point-in-time <ISO-8601 instant, e.g. 2026-09-23T14:00:00Z>"; fi

  local as_of
  if ! as_of=$(date -u -d "$timestamp" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null) && \
     ! as_of=$(date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$timestamp" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null); then
    die "'$timestamp' is not a parseable timestamp"
  fi

  local token
  token=$(get_access_token alice@demo.io demo) || die 'could not log in as the demo user for a bearer token - run "tasks.sh seed" first'

  say "replaying ledger.entry.v1 up to $as_of..."
  local encoded result count
  encoded=$(jq -rn --arg v "$as_of" '$v|@uri')
  result=$(curl -s "$ledger_base/internal/ledger/admin/point-in-time?asOf=$encoded" -H "Authorization: Bearer $token")
  count=$(echo "$result" | jq '.balances | length')
  if [ "$count" -eq 0 ]; then
    say "no ledger.entry.v1 activity on or before $as_of."
    return
  fi
  echo "$result" | jq -r '.balances[] | [.ledgerAccountId, .currency, .balanceAfterMinor, .occurredAt] | @tsv' | \
    (echo -e "ledgerAccountId\tcurrency\tbalanceAfterMinor\toccurredAt"; cat) | column -t -s $'\t'
}

# plans/phase-09-polish.md task 09.5. Narrated, not idempotent by design - every money-moving
# call gets a fresh Idempotency-Key, unlike `seed`. Needs `tasks.sh seed` to have run at least
# once already (alice@demo.io and carol@demo.io must already have EUR wallets).
no_pause=0
wait_for_demo_step() {
  local step="$1" narration="$2"
  say "$step - $narration"
  if [ "$no_pause" -ne 1 ]; then
    echo -n "[tasks]   press Enter to continue..." >&2
    read -r _ || true
  fi
}

demo_wallet() {
  local token="$1" currency="$2" accounts wallet
  accounts=$(curl -s "$gateway_base/api/accounts/me" -H "Authorization: Bearer $token")
  wallet=$(echo "$accounts" | jq -c --arg c "$currency" '[.[] | select(.currency==$c and .type=="WALLET")][0]')
  if [ "$wallet" = "null" ] || [ -z "$wallet" ]; then die "no $currency wallet for this user - run \"tasks.sh seed\" first"; fi
  echo "$wallet"
}

demo_balance_available() {
  local token="$1" ledger_account_id="$2"
  curl -s "$ledger_base/internal/ledger/accounts/$ledger_account_id/balance" -H "Authorization: Bearer $token" | jq -r '.availableMinor'
}

run_demo() {
  local alice_token carol_token
  alice_token=$(get_access_token alice@demo.io demo) || die 'could not log in as the demo users - run "tasks.sh seed" first'
  carol_token=$(get_access_token carol@demo.io demo) || die 'could not log in as the demo users - run "tasks.sh seed" first'

  local alice_eur alice_gbp carol_eur
  alice_eur=$(demo_wallet "$alice_token" EUR)
  alice_gbp=$(demo_wallet "$alice_token" GBP)
  carol_eur=$(demo_wallet "$carol_token" EUR)

  local alice_eur_id alice_eur_lacct alice_gbp_lacct carol_eur_id
  alice_eur_id=$(echo "$alice_eur" | jq -r '.id'); alice_eur_lacct=$(echo "$alice_eur" | jq -r '.ledgerAccountId')
  alice_gbp_lacct=$(echo "$alice_gbp" | jq -r '.ledgerAccountId')
  carol_eur_id=$(echo "$carol_eur" | jq -r '.id')

  say 'Revolut clone demo - 7 steps, narrate each one as it happens.'

  wait_for_demo_step '1/7 FUND' "topping up Alice's EUR wallet by EUR 25.00..."
  local before after
  before=$(demo_balance_available "$alice_token" "$alice_eur_lacct")
  curl -s -X POST "$ledger_base/internal/ledger/admin/fund/$alice_eur_lacct" -H "Authorization: Bearer $alice_token" \
    -H "Content-Type: application/json" -d '{"amountMinor":2500,"currency":"EUR"}' > /dev/null
  after=$(demo_balance_available "$alice_token" "$alice_eur_lacct")
  say "  Alice's EUR balance: $before -> $after minor units"

  wait_for_demo_step '2/7 TRANSFER' 'Alice sends Carol EUR 10.00...'
  local body raw status
  body=$(jq -n --arg src "$alice_eur_id" --arg tgt "$carol_eur_id" '{sourceAccountId:$src, targetAccountId:$tgt, amountMinor:1000, currency:"EUR", reference:"Demo transfer"}')
  raw=$(curl -s -w '\n%{http_code}' -X POST "$gateway_base/api/payments" -H "Authorization: Bearer $alice_token" \
    -H "Content-Type: application/json" -H "Idempotency-Key: $(generate_uuid)" -d "$body")
  status="${raw##*$'\n'}"
  [ "$status" = "202" ] || die "demo transfer failed: HTTP $status: ${raw%$'\n'*}"
  say '  payment accepted (202) - settles asynchronously, same as the real app.'

  wait_for_demo_step '3/7 EXCHANGE' 'Alice exchanges EUR 15.00 for GBP...'
  local quote_body quote quote_id gbp_before_exchange
  quote_body='{"sellCurrency":"EUR","buyCurrency":"GBP","sellMinor":1500}'
  raw=$(curl -s -w '\n%{http_code}' -X POST "$gateway_base/api/fx/quotes" -H "Authorization: Bearer $alice_token" \
    -H "Content-Type: application/json" -H "Idempotency-Key: $(generate_uuid)" -d "$quote_body")
  status="${raw##*$'\n'}"; quote="${raw%$'\n'*}"
  [ "$status" = "200" ] || [ "$status" = "201" ] || die "demo fx quote failed: HTTP $status: $quote"
  quote_id=$(echo "$quote" | jq -r '.quoteId')
  say "  quoted: sell 1500 EUR-minor for $(echo "$quote" | jq -r '.buyMinor') GBP-minor at $(echo "$quote" | jq -r '.clientRate') (fee $(echo "$quote" | jq -r '.feeMinor'))"
  gbp_before_exchange=$(demo_balance_available "$alice_token" "$alice_gbp_lacct")
  raw=$(curl -s -w '\n%{http_code}' -X POST "$gateway_base/api/fx/quotes/$quote_id/execute" -H "Authorization: Bearer $alice_token" \
    -H "Idempotency-Key: $(generate_uuid)")
  status="${raw##*$'\n'}"
  [ "$status" = "200" ] || [ "$status" = "202" ] || die "demo fx execute failed: HTTP $status: ${raw%$'\n'*}"
  local deadline=$(( $(date +%s) + 10 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if [ "$(demo_balance_available "$alice_token" "$alice_gbp_lacct")" -gt "$gbp_before_exchange" ]; then break; fi
    sleep 0.5
  done
  say '  exchange executed - the hold is captured into a posted FX entry off the back of this.'

  wait_for_demo_step '4/7 CARD SPEND' 'issuing Alice a card and tapping it at a merchant...'
  local existing_cards card_id
  existing_cards=$(curl -s "$gateway_base/api/cards" -H "Authorization: Bearer $alice_token")
  if [ "$(echo "$existing_cards" | jq 'length')" -gt 0 ]; then
    card_id=$(echo "$existing_cards" | jq -r '.[0].id')
    say "  reusing existing card $card_id"
  else
    local card_body
    card_body=$(jq -n --arg acc "$alice_eur_id" '{accountId:$acc, type:"VIRTUAL", label:"Demo card", pin:"1234"}')
    card_id=$(curl -s -X POST "$gateway_base/api/cards" -H "Authorization: Bearer $alice_token" \
      -H "Content-Type: application/json" -d "$card_body" | jq -r '.id')
    say "  issued card $card_id"
  fi
  local spend
  spend=$(curl -s -X POST "$simulator_base/simulator/cards/spend" -H "Content-Type: application/json" \
    -d "$(jq -n --arg cid "$card_id" '{cardId:$cid}')")
  say "  authorized $(echo "$spend" | jq -r '.amountMinor') $(echo "$spend" | jq -r '.currency') at $(echo "$spend" | jq -r '.merchantName') (networkRef $(echo "$spend" | jq -r '.networkRef'))"

  wait_for_demo_step '5/7 ADVANCE CLOCK' "advancing the simulator's clock so the tap settles..."
  curl -s -X POST "$simulator_base/simulator/clock/advance-day" > /dev/null
  curl -s -X POST "$simulator_base/simulator/clock/advance-day" > /dev/null
  say '  clock advanced - the settlement batch for today has gone out.'

  wait_for_demo_step '6/7 SETTLE' 'checking the authorization settled...'
  local network_ref settled=0
  network_ref=$(echo "$spend" | jq -r '.networkRef')
  deadline=$(( $(date +%s) + 15 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local state
    state=$(curl -s "$gateway_base/api/cards/$card_id/authorizations" -H "Authorization: Bearer $alice_token" | \
      jq -r --arg ref "$network_ref" '.[] | select(.networkRef==$ref) | .state')
    if [ "$state" = "SETTLED" ]; then settled=1; break; fi
    sleep 0.5
  done
  if [ "$settled" -eq 1 ]; then say '  settled.'; else warn '  still pending after 15s - the Activity screen will catch up shortly.'; fi

  wait_for_demo_step '7/7 DASHBOARD' 'here is what changed - switch to the browser now.'
  local final_eur final_gbp
  final_eur=$(demo_balance_available "$alice_token" "$alice_eur_lacct")
  final_gbp=$(demo_balance_available "$alice_token" "$alice_gbp_lacct")
  say "  Alice: EUR $final_eur minor, GBP $final_gbp minor available"
  say '  Open http://localhost:5173 and log in as alice@demo.io / demo to see the Home, Activity and Exchange screens update.'
  say 'Demo complete.'
}

# --- main ------------------------------------------------------------------------------

command="${1:-}"
[ -n "$command" ] || die "usage: tasks.sh <up|down|reset|logs|psql|topics|seed|analytics-rebuild|point-in-time|demo> [args]"
shift || true

target=""
describe=0
follow=0
if [ "$command" = "logs" ] || [ "$command" = "psql" ] || [ "$command" = "point-in-time" ]; then
  target="${1:-}"; shift || true
fi
for arg in "$@"; do
  case "$arg" in
    --describe) describe=1 ;;
    --follow|-f) follow=1 ;;
    --no-pause) no_pause=1 ;;
  esac
done

cd "$repo"

case "$command" in
  up)
    say 'starting infra stack (postgres, kafka, kafka-ui, redis, keycloak)...'
    docker compose "${compose[@]}" up -d

    say 'waiting for every container to report healthy...'
    mapfile -t services < <(docker compose "${compose[@]}" ps --services)
    deadline=$(( $(date +%s) + 180 ))
    pending=("${services[@]}")
    while [ "${#pending[@]}" -gt 0 ]; do
      if [ "$(date +%s)" -gt "$deadline" ]; then die "timed out waiting for: ${pending[*]}"; fi
      remaining=()
      for svc in "${pending[@]}"; do
        cid=$(docker compose "${compose[@]}" ps -q "$svc" 2>/dev/null | head -1)
        if [ -n "$cid" ]; then
          health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' "$cid")
          if [ "$health" = "healthy" ] || [ "$health" = "no-healthcheck" ]; then
            say "  $svc : $health"
            continue
          fi
        fi
        remaining+=("$svc")
      done
      pending=("${remaining[@]}")
      # Not `[ cond ] && sleep 2` as a standalone statement: under `set -e`, the `&&` chain's
      # own exit status is the LHS's when it's false, and a false status at a script's top level
      # kills the script right there - found live, this exact line, once `pending` emptied out.
      if [ "${#pending[@]}" -gt 0 ]; then sleep 2; fi
    done
    say 'all containers healthy. Run "./tasks.sh topics" to create the Kafka topic catalogue.'
    ;;

  down)
    say 'stopping infra stack (volumes kept)...'
    docker compose "${compose[@]}" down
    ;;

  reset)
    warn 'stopping infra stack and DROPPING its volumes - postgres/kafka/keycloak data will be lost.'
    docker compose "${compose[@]}" down -v
    ;;

  logs)
    [ -n "$target" ] || die 'usage: tasks.sh logs <service>   (postgres|kafka|kafka-ui|redis|keycloak)'
    if [ "$follow" -eq 1 ]; then
      docker compose "${compose[@]}" logs -f "$target"
    else
      docker compose "${compose[@]}" logs --tail=200 "$target"
    fi
    ;;

  psql)
    [ -n "$target" ] || die 'usage: tasks.sh psql <database>   (identity|account|ledger|payment|fx|card|analytics|notification)'
    if ! printf '%s\n' "${known_dbs[@]}" | grep -qx "$target"; then
      warn "'$target' is not one of the known databases (${known_dbs[*]}) - trying anyway."
    fi
    docker compose "${compose[@]}" exec postgres psql -U "$target" -d "$target"
    ;;

  topics)
    if [ "$describe" -eq 1 ]; then
      say 'describing every declared topic (cleanup.policy, retention.ms)...'
      while IFS= read -r topic; do
        docker compose "${compose[@]}" exec -T kafka /opt/kafka/bin/kafka-topics.sh \
          --bootstrap-server 127.0.0.1:9092 --describe --topic "$topic"
      done < <(get_topic_names)
    else
      say 'creating the topic catalogue (idempotent)...'
      cid=$(docker compose "${compose[@]}" ps -q kafka | head -1)
      [ -n "$cid" ] || die 'kafka container is not running - run "tasks.sh up" first'
      docker cp "$repo/infra/kafka/create-topics.sh" "$cid:/tmp/create-topics.sh"
      docker compose "${compose[@]}" exec -T kafka bash /tmp/create-topics.sh
    fi
    ;;

  seed)
    bash "$repo/tools/seed/seed.sh"
    ;;

  analytics-rebuild)
    analytics_rebuild
    ;;

  point-in-time)
    point_in_time_query "$target"
    ;;

  demo)
    run_demo
    ;;

  *)
    die "unknown command '$command' - usage: tasks.sh <up|down|reset|logs|psql|topics|seed|analytics-rebuild|point-in-time|demo> [args]"
    ;;
esac
