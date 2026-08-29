#!/usr/bin/env bash
# Publishes a new version: bumps versionCode/versionName, builds the signed release
# APKs locally to verify, then commits + tags + pushes so the GitHub CD workflow
# publishes the GitHub Release (which the in-app updater picks up).
#
# Usage: ./release.sh <version>     e.g. ./release.sh 1.0.1
set -euo pipefail

VERSION="${1:?Usage: ./release.sh <version>  (e.g. ./release.sh 1.0.1)}"

if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
  echo "Error: version must look like 1.0.1" >&2
  exit 1
fi

if [ ! -f my-upload-key.jks ]; then
  echo "Error: my-upload-key.jks not found. Restore it from your backup (it is gitignored)." >&2
  exit 1
fi
if [ ! -f my-upload-key-password.txt ]; then
  echo "Error: my-upload-key-password.txt not found." >&2
  exit 1
fi

echo "== Bumping version to $VERSION =="
for file in app/build.gradle.kts apptv/build.gradle.kts; do
  OLD_CODE=$(grep -oE 'versionCode = [0-9]+' "$file" | grep -oE '[0-9]+')
  NEW_CODE=$((OLD_CODE + 1))
  perl -0pi -e "s/versionCode = [0-9]+/versionCode = $NEW_CODE/; s/versionName = \"[^\"]*\"/versionName = \"$VERSION\"/" "$file"
  echo "  $file -> versionCode $NEW_CODE, versionName \"$VERSION\""
done

echo "== Building signed release APKs locally (verification) =="
PASS=$(cat my-upload-key-password.txt)
KEYSTORE_PATH="$(pwd)/my-upload-key.jks" STORE_PASSWORD="$PASS" KEY_PASSWORD="$PASS" \
  ./gradlew :app:assembleRelease :apptv:assembleRelease
ls -la app/build/outputs/apk/release/app-release.apk apptv/build/outputs/apk/release/apptv-release.apk

echo "== Committing and tagging v$VERSION =="
git add app/build.gradle.kts apptv/build.gradle.kts
git commit -m "Bump version to $VERSION"
git tag "v$VERSION"
git push origin main
git push origin "v$VERSION"

echo
echo "Done. The GitHub CD workflow will build and publish release v$VERSION:"
echo "  https://github.com/Jordigb44/M3U-Editor-y-Reproductor/releases"
echo "The apps will then offer the update automatically."
