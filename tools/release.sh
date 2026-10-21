#!/usr/bin/env bash
#
# Build, sign, verify and publish a Horror Pets release, entirely from this machine.
# The signing key never leaves ~/.horror-pets/ and is never uploaded anywhere (no CI secrets).
#
#   tools/release.sh 0.2.0               real release: tag, push, GitHub Release
#   tools/release.sh 0.2.0 --dry-run     everything except tag / push / release
#
# A real release needs a clean checkout of main that equals origin/main, an authenticated gh,
# and push access to origin. All of that is checked before the build starts.
#
# Flags:
#   --dry-run      Build and verify only. The "must be on main", "tag must not exist" and
#                  "main must equal origin/main" checks (and the gh checks) become warnings, so
#                  this can run from any branch. Push access is only tested in a real release.
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
#   unset PW HP_PW
#
# Needs: JDK 17 (java and keytool), the Android SDK (ANDROID_HOME, local.properties or
# ~/Library/Android/sdk), git, and for a real release an authenticated gh.

set -euo pipefail

KEY_DIR="$HOME/.horror-pets"
KEYSTORE="$KEY_DIR/release.jks"
PROPS="$KEY_DIR/release.properties"

DRY_RUN=0

info() { printf '==> %s\n' "$*"; }
warn() { printf 'WARNING: %s\n' "$*" >&2; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() { sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; /^#$/d'; }

# A check that is fatal in a real release but only a warning in a dry run.
soft_fail() {
  if [ "$DRY_RUN" -eq 1 ]; then
    warn "dry run: would refuse a real release: $*"
  else
    die "$*"
  fi
}

# Everything a real release needs for publishing, checked BEFORE the build and long before the
# point of no return (tagging). Uses $TAG and $DRY_RUN. In a dry run the gh checks only warn and
# the push test is skipped, so a dry run never contacts the remote for writing.
preflight_publish() {
  local out
  if ! command -v gh >/dev/null 2>&1; then
    soft_fail "gh (the GitHub CLI) is not installed or not on PATH; it is needed to create the release"
    return 0
  fi
  if ! gh auth status --hostname github.com >/dev/null 2>&1; then
    soft_fail "gh is not logged in to github.com; run 'gh auth login' first"
    return 0
  fi
  if [ "$DRY_RUN" -eq 1 ]; then
    info "Dry run: not testing push access to origin (a real release does)"
    return 0
  fi
  if ! out="$(git push --dry-run origin "HEAD:refs/tags/$TAG" 2>&1)"; then
    die "cannot push tag $TAG to origin (no write access or credentials?). git said:
$out"
  fi
}

# Prints the keytool to use: the one in $JAVA_HOME when it is set, else the one on PATH. JAVA_HOME
# comes first because on macOS /usr/bin/keytool is only a stub that picks some installed JDK.
find_keytool() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then
    printf '%s' "$JAVA_HOME/bin/keytool"
  elif command -v keytool >/dev/null 2>&1; then
    command -v keytool
  else
    return 1
  fi
}

# Prints the Android SDK root.
find_sdk() {
  local candidate local_dir
  local_dir="$(after_first 'sdk\.dir=' "$ROOT/local.properties" 2>/dev/null || true)"
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$local_dir" \
    "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate/build-tools" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

# Prints the newest release build-tools directory (skips -rc and other previews) that has
# both apksigner and aapt2.
find_build_tools() {
  local sdk="$1" v re='^[0-9]+(\.[0-9]+)*$'
  for v in $(ls -1 "$sdk/build-tools" | sort -V -r); do
    [[ $v =~ $re ]] || continue
    if [ -x "$sdk/build-tools/$v/apksigner" ] && [ -x "$sdk/build-tools/$v/aapt2" ]; then
      printf '%s' "$sdk/build-tools/$v"
      return 0
    fi
  done
  return 1
}

# Prints what follows REGEX on the first line that starts with it, then stops. Reads the files given,
# or stdin. Written with separate -e blocks so it works with both BSD (macOS) and GNU sed.
after_first() {
  local re="$1"
  shift
  sed -n -e "/^$re/{" -e "s/^$re//" -e p -e q -e '}' "$@"
}

# Reads KEY from release.properties without sourcing (executing) the file.
prop() {
  after_first "[[:space:]]*$1[[:space:]]*=[[:space:]]*" "$PROPS" | sed 's/[[:space:]]*$//'
}

# Sourced (for testing the functions above) instead of executed: stop here.
[[ "${BASH_SOURCE[0]}" == "$0" ]] || return 0

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------
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
VERSION_RE='^([0-9]+)\.([0-9]+)\.([0-9]+)$'
[[ $VERSION =~ $VERSION_RE ]] \
  || die "version '$VERSION' must look like MAJOR.MINOR.PATCH, for example 0.2.0"
if [ "$ALLOW_DIRTY" -eq 1 ] && [ "$DRY_RUN" -ne 1 ]; then
  die "--allow-dirty is only accepted together with --dry-run"
fi

# The same limits as versionCodeOf() in app/build.gradle.kts, checked here so a bad version fails
# before the build starts. Components longer than 6 characters are rejected first, which also
# keeps the arithmetic below from overflowing.
MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"
if [ "${#MAJOR}" -gt 6 ] || [ "${#MINOR}" -gt 6 ] || [ "${#PATCH}" -gt 6 ]; then
  die "version '$VERSION' is out of range (versionCode = major*10000 + minor*100 + patch)"
fi
[ $((10#$MAJOR)) -le 209999 ] \
  || die "version '$VERSION': major must be at most 209999 (Android caps versionCode at 2100000000)"
[ $((10#$MINOR)) -lt 100 ] && [ $((10#$PATCH)) -lt 100 ] \
  || die "version '$VERSION': minor and patch must each be below 100 (versionCode = major*10000 + minor*100 + patch)"
EXPECTED_VERSION_CODE=$((10#$MAJOR * 10000 + 10#$MINOR * 100 + 10#$PATCH))
[ "$EXPECTED_VERSION_CODE" -ge 1 ] || EXPECTED_VERSION_CODE=1
TAG="v$VERSION"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------------------
# Preconditions
# ---------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
  info "Checking preconditions for $TAG (dry run)"
else
  info "Checking preconditions for $TAG"
fi

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

# The tag is pushed and the release is built from this commit, so it must be exactly origin/main.
if git fetch --quiet origin main; then
  if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
    soft_fail "HEAD ($(git rev-parse --short HEAD)) is not origin/main ($(git rev-parse --short origin/main)); push or pull main so they match"
  fi
else
  soft_fail "could not fetch origin/main to compare it with HEAD (see the git error above)"
fi

# Everything below is built, verified and tagged at exactly this commit.
COMMIT="$(git rev-parse HEAD)"

[ -f "$KEYSTORE" ] && [ -f "$PROPS" ] || die "signing key not set up: expected $KEYSTORE and $PROPS.
Create them once (see the 'One-time key setup' block in 'tools/release.sh --help', or the README),
and back up $KEY_DIR afterwards."

STORE_PASSWORD="$(prop storePassword)"
KEY_ALIAS="$(prop keyAlias)"
KEY_PASSWORD="$(prop keyPassword)"
[ -n "$STORE_PASSWORD" ] || die "storePassword is missing in $PROPS"
[ -n "$KEY_ALIAS" ] || die "keyAlias is missing in $PROPS"
[ -n "$KEY_PASSWORD" ] || die "keyPassword is missing in $PROPS"

# ---------------------------------------------------------------------------
# Tools
# ---------------------------------------------------------------------------
KEYTOOL="$(find_keytool)" || die "keytool not found; install a JDK 17 and put it on PATH or set JAVA_HOME"
SDK="$(find_sdk)" || die "Android SDK not found; set ANDROID_HOME"
BUILD_TOOLS="$(find_build_tools "$SDK")" \
  || die "no release build-tools with apksigner and aapt2 under $SDK/build-tools"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
info "Using build-tools $(basename "$BUILD_TOOLS")"

# Publishing prerequisites (gh, login, push access) are checked now, not after the build.
preflight_publish

# ---------------------------------------------------------------------------
# Build (tests, lint, signed release APK)
# ---------------------------------------------------------------------------
info "Building $VERSION: clean, release unit tests, release lint, signed assembleRelease"
# The signing variables exist only inside this subshell, so only this one Gradle run sees them.
# No configuration or build cache, so no signing input is cached and every release is a fresh build.
# --no-daemon: the build runs in a single-use daemon that exits with it, so the signing variables do
# not linger in a long-lived daemon's environment (and no other agent's daemon is stopped).
# kotlin.compiler.execution.strategy=in-process keeps the Kotlin compiler out of its own long-lived
# daemon process for the same reason.
(
  export HP_KEYSTORE_PATH="$KEYSTORE"
  export HP_KEYSTORE_PASSWORD="$STORE_PASSWORD"
  export HP_KEY_ALIAS="$KEY_ALIAS"
  export HP_KEY_PASSWORD="$KEY_PASSWORD"
  ./gradlew --no-daemon --no-configuration-cache --no-build-cache \
    -Pkotlin.compiler.execution.strategy=in-process \
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

# minSdk is 22: Android 5.x only understands v1 (JAR) signatures, Android 7+ prefers v2.
case "$SIGNER_OUT" in
  *"Verified using v1 scheme (JAR signing): true"*) ;;
  *) die "the APK is not v1 (JAR) signed, so it would not install on Android 5.x and 6.x" ;;
esac
case "$SIGNER_OUT" in
  *"Verified using v2 scheme (APK Signature Scheme v2): true"*) ;;
  *) die "the APK is not v2 signed (APK Signature Scheme v2)" ;;
esac

# The APK must be signed by *our* key, not a debug key: compare certificate digests.
APK_DIGEST="$(printf '%s\n' "$SIGNER_OUT" | after_first 'Signer #1 certificate SHA-256 digest: ' || true)"
KEYSTORE_DIGEST="$(HP_FP_PASSWORD="$STORE_PASSWORD" "$KEYTOOL" -list -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
  -storepass:env HP_FP_PASSWORD 2>/dev/null \
  | sed -n 's/^Certificate fingerprint (SHA-256): //p' | tr -d ':' | tr 'A-F' 'a-f' || true)"
[ -n "$APK_DIGEST" ] && [ -n "$KEYSTORE_DIGEST" ] \
  || die "could not read the certificate digests to compare (is the alias '$KEY_ALIAS' in $KEYSTORE and the password right?)"
[ "$APK_DIGEST" = "$KEYSTORE_DIGEST" ] \
  || die "the APK is not signed with the key in $KEYSTORE (digest mismatch)"
info "Signed with the release key (SHA-256 $APK_DIGEST)"

info "Checking APK metadata"
BADGING="$("$AAPT2" dump badging "$BUILT_APK")"
PACKAGE_LINE="$(printf '%s\n' "$BADGING" | after_first 'package: ' || true)"
printf 'package: %s\n' "$PACKAGE_LINE"
case "$PACKAGE_LINE" in
  *"versionCode='$EXPECTED_VERSION_CODE'"*) ;;
  *) die "versionCode in the APK is not $EXPECTED_VERSION_CODE" ;;
esac
case "$PACKAGE_LINE" in
  *"versionName='$VERSION'"*) ;;
  *) die "versionName in the APK is not $VERSION" ;;
esac
# (A `printf | grep -q` here would race with pipefail: grep exits early, printf gets SIGPIPE and the
# pipeline reports failure, which would silently skip the check. A plain case has no such problem.)
case $'\n'"$BADGING" in
  *$'\n'application-debuggable*) die "the release APK is debuggable" ;;
esac

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
  printf '    git tag -a %s -m "Horror Pets %s" %s\n' "$TAG" "$VERSION" "$COMMIT"
  printf '    git push origin refs/tags/%s\n' "$TAG"
  printf '    gh release create %s dist/horror-pets-%s.apk --verify-tag --title "Horror Pets %s" --generate-notes\n' \
    "$TAG" "$VERSION" "$VERSION"
  exit 0
fi

# Last look before the point of no return: the tree and HEAD must still be what was built.
[ -z "$(git status --porcelain)" ] || die "the working tree changed during the build; nothing was tagged"
[ "$(git rev-parse HEAD)" = "$COMMIT" ] || die "HEAD moved during the build (now $(git rev-parse --short HEAD), built $COMMIT); nothing was tagged"

APK_Q="$(printf '%q' "$DIST_APK")"
PUSH_CMD="git push origin refs/tags/$TAG"
CREATE_CMD="gh release create $TAG $APK_Q --verify-tag --title \"Horror Pets $VERSION\" --generate-notes"

info "Tagging $TAG at $COMMIT"
git tag -a "$TAG" -m "Horror Pets $VERSION" "$COMMIT"

info "Pushing $TAG to origin"
git push origin "refs/tags/$TAG" || die "pushing $TAG failed; the tag exists only locally and no release was created.
Fix the problem, then run both:
  $PUSH_CMD
  $CREATE_CMD
Or abandon this release with: git tag -d $TAG"

info "Creating GitHub Release $TAG"
gh release create "$TAG" "$DIST_APK" --verify-tag --title "Horror Pets $VERSION" --generate-notes \
  || die "$TAG is pushed, but creating the GitHub Release failed. See what exists with:
  gh release view $TAG
If there is no release, run:
  $CREATE_CMD
If the release exists without the APK, run:
  gh release upload $TAG $APK_Q --clobber"

info "Released $TAG"
