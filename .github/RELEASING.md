# Releasing Aide

The `Release` workflow (`.github/workflows/release.yml`) builds a signed APK
and publishes a GitHub Release. Triggers:

- Push a tag matching `v[0-9]+.[0-9]+.[0-9]+*` (e.g. `v1.2.3`, `v1.2.3-rc1`).
- Manual `workflow_dispatch` from the Actions tab with a `version` input.
  Creates the `v<version>` tag at the workflow SHA and publishes the Release.

Must run from a `v*` tag or one of `main` / `release` / `release/*`.

## One-time setup

Generate the signing keystore on a trusted local machine:

```bash
keytool -genkey -v \
  -keystore aide-release.jks \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -alias aide \
  -storepass '<STORE_PASS>' -keypass '<KEY_PASS>' \
  -dname 'CN=Aide,O=Swaptr,C=IN'

base64 -w0 aide-release.jks > aide-release.jks.base64
```

Back up `aide-release.jks` and both passwords to a password manager. Losing
them means existing users can never upgrade.

Add these secrets under **Settings → Secrets and variables → Actions**:

| Secret | Required | Value |
| --- | --- | --- |
| `AIDE_KEYSTORE_BASE64` | yes | contents of `aide-release.jks.base64` |
| `AIDE_KEYSTORE_PASSWORD` | yes | `<STORE_PASS>` |
| `AIDE_KEY_ALIAS` | yes | `aide` |
| `AIDE_KEY_PASSWORD` | yes | `<KEY_PASS>` |
| `GRADLE_CACHE_ENCRYPTION_KEY` | optional | `openssl rand -base64 16` — encrypts Gradle build cache |

`GITHUB_TOKEN` is provided automatically.

## Cut a release

Either push a tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

…or trigger manually: Actions → **Release** → **Run workflow** → enter
`0.1.0`. The workflow creates the `v0.1.0` tag at the workflow SHA.

Tag reuse is rejected — the publish step aborts if a Release with that tag
already exists. Bump the version (or delete the existing Release) and rerun.

The workflow:

1. Derives `versionName` from the tag and `versionCode` from semver core
   (`MAJOR*1_000_000 + MINOR*1_000 + PATCH`, each capped at 999). Same tag →
   same `versionCode`. Pre-release tags (`v1.2.3-rc1`) share a `versionCode`
   with `v1.2.3`; do not promote a pre-release APK to Play production.
2. Validates the Gradle wrapper.
3. Runs `:app:testReleaseUnitTest` and `:app:lintRelease`.
4. Assembles a signed `arm64-v8a` APK.
5. Verifies the APK with `apksigner verify` (`--min-sdk 31 --max-sdk 36`).
6. Writes `SHA256SUMS.txt` and `mapping-<version>.txt`.
7. Generates SLSA build provenance attestations.
8. Uploads everything as a workflow artifact.
9. Publishes a GitHub Release with all assets attached.
