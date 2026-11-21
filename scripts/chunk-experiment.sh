#!/usr/bin/env bash
# F3: re-ingest the docs with different TokenTextSplitter settings and compare what /docs/search returns.
# Needs the app on :8080 and jq. Restores the configured settings (rag.chunk-size / rag.chunk-overlap) at the end.
# Usage: scripts/chunk-experiment.sh [base-url] ["size:overlap" ...]   default settings: 800:0 64:16 32:8 16:0
set -euo pipefail
BASE="${1:-http://localhost:8080}"; shift || true
settings=("${@:-800:0}"); [[ $# -eq 0 ]] && settings=("800:0" "64:16" "32:8" "16:0")

queries=(
  "What is the difference between launch and async?"
  "How do I fall back to a default value when something is null?"
  "Can subclasses of a sealed class be declared in another file?"
  "What does the copy function of a data class do?"
)

for s in "${settings[@]}"; do
  size=${s%%:*}; overlap=${s##*:}
  echo "=================================================================="
  echo "chunkSize=$size chunkOverlap=$overlap"
  chunks=$(curl -s -X POST "$BASE/kotlin/docs/ingest?chunkSize=$size&chunkOverlap=$overlap")
  echo "chunks per file: $(jq -c . <<<"$chunks")  total: $(jq '[.[]] | add' <<<"$chunks")"
  for q in "${queries[@]}"; do
    hits=$(curl -s -G "$BASE/kotlin/docs/search" --data-urlencode "q=$q" -d k=3)
    printf '%-66s top1=%s  top3=%s  avgLen=%s\n' "$q" \
      "$(jq -r '.[0] | "\(.source)#\(.chunkIndex) \(.score*1000|round/1000)"' <<<"$hits")" \
      "$(jq -c '[.[] | .score*1000|round/1000]' <<<"$hits")" \
      "$(jq '[.[] | .text | length] | add / length | round' <<<"$hits")"
  done
done
echo "=================================================================="
echo "restoring configured chunk settings: $(curl -s -X POST "$BASE/kotlin/docs/ingest" | jq -c .)"
