#!/usr/bin/env bash
# Day 10: send the same questions to the RAG chain and the Koog agent and print
# answer, sources/tool calls and latency side by side. Needs the app on :8080 with docs ingested.
# Usage: scripts/compare-rag-vs-agent.sh [base-url]
set -euo pipefail
BASE="${1:-http://localhost:8080}"

questions=(
  "Do subclasses of a sealed class have to be declared inside the sealed class?"
  "What is the difference between a data class and a sealed class? When would I use each?"
  "How do I safely call a method on a nullable String and fall back to a default value?"
  "What is the difference between launch and async in coroutines?"
  "Hi, who are you?"
)

ask() { # $1 = path, $2 = question -> prints body, then latency on the last line
  local body; body=$(jq -n --arg q "$2" '{question: $q}')
  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' -d "$body" -w '\n%{time_total}'
}

for q in "${questions[@]}"; do
  echo "=================================================================="
  echo "Q: $q"
  rag=$(ask /kotlin/chat/ask "$q");   rag_t=$(tail -n1 <<<"$rag");   rag_json=$(sed '$d' <<<"$rag")
  agent=$(ask /kotlin/agent/ask "$q"); agent_t=$(tail -n1 <<<"$agent"); agent_json=$(sed '$d' <<<"$agent")
  echo "--- RAG   (${rag_t}s) sources: $(jq -c '[.sources[].source]' <<<"$rag_json")"
  jq -r '.answer' <<<"$rag_json"
  echo "--- Agent (${agent_t}s) tool calls: $(jq -c '[.toolCalls[].args]' <<<"$agent_json")"
  jq -r '.answer' <<<"$agent_json"
done
