#!/usr/bin/env bash
# task 09.12: cross-platform companion to seed.ps1 - same idempotent demo-data generator, same
# behaviour, ported function-for-function. See seed.ps1's own header comment for the full design
# rationale (why reconcile rather than register, why funding is gated on real postings rather
# than a flag, why P2P/FX use fixed Idempotency-Keys, why a balance-increase timeout is a warning
# not a failure) - not repeated here, so the two files stay in sync in behaviour without having
# to stay in sync in prose.
#
# Requires: bash, curl, jq, docker (compose). Run from the repo root with the full stack up
# (tasks.sh up, then docker compose -f docker-compose.infra.yml -f docker-compose.yml
# --profile services up -d --build).

set -euo pipefail

# See tasks.sh's identical export for why: Git Bash on Windows (MSYS2) otherwise rewrites a
# Unix-looking absolute path in an argument into a Windows one before docker ever sees it. A
# no-op on real Linux/macOS.
export MSYS_NO_PATHCONV=1

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose=(-f docker-compose.infra.yml)

say()  { echo -e "\033[36m[seed] $*\033[0m" >&2; }
warn() { echo -e "\033[33m[seed] $*\033[0m" >&2; }
die()  { echo -e "\033[31m[seed] $*\033[0m" >&2; exit 1; }

gateway_base="http://localhost:8080"
ledger_base="http://localhost:8083"
simulator_base="http://localhost:8089"
token_endpoint="http://localhost:8180/realms/revolut/protocol/openid-connect/token"

demo_password="demo"
operator_email="seed-operator@revolutclone.local"
operator_password="SeedOperator1!"

history_batch_count=75
history_advance_day_count=5
vault_goal_minor=500000

# --- http helpers -------------------------------------------------------------------------

# http_call prints "<body>\n<status>" to stdout; resp_body/resp_status split it back apart.
# Deliberately NOT a global $http_status side-channel set inside the function: a call site that
# captures the body via `x=$(http_call ...)` runs http_call in a subshell, so any variable it
# assigned internally would vanish the moment that subshell exits and the parent would never see
# it - found live testing this script against the real stack, every call read a stale/empty
# status. Passing the combined raw response to pure, non-subshell-sensitive splitter functions
# avoids the trap entirely.
http_call() {
  # http_call METHOD URL [TOKEN] [JSON_BODY] [EXTRA_HEADER]
  local method="$1" url="$2" token="${3:-}" json_body="${4:-}" extra_header="${5:-}"
  local -a args=(-s -w '\n%{http_code}' -X "$method" "$url")
  # If-statements, not `[ cond ] && args+=(...)` as standalone statements: under `set -e`, a
  # bare `&&` chain's exit status is the left side's when it's false, which kills the script at
  # that line - see tasks.sh's identical note on its `up` command's health-poll loop, where this
  # exact shape of bug was found live. Every call site here always passes a non-empty token and
  # (when used) a non-empty extra_header, so it never actually fired, but it is a real landmine.
  if [ -n "$token" ]; then args+=(-H "Authorization: Bearer $token"); fi
  if [ -n "$json_body" ]; then
    args+=(-H "Content-Type: application/json" -d "$json_body")
  fi
  if [ -n "$extra_header" ]; then args+=(-H "$extra_header"); fi
  curl "${args[@]}"
}

resp_status() { printf '%s' "${1##*$'\n'}"; }
resp_body()   { printf '%s' "${1%$'\n'*}"; }

get_access_token() {
  local email="$1" password="$2"
  local resp token
  resp=$(curl -s -X POST "$token_endpoint" \
    -d grant_type=password -d client_id=revolut-web -d username="$email" -d password="$password" -d scope=openid)
  token=$(echo "$resp" | jq -r '.access_token // empty')
  if [ -z "$token" ]; then die "could not get an access token for $email: $resp"; fi
  echo "$token"
}

# --- seed steps, one function per concern - mirrors seed.ps1's own decomposition ----------

invoke_reconcile() {
  say 'reconciling pre-provisioned Keycloak users (alice/bob/carol/dan@demo.io)...'
  local operator_token raw status body names
  operator_token=$(get_access_token "$operator_email" "$operator_password")
  raw=$(http_call POST "$gateway_base/api/identity/admin/reconcile" "$operator_token")
  status=$(resp_status "$raw"); body=$(resp_body "$raw")
  if [ "$status" != "200" ]; then die "reconcile failed: HTTP $status: $body"; fi
  names=$(echo "$body" | jq -r '[.[].email] | join(", ")')
  if [ -n "$names" ]; then say "  newly reconciled: $names"
  else say '  nothing to reconcile - every provider user already has a profile'
  fi
}

# Prints the JSON array of the 3 ready wallet accounts on stdout once they exist.
wait_for_all_wallets() {
  local token="$1"
  local deadline=$(( $(date +%s) + 30 ))
  while true; do
    local accounts ready count
    accounts=$(curl -s "$gateway_base/api/accounts/me" -H "Authorization: Bearer $token")
    ready=$(echo "$accounts" | jq -c '[.[] | select(.type=="WALLET" and (.currency=="EUR" or .currency=="GBP" or .currency=="USD") and .ledgerAccountId != null)]')
    count=$(echo "$ready" | jq 'length')
    if [ "$count" -eq 3 ]; then echo "$ready"; return 0; fi
    if [ "$(date +%s)" -gt "$deadline" ]; then
      die "timed out waiting for EUR/GBP/USD wallets to all have a ledgerAccountId"
    fi
    sleep 1
  done
}

test_already_funded() {
  local token="$1" ledger_account_id="$2" postings count
  postings=$(curl -s "$ledger_base/internal/ledger/accounts/$ledger_account_id/postings?size=1" -H "Authorization: Bearer $token")
  count=$(echo "$postings" | jq 'length')
  [ "$count" -gt 0 ]
}

fund_if_never_funded() {
  local token="$1" ledger_account_id="$2" amount_minor="$3" currency="$4"
  if test_already_funded "$token" "$ledger_account_id"; then
    say "    $currency already funded in a previous run - skipping"
    return
  fi
  local body raw status
  body=$(jq -n --argjson amt "$amount_minor" --arg cur "$currency" '{amountMinor:$amt, currency:$cur}')
  raw=$(http_call POST "$ledger_base/internal/ledger/admin/fund/$ledger_account_id" "$token" "$body")
  status=$(resp_status "$raw")
  case "$status" in 200|201|202|204) ;; *) die "funding $currency failed: HTTP $status" ;; esac
  say "    funded $currency wallet with $amount_minor minor units"
}

get_balance_available() {
  local token="$1" ledger_account_id="$2"
  curl -s "$ledger_base/internal/ledger/accounts/$ledger_account_id/balance" -H "Authorization: Bearer $token" | jq -r '.availableMinor'
}

# A timeout here is a warning (return 1), not a die - see seed.ps1's own comment on why: a rerun
# whose Idempotency-Key was already consumed correctly replays the original response and moves
# no new money, so the balance genuinely will never increase again, by design.
wait_for_balance_increase() {
  local token="$1" ledger_account_id="$2" baseline="$3" min_increase="$4" what="$5" timeout="${6:-4}"
  local deadline=$(( $(date +%s) + timeout ))
  while true; do
    local avail
    avail=$(get_balance_available "$token" "$ledger_account_id")
    if [ "$avail" -ge "$(( baseline + min_increase ))" ]; then return 0; fi
    if [ "$(date +%s)" -gt "$deadline" ]; then
      warn "  $what did not increase the balance within ${timeout}s - almost always means a prior run already settled it (idempotent replay moves no new money); treat this as a no-op, not a failure"
      return 1
    fi
    sleep 0.5
  done
}

get_or_issue_seed_card() {
  local token="$1" account_id="$2" existing count body raw resp card_id
  existing=$(curl -s "$gateway_base/api/cards" -H "Authorization: Bearer $token")
  count=$(echo "$existing" | jq 'length')
  if [ "$count" -gt 0 ]; then echo "$existing" | jq -r '.[0].id'; return; fi
  body=$(jq -n --arg acc "$account_id" '{accountId:$acc, type:"VIRTUAL", label:"Seed card", pin:"1234"}')
  raw=$(http_call POST "$gateway_base/api/cards" "$token" "$body")
  resp=$(resp_body "$raw")
  card_id=$(echo "$resp" | jq -r '.id')
  say "    issued card $card_id"
  echo "$card_id"
}

get_existing_authorization_count() {
  local token="$1" card_id="$2"
  curl -s "$gateway_base/api/cards/$card_id/authorizations" -H "Authorization: Bearer $token" | jq 'length'
}

invoke_seed_history_for() {
  local token="$1" card_id="$2" existing_count body
  existing_count=$(get_existing_authorization_count "$token" "$card_id")
  if [ "$existing_count" -ge "$history_batch_count" ]; then
    say "    card $card_id already has $existing_count authorizations (>= $history_batch_count) - skipping the spend batch"
    return
  fi
  say "    submitting a $history_batch_count-authorization spend batch on card $card_id..."
  body=$(jq -n --arg cid "$card_id" '{cardId:$cid}')
  curl -s -X POST "$simulator_base/simulator/cards/spend/batch?count=$history_batch_count" \
    -H "Content-Type: application/json" -d "$body" > /dev/null
  say '    advancing the simulator clock so the batch settles...'
  for ((i = 0; i < history_advance_day_count; i++)); do
    curl -s -X POST "$simulator_base/simulator/clock/advance-day" > /dev/null
  done
}

invoke_seed_budget() {
  local token="$1" limit_minor="$2" month_first body raw status
  month_first=$(date +%Y-%m-01)
  body=$(jq -n --argjson lim "$limit_minor" --arg mo "$month_first" '{category:"Groceries", month:$mo, limitMinor:$lim, currency:"EUR"}')
  raw=$(http_call PUT "$gateway_base/api/analytics/budgets" "$token" "$body")
  status=$(resp_status "$raw")
  case "$status" in 200|201|202|204) ;; *) die "budget upsert failed: HTTP $status" ;; esac
}

invoke_seed_vault() {
  local token="$1" card_id="$2" body raw status
  body=$(jq -n --arg cid "$card_id" --argjson goal "$vault_goal_minor" \
    '{name:"Seed round-up vault", currency:"EUR", goalMinor:$goal, roundupEnabled:true, roundupSourceCardId:$cid}')
  raw=$(http_call POST "$gateway_base/api/analytics/vaults" "$token" "$body")
  status=$(resp_status "$raw")
  case "$status" in
    200|201|202) ;;
    409) say '    round-up vault already exists' ;;
    *) die "vault creation failed: HTTP $status" ;;
  esac
}

invoke_seed_transfer() {
  local from_token="$1" source_account_id="$2" target_account_id="$3" target_ledger_account_id="$4" \
        amount_minor="$5" reference="$6" idempotency_key="$7"
  local baseline body raw status
  baseline=$(get_balance_available "$from_token" "$target_ledger_account_id")
  body=$(jq -n --arg src "$source_account_id" --arg tgt "$target_account_id" --argjson amt "$amount_minor" --arg ref "$reference" \
    '{sourceAccountId:$src, targetAccountId:$tgt, amountMinor:$amt, currency:"EUR", reference:$ref}')
  raw=$(http_call POST "$gateway_base/api/payments" "$from_token" "$body" "Idempotency-Key: $idempotency_key")
  status=$(resp_status "$raw")
  case "$status" in 200|202) ;; *) die "transfer '$reference' failed: HTTP $status" ;; esac
  if wait_for_balance_increase "$from_token" "$target_ledger_account_id" "$baseline" "$amount_minor" "transfer '$reference'"; then
    say "    $reference settled"
  else
    say "    $reference already applied in a previous run"
  fi
}

# A quote is one-shot and time-limited (FxQuoteController: a retried request with the same
# Idempotency-Key returns the SAME quote, never a fresh one), so a previously-created-but-never-
# executed quote under a fixed key 409s forever on execute - tolerated the same way the vault's
# 409 is: the quote is either already CONSUMED (nothing to do) or permanently EXPIRED (nothing a
# rerun can do about it either), neither a reason to fail the whole run.
invoke_seed_exchange() {
  local token="$1" sell_currency="$2" buy_currency="$3" sell_minor="$4" buy_ledger_account_id="$5" idempotency_key="$6"
  local baseline body raw status quote_resp quote_id
  baseline=$(get_balance_available "$token" "$buy_ledger_account_id")
  body=$(jq -n --arg sc "$sell_currency" --arg bc "$buy_currency" --argjson sm "$sell_minor" \
    '{sellCurrency:$sc, buyCurrency:$bc, sellMinor:$sm}')
  raw=$(http_call POST "$gateway_base/api/fx/quotes" "$token" "$body" "Idempotency-Key: $idempotency_key")
  status=$(resp_status "$raw"); quote_resp=$(resp_body "$raw")
  case "$status" in 200|201) ;; *) die "fx quote $sell_currency->$buy_currency failed: HTTP $status" ;; esac
  quote_id=$(echo "$quote_resp" | jq -r '.quoteId')
  raw=$(http_call POST "$gateway_base/api/fx/quotes/$quote_id/execute" "$token" "" "Idempotency-Key: $idempotency_key")
  status=$(resp_status "$raw")
  case "$status" in
    200|202) ;;
    409) say "    $sell_currency->$buy_currency quote already consumed or expired from a previous run - skipping"; return ;;
    *) die "fx execute $sell_currency->$buy_currency failed: HTTP $status" ;;
  esac
  if wait_for_balance_increase "$token" "$buy_ledger_account_id" "$baseline" 1 "FX $sell_currency->$buy_currency exchange"; then
    say "    $sell_currency->$buy_currency exchange settled"
  else
    say "    $sell_currency->$buy_currency exchange already applied in a previous run"
  fi
}

assert_zero_sum_invariant() {
  say 'asserting the ledger zero-sum invariant (SUM per entry_id over the real posting table)...'
  local unbalanced
  if ! unbalanced=$(docker compose "${compose[@]}" exec -T postgres psql -U ledger -d ledger -tAc \
      "SELECT entry_id FROM posting GROUP BY entry_id HAVING SUM(CASE WHEN direction='DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0;" 2>&1); then
    die "ledger invariant query failed: $unbalanced"
  fi
  if [ -n "$(echo "$unbalanced" | tr -d '[:space:]')" ]; then
    die "UNBALANCED entry_id(s) found - this is a hard failure regardless of anything else seeding did: $unbalanced"
  fi
  say '  every journal entry sums to zero.'
}

# --- users: four parallel maps rather than an array of objects - bash has no clean way to ------
# nest structured data, and four short maps read at least as clearly as seed.ps1's [ordered]@{}.
emails=(alice@demo.io bob@demo.io carol@demo.io dan@demo.io)
declare -A name=([alice@demo.io]=Alice [bob@demo.io]=Bob [carol@demo.io]=Carol [dan@demo.io]=Dan)
declare -A balance
# Alice's/Bob's EUR figure bakes in the same large headroom a 75-authorization batch against a
# 36-merchant catalogue needs - see seed.ps1's identical comment on this.
balance["alice@demo.io:EUR"]=5235000; balance["alice@demo.io:GBP"]=89050;  balance["alice@demo.io:USD"]=150000
balance["bob@demo.io:EUR"]=5180000;   balance["bob@demo.io:GBP"]=45000;   balance["bob@demo.io:USD"]=95000
balance["carol@demo.io:EUR"]=320000;  balance["carol@demo.io:GBP"]=120000; balance["carol@demo.io:USD"]=50000
balance["dan@demo.io:EUR"]=60000;     balance["dan@demo.io:GBP"]=15000;   balance["dan@demo.io:USD"]=220000
history_emails=(alice@demo.io bob@demo.io)
declare -A budget_limit=([alice@demo.io]=100000 [bob@demo.io]=20000)

declare -A token_of accounts_json card_of

# Keycloak access tokens are short-lived, and this script's later phases (card spend batches,
# transfers, exchanges) run long enough after the initial fetch - especially against a stack
# that's still warming up, or mid-replay after an analytics-rebuild - that a token grabbed at
# the very start can expire before it's used, surfacing as a 401 (or, worse, as "integer
# expression expected" from a count check parsing an empty/error response body). Found live: a
# run immediately after `tasks.sh down && up` plus an analytics-rebuild took long enough that
# the funding phase's tokens had expired by the transfer phase. Re-fetching before each of the
# three remaining multi-step phases is cheap insurance against the same trap recurring.
refresh_tokens() {
  for email in "${emails[@]}"; do
    token_of["$email"]=$(get_access_token "$email" "$demo_password")
  done
}

account_id_of()        { echo "${accounts_json[$1]}" | jq -r --arg c "$2" '.[] | select(.currency==$c) | .id'; }
ledger_account_id_of()  { echo "${accounts_json[$1]}" | jq -r --arg c "$2" '.[] | select(.currency==$c) | .ledgerAccountId'; }

main() {
  cd "$repo"

  invoke_reconcile

  say "funding each user's EUR/GBP/USD wallets (skipped where already funded by a prior run)..."
  for email in "${emails[@]}"; do
    say "  ${name[$email]} ($email)"
    local tok accts
    tok=$(get_access_token "$email" "$demo_password")
    token_of["$email"]="$tok"
    accts=$(wait_for_all_wallets "$tok")
    accounts_json["$email"]="$accts"
    for currency in EUR GBP USD; do
      local lacct
      lacct=$(echo "$accts" | jq -r --arg c "$currency" '.[] | select(.currency==$c) | .ledgerAccountId')
      fund_if_never_funded "$tok" "$lacct" "${balance[$email:$currency]}" "$currency"
    done
  done

  refresh_tokens
  say 'seeding card + spend history for Alice and Bob (~150 authorizations total)...'
  for email in "${history_emails[@]}"; do
    say "  ${name[$email]}"
    local tok eur_account_id cid
    tok="${token_of[$email]}"
    eur_account_id=$(account_id_of "$email" EUR)
    cid=$(get_or_issue_seed_card "$tok" "$eur_account_id")
    card_of["$email"]="$cid"
    invoke_seed_history_for "$tok" "$cid"
  done

  refresh_tokens
  say 'seeding a handful of P2P transfers between the four...'
  invoke_seed_transfer "${token_of[alice@demo.io]}" "$(account_id_of alice@demo.io EUR)" "$(account_id_of carol@demo.io EUR)" \
    "$(ledger_account_id_of carol@demo.io EUR)" 5000 'Rent split' 'seed:p2p:alice@demo.io-to-carol@demo.io:Rent split'
  invoke_seed_transfer "${token_of[bob@demo.io]}" "$(account_id_of bob@demo.io EUR)" "$(account_id_of dan@demo.io EUR)" \
    "$(ledger_account_id_of dan@demo.io EUR)" 3000 'Dinner' 'seed:p2p:bob@demo.io-to-dan@demo.io:Dinner'
  invoke_seed_transfer "${token_of[carol@demo.io]}" "$(account_id_of carol@demo.io EUR)" "$(account_id_of dan@demo.io EUR)" \
    "$(ledger_account_id_of dan@demo.io EUR)" 2000 'Movie tickets' 'seed:p2p:carol@demo.io-to-dan@demo.io:Movie tickets'
  invoke_seed_transfer "${token_of[dan@demo.io]}" "$(account_id_of dan@demo.io EUR)" "$(account_id_of alice@demo.io EUR)" \
    "$(ledger_account_id_of alice@demo.io EUR)" 1500 'Coffee' 'seed:p2p:dan@demo.io-to-alice@demo.io:Coffee'

  refresh_tokens
  say 'seeding several FX exchanges...'
  invoke_seed_exchange "${token_of[alice@demo.io]}" EUR GBP 20000 "$(ledger_account_id_of alice@demo.io GBP)" 'seed:fx:alice@demo.io:EUR-to-GBP'
  invoke_seed_exchange "${token_of[bob@demo.io]}" USD EUR 10000 "$(ledger_account_id_of bob@demo.io EUR)" 'seed:fx:bob@demo.io:USD-to-EUR'
  invoke_seed_exchange "${token_of[carol@demo.io]}" GBP USD 15000 "$(ledger_account_id_of carol@demo.io USD)" 'seed:fx:carol@demo.io:GBP-to-USD'

  say 'seeding exactly two budgets (one comfortable, one breached) and two vaults...'
  for email in "${history_emails[@]}"; do
    invoke_seed_budget "${token_of[$email]}" "${budget_limit[$email]}"
    invoke_seed_vault "${token_of[$email]}" "${card_of[$email]}"
  done

  assert_zero_sum_invariant

  say 'seed complete: alice@demo.io / bob@demo.io / carol@demo.io / dan@demo.io, password "demo".'
}

main "$@"
