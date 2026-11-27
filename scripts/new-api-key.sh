#!/usr/bin/env bash
# F10: create an API key for one user. Prints the key ONCE (give it to the client) and the env vars for the server,
# which only ever stores the SHA-256. Usage: scripts/new-api-key.sh <user-id> [index] [name]
set -euo pipefail
USER_ID=${1:?usage: new-api-key.sh <user-id> [index] [name]}
I=${2:-0}
NAME=${3:-$USER_ID}
KEY="rag_$(head -c 32 /dev/urandom | base64 | tr '+/' '-_' | tr -d '=\n')"
HASH=$(printf %s "$KEY" | sha256sum | cut -d' ' -f1)
echo "API key for $USER_ID (store it in the client, it is not saved anywhere):"
echo "  $KEY"
echo
echo "Server env:"
echo "  RAG_SECURITY_API_KEYS_${I}_USER_ID=$USER_ID"
echo "  RAG_SECURITY_API_KEYS_${I}_SHA256=$HASH"
echo "  RAG_SECURITY_API_KEYS_${I}_NAME=$NAME"
