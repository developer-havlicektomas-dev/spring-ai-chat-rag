#!/usr/bin/env bash
# F9: AI Study Guide spike demo. Needs the app running with RAG_STUDY_DEV_PLAN_SWITCH=true (for the PRO part), jq, curl.
#   SPRING_DOCKER_COMPOSE_ENABLED=false RAG_STUDY_DEV_PLAN_SWITCH=true ./gradlew bootRun
#   scripts/study-guide-demo.sh [base-url]
set -euo pipefail
BASE=${1:-http://localhost:8080}/kotlin/study
RUN=$(date +%s)
FREE=demo-free-$RUN
PRO=demo-pro-$RUN
NOTE=$(cat <<'TXT'
Biology notes - Photosynthesis (lecture 4)

Photosynthesis is the process in which plants, algae and some bacteria convert light energy into chemical energy stored in glucose. It takes place in the chloroplasts, which contain the green pigment chlorophyll.

Overall equation: 6 CO2 + 6 H2O + light energy -> C6H12O6 + 6 O2.

Stage 1: the light-dependent reactions happen in the thylakoid membranes. Water is split (photolysis), which releases oxygen as a by-product. The energy from light is stored in ATP and NADPH.

Stage 2: the Calvin cycle (light-independent reactions) happens in the stroma. The enzyme RuBisCO fixes carbon dioxide, and ATP and NADPH from stage 1 are used to build glucose.

Limiting factors: light intensity, CO2 concentration and temperature. If one factor is in short supply, the rate of photosynthesis stops increasing even when the others rise.
TXT
)

# $1 user, $2 label, $3 jq filter for the request body (gets $note), $4 jq filter for the output
call() {
  local user=$1 label=$2 body=$3 show=$4
  local out; out=$(mktemp)
  local meta; meta=$(jq -n --arg note "$NOTE" "$body" | curl -s -o "$out" -w '%{http_code} %{time_total}' \
    -H 'Content-Type: application/json' -H "X-User-Id: $user" -d @- "$BASE/generate")
  echo "== $label -> HTTP ${meta% *} in ${meta#* }s"
  jq -c "$show" "$out" 2>/dev/null || cat "$out"; echo
  rm -f "$out"
}
SUMMARY='if .code then . else {answerType, grounded, chunks, windows, quiz: (.quiz|length), dropped: .validation.droppedQuestions, flashcards: (.flashcards|length?), usage, inputTokens, outputTokens, latencyMs, firstQuestion: .quiz[0]} end'

echo "## FREE user $FREE"
curl -s -H "X-User-Id: $FREE" "$BASE/usage" | jq -c '{plan, usedToday, dailyLimit}'
call "$FREE" "free: explanation + 3-question quiz" '{text:$note, title:"Photosynthesis", quizSize:3}' "$SUMMARY"
call "$FREE" "free: 10 questions (limit 5)"       '{text:$note, quizSize:10}' '.'
call "$FREE" "free: flashcards (PRO feature)"     '{text:$note, features:["FLASHCARDS"]}' '.'
call "$FREE" "free: HARD difficulty (PRO)"        '{text:$note, difficulty:"HARD"}' '.'
call "$FREE" "free: explanation only"             '{text:$note, features:["EXPLANATION"]}' '{answerType, quiz: (.quiz|length), concepts: (.explanation.keyConcepts|length), usage, latencyMs}'
call "$FREE" "free: insufficient note -> labeled fallback" '{text:"milk, eggs, bread, call mum", title:"Photosynthesis", quizSize:3}' '{answerType, grounded, insufficientReason, explanation, quiz: (.quiz|length), usage, latencyMs}'
call "$FREE" "free: 4th generation today"         '{text:$note, quizSize:2}' '.'

echo "## PRO user $PRO"
curl -s -X PUT -H 'Content-Type: application/json' -H "X-User-Id: $PRO" -d '{"plan":"pro"}' "$BASE/plan" | jq -c '{plan, usedToday, dailyLimit}'
call "$PRO" "pro: 8 HARD questions + flashcards, in Czech" \
  '{text:$note, title:"Fotosyntéza", language:"cs", quizSize:8, difficulty:"HARD", features:["EXPLANATION","QUIZ","FLASHCARDS"]}' \
  "$SUMMARY + {cards: [.flashcards[]?.front]}"
