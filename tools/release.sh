#!/usr/bin/env bash
# Publish a GitHub release of the signed ReadyNode app. Usage: tools/release.sh v3.0
# Put the signed APK at ReadyNode.apk in the repo root first (git ignores it). The tag is made on GitHub at this
# copy's commit, which must match GitHub's main.
set -euo pipefail
TAG=${1:?usage: tools/release.sh vX.Y}
cd "$(dirname "$0")/.."
[ -f ReadyNode.apk ] || { echo "Missing ReadyNode.apk: put the signed app here first." >&2; exit 1; }
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner)
git fetch -q origin
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "This copy isn't the same as GitHub's main: git pull (or push) first." >&2; exit 1; }
APP=$(grep -o 'versionName "[^"]*"' app/build.gradle | cut -d'"' -f2)
[ "v$APP" = "$TAG" ] || echo "Note: the app says version $APP, the tag is $TAG." >&2
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
cp ReadyNode.apk "$OUT/"
echo "ReadyNode $APP, built from $(git rev-parse --short HEAD)" >"$OUT/VERSION"
(cd "$OUT" && sha256sum ReadyNode.apk VERSION >SHA256SUMS)
gh release create "$TAG" --repo "$REPO" --target "$(git rev-parse HEAD)" --title "ReadyNode ${TAG#v}" \
	--notes "ReadyNode $APP. Download ReadyNode.apk and open it on an Android phone (Android 8 or newer)." "$OUT"/*
echo "Published $TAG."
