#!/usr/bin/env bash
#
# Build, sign, verify and publish a Horror Pets release, entirely from this machine.
# The signing key never leaves ~/.horror-pets/ and is never uploaded anywhere (no CI secrets).
#
#   tools/release.sh 0.2.0               real release: tag, push, GitHub Release
#   tools/release.sh 0.2.0 --dry-run     everything except tag / push / release
#
# Flags:
#   --dry-run      Build and verify only. The "must be on main", "tag must not exist" and
#                  "main must be pushed" checks become warnings so this can run from any branch.
#   --allow-dirty  With --dry-run only: tolerate uncommitted changes (a real release never does).
#   -h, --help     Show this help.
#
# One-time key setup (back up ~/.horror-pets/ afterwards: lose the key and installed copies can no
# longer be updated, because Android only accepts updates signed with the same key):
#
#   umask 077 && mkdir -p ~/.horror-pets && chmod 700 ~/.horror-pets
#   PW="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40)"
#   printf 'storePassword=%s\nkeyAlias=horrorpets\nkeyPassword=%s\n' "$PW" "$PW" > ~/.horror-pets/release.properties
#   HP_PW="$PW" keytool -genkeypair -v -keystore ~/.horror-pets/release.jks -storetype PKCS12 \
#     -alias horrorpets -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Horror Pets, O=Superfun Team" \
#     -storepass:env HP_PW -keypass:env HP_PW
#   chmod 600 ~/.horror-pets/release.jks ~/.horror-pets/release.properties
#
# Needs: JDK 17, the Android SDK (ANDROID_HOME, local.properties or ~/Library/Android/sdk),
# git, and (for a real release) an authenticated gh.

set -euo pipefail

KEY_DIR="$HOME/.horror-pets"
KEYSTORE="$KEY_DIR/release.jks"
PROPS="$KEY_DIR/release.properties"

info() { printf '==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; /^#$/d'; }

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------
DRY_RUN=0
ALLOW_DIRTY=0
VERSION=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    --allow-dirty) ALLOW_DIRTY=1 ;;
    -h | --help) usage; exit 0 ;;
    -*) die "unknown option '$arg' (see --help)" ;;
    *)
      [ -z "$VERSION" ] || die "more than one version given ('$VERSION' and '$arg')"
      VERSION="$arg"
      ;;
  esac
done

[ -n "$VERSION" ] || { usage >&2; exit 2; }
case "$VERSION" in
  v*) die "give the bare version (0.2.0), not '$VERSION'; the tag gets its 'v' automatically" ;;
esac
printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || die "version '$VERSION' must look like MAJOR.MINOR.PATCH, for example 0.2.0"
if [ "$ALLOW_DIRTY" -eq 1 ] && [ "$DRY_RUN" -ne 1 ]; then
  die "--allow-dirty is only accepted together with --dry-run"
fi

MAJOR="${VERSION%%.*}"
REST="${VERSION#*.}"
MINOR="${REST%%.*}"
PATCH="${REST#*.}"
[ $((10#$MINOR)) -lt 100 ] && [ $((10#$PATCH)) -lt 100 ] \
  || die "version '$VERSION': minor and patch must each be below 100 (versionCode = major*10000 + minor*100 + patch)"
EXPECTED_VERSION_CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))
[ "$EXPECTED_VERSION_CODE" -ge 1 ] || EXPECTED_VERSION_CODE=1
TAG="v$VERSION"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A check that is fatal in a real release but only a warning in a dry run.
soft_fail() {
  if [ "$DRY_RUN" -eq 1 ]; then
    warn "dry run: would refuse a real release: $*"
  else
    die "$*"
  fi
}

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------
info "Checking preconditions for $TAG$([ "$DRY_RUN" -eq 1 ] && echo ' (dry run)')"

if [ -n "$(git status --porcelain)" ]; then
  if [ "$ALLOW_DIRTY" -eq 1 ]; then
    warn "working tree is not clean; continuing because of --allow-dirty (dry run only)"
  else
    die "working tree is not clean; commit or stash your changes first"
  fi
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
[ "$BRANCH" = "main" ] || soft_fail "current branch is '$BRANCH', releases are cut from 'main'"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  soft_fail "tag $TAG already exists locally"
fi

set +e
REMOTE_TAG_OUT="$(git ls-remote --exit-code --tags origin "refs/tags/$TAG" 2>&1)"
REMOTE_TAG_RC=$?
set -e
case "$REMOTE_TAG_RC" in
  0) soft_fail "tag $TAG already exists on origin" ;;
  2) ;; # reachable, tag not present: what we want
  *) soft_fail "could not check origin for tag $TAG: $REMOTE_TAG_OUT" ;;
esac

# The tag will be pushed, so the commit it points at must already be part of origin/main.
if git fetch --quiet origin main 2>/dev/null; then
  git merge-base --is-ancestor HEAD origin/main \
    || soft_fail "HEAD ($(git rev-parse --short HEAD)) is not on origin/main yet; push main first"
else
  soft_fail "could not fetch origin/main to check that HEAD is pushed"
fi

[ -f "$KEYSTORE" ] && [ -f "$PROPS" ] || die "signing key not set up: expected $KEYSTORE and $PROPS.
Create them once (see the 'One-time key setup' block at the top of this script, or the README),
and back up $KEY_DIR afterwards."

# Reads KEY from release.properties without sourcing (executing) the file.
prop() {
  sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" "$PROPS" | head -n 1 | sed 's/[[:space:]]*$//'
}
STORE_PASSWORD="$(prop storePassword)"
KEY_ALIAS="$(prop keyAlias)"
KEY_PASSWORD="$(prop keyPassword)"
[ -n "$STORE_PASSWORD" ] || die "storePassword is missing in $PROPS"
[ -n "$KEY_ALIAS" ] || die "keyAlias is missing in $PROPS"
[ -n "$KEY_PASSWORD" ] || die "keyPassword is missing in $PROPS"

# ---------------------------------------------------------------------------
# Android SDK tools
# ---------------------------------------------------------------------------
find_sdk() {
  local candidate local_dir
  local_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | head -n 1)"
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$local_dir" \
    "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate/build-tools" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}
SDK="$(find_sdk)" || die "Android SDK not found; set ANDROID_HOME"
BUILD_TOOLS=""
for v in $(ls -1 "$SDK/build-tools" | sort -V -r); do
  if [ -x "$SDK/build-tools/$v/apksigner" ] && [ -x "$SDK/build-tools/$v/aapt2" ]; then
    BUILD_TOOLS="$SDK/build-tools/$v"
    break
  fi
done
[ -n "$BUILD_TOOLS" ] || die "no build-tools with apksigner and aapt2 under $SDK/build-tools"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
info "Using build-tools $(basename "$BUILD_TOOLS")"

# ---------------------------------------------------------------------------
# Build (tests, lint, signed release APK)
# ---------------------------------------------------------------------------
info "Building $VERSION: clean, release unit tests, release lint, signed assembleRelease"
# The signing variables exist only inside this subshell, so only this one Gradle run sees them.
# Configuration and build caches are off so no signing input is cached and every release is a fresh build.
(
  export HP_KEYSTORE_PATH="$KEYSTORE"
  export HP_KEYSTORE_PASSWORD="$STORE_PASSWORD"
  export HP_KEY_ALIAS="$KEY_ALIAS"
  export HP_KEY_PASSWORD="$KEY_PASSWORD"
  ./gradlew --no-configuration-cache --no-build-cache \
    clean :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease \
    "-PversionName=$VERSION"
)

BUILT_APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$BUILT_APK" ] || die "expected a signed APK at $BUILT_APK but it is missing (an unsigned build is named app-release-unsigned.apk)"

# ---------------------------------------------------------------------------
# Verify signature and metadata
# ---------------------------------------------------------------------------
info "Verifying the signature"
SIGNER_OUT="$("$APKSIGNER" verify --verbose --print-certs "$BUILT_APK")" \
  || die "apksigner verify failed for $BUILT_APK"
# --verbose also reports every unsigned META-INF entry in the v1 section; that is noise, so hide it.
printf '%s\n' "$SIGNER_OUT" | grep -v '^WARNING: META-INF/' || true

# The APK must be signed by *our* key, not a debug key: compare certificate digests.
APK_DIGEST="$(printf '%s\n' "$SIGNER_OUT" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)"
KEYSTORE_DIGEST="$(HP_FP_PASSWORD="$STORE_PASSWORD" keytool -list -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
  -storepass:env HP_FP_PASSWORD 2>/dev/null \
  | sed -n 's/^Certificate fingerprint (SHA-256): //p' | tr -d ':' | tr 'A-F' 'a-f')"
[ -n "$APK_DIGEST" ] && [ -n "$KEYSTORE_DIGEST" ] || die "could not read the certificate digests to compare"
[ "$APK_DIGEST" = "$KEYSTORE_DIGEST" ] \
  || die "the APK is not signed with the key in $KEYSTORE (digest mismatch)"
info "Signed with the release key (SHA-256 $APK_DIGEST)"

info "Checking APK metadata"
BADGING="$("$AAPT2" dump badging "$BUILT_APK")"
PACKAGE_LINE="$(printf '%s\n' "$BADGING" | sed -n 's/^package: //p' | head -n 1)"
printf 'package: %s\n' "$PACKAGE_LINE"
case "$PACKAGE_LINE" in
  *"versionCode='$EXPECTED_VERSION_CODE'"*) ;;
  *) die "versionCode in the APK is not $EXPECTED_VERSION_CODE" ;;
esac
case "$PACKAGE_LINE" in
  *"versionName='$VERSION'"*) ;;
  *) die "versionName in the APK is not $VERSION" ;;
esac
if printf '%s\n' "$BADGING" | grep -q '^application-debuggable'; then
  die "the release APK is debuggable"
fi

# ---------------------------------------------------------------------------
# Stage the artifact
# ---------------------------------------------------------------------------
mkdir -p "$ROOT/dist"
DIST_APK="$ROOT/dist/horror-pets-$VERSION.apk"
cp "$BUILT_APK" "$DIST_APK"
info "Wrote dist/horror-pets-$VERSION.apk ($(wc -c <"$DIST_APK" | tr -d ' ') bytes)"
info "SHA-256 $(shasum -a 256 "$DIST_APK" | cut -d' ' -f1)"

# ---------------------------------------------------------------------------
# Publish
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  info "Dry run: stopping before publishing. A real release would now run:"
  printf '    git tag -a %s -m "Horror Pets %s"\n' "$TAG" "$VERSION"
  printf '    git push origin %s\n' "$TAG"
  printf '    gh release create %s dist/horror-pets-%s.apk --verify-tag --title "Horror Pets %s" --generate-notes\n' \
    "$TAG" "$VERSION" "$VERSION"
  exit 0
fi

info "Tagging $TAG"
git tag -a "$TAG" -m "Horror Pets $VERSION"

info "Pushing $TAG to origin"
git push origin "$TAG" || die "push failed; the tag exists locally. Fix the problem and rerun 'git push origin $TAG', or delete it with 'git tag -d $TAG'"

info "Creating GitHub Release $TAG"
gh release create "$TAG" "$DIST_APK" --verify-tag --title "Horror Pets $VERSION" --generate-notes \
  || die "the tag is pushed but the release was not created; rerun: gh release create $TAG dist/horror-pets-$VERSION.apk --verify-tag --title \"Horror Pets $VERSION\" --generate-notes"

info "Released $TAG"
