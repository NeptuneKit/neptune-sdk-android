#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/publish-maven-central-local.sh [--env-file <path>] [--version <x.y.z>] [--dry-run]

Examples:
  scripts/publish-maven-central-local.sh --dry-run
  scripts/publish-maven-central-local.sh --version 1.2.3
  scripts/publish-maven-central-local.sh --env-file .env.maven-central
EOF
}

env_file=".env.maven-central"
dry_run="false"
version_name=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      env_file="${2:-}"
      shift 2
      ;;
    --version)
      version_name="${2:-}"
      shift 2
      ;;
    --dry-run)
      dry_run="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -f "${env_file}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${env_file}"
  set +a
fi

if [[ -z "${version_name}" ]]; then
  version_name="${VERSION_NAME:-}"
fi

if [[ -z "${version_name}" ]]; then
  echo "Missing version. Use --version or set VERSION_NAME in env file." >&2
  exit 1
fi

if [[ "${dry_run}" == "true" ]]; then
  ./gradlew :sdk:publishToMavenLocal -PVERSION_NAME="${version_name}"
  exit 0
fi

required_vars=(
  MAVEN_CENTRAL_USERNAME
  MAVEN_CENTRAL_PASSWORD
  MAVEN_SIGNING_KEY
  MAVEN_SIGNING_PASSWORD
)

for var in "${required_vars[@]}"; do
  if [[ -z "${!var:-}" ]]; then
    echo "Missing required variable: ${var}" >&2
    exit 1
  fi
done

if [[ -z "${MAVEN_CENTRAL_NAMESPACE:-}" ]]; then
  echo "Missing required variable: MAVEN_CENTRAL_NAMESPACE" >&2
  exit 1
fi

ORG_GRADLE_PROJECT_MAVEN_CENTRAL_USERNAME="${MAVEN_CENTRAL_USERNAME}" \
ORG_GRADLE_PROJECT_MAVEN_CENTRAL_PASSWORD="${MAVEN_CENTRAL_PASSWORD}" \
ORG_GRADLE_PROJECT_signingKey="${MAVEN_SIGNING_KEY}" \
ORG_GRADLE_PROJECT_signingPassword="${MAVEN_SIGNING_PASSWORD}" \
./gradlew :sdk:publish -PVERSION_NAME="${version_name}"

auth="$(printf '%s:%s' "${MAVEN_CENTRAL_USERNAME}" "${MAVEN_CENTRAL_PASSWORD}" | base64 | tr -d '\n')"

curl --fail-with-body --request POST \
  --url "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/${MAVEN_CENTRAL_NAMESPACE}" \
  --header "Authorization: Bearer ${auth}" \
  --header "Accept: application/json"

echo "Maven Central release publish completed for version ${version_name}"
