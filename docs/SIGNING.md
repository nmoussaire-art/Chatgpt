# Release signing

LoopGuard releases are signed with a dedicated 4096-bit RSA key that is valid until 2056.
Every future version **must** be signed with this same key, otherwise Android will refuse to
install it as an update over an existing LoopGuard.

## The key

| | |
|---|---|
| File | `signing/loopguard-release.jks` (PKCS#12) |
| Alias | `loopguard` |
| Algorithm | RSA 4096, SHA384withRSA |
| Certificate | `CN=LoopGuard, OU=LoopGuard, O=LoopGuard, C=AE` |
| Valid until | 21 July 2056 |
| SHA-256 fingerprint | `9E:C0:24:2F:93:43:5E:96:6A:60:1B:DD:04:84:05:08:BF:50:82:0F:42:F3:2F:98:41:43:22:1D:47:B9:E3:44` |

Verify any LoopGuard APK against that fingerprint:

```bash
apksigner verify --print-certs LoopGuard-v2.0.0.apk
```

## What is committed and what is not

The keystore and its passwords are **never** committed. `.gitignore` blocks:

```
*.jks
*.keystore
*.p12
keystore.properties
signing/
```

Only the *public* SHA-256 fingerprint above is in the repository, so that anyone can verify
a downloaded APK without having the key.

## Building a signed release locally

Create `keystore.properties` in the repository root (it is git-ignored):

```properties
storeFile=signing/loopguard-release.jks
storePassword=<the store password>
keyAlias=loopguard
keyPassword=<the key password>
```

Put the keystore at `signing/loopguard-release.jks`, then:

```bash
./gradlew :app:assembleRelease
```

The output is `app/build/outputs/apk/release/app-release.apk`.

If the keystore or the properties are missing, the build still succeeds but produces an
**unsigned** release APK, which Android will not install. This is intentional: the build
fails loudly at install time rather than silently shipping a debug-signed artefact.

## Building a signed release in CI

`.github/workflows/build-apk.yml` reads the same values from environment variables, which
are populated from GitHub Secrets. Add these four secrets under
**Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `LOOPGUARD_KEYSTORE_BASE64` | `base64 -w0 signing/loopguard-release.jks` |
| `LOOPGUARD_KEYSTORE_PASSWORD` | the store password |
| `LOOPGUARD_KEY_ALIAS` | `loopguard` |
| `LOOPGUARD_KEY_PASSWORD` | the key password |

The workflow decodes the keystore into `signing/`, builds, verifies the signature with
`apksigner`, and attaches the APK to a GitHub Release when one is published.

## Rules for future releases

1. **Never lose this key.** Keep an offline copy. If it is lost, no future build can update
   an installed LoopGuard — every user would have to uninstall and lose their data.
2. **Never commit it**, and never paste the passwords into an issue, a pull request or a
   build log.
3. **Never fall back to the debug key.** That is precisely what forced the one-time
   uninstall between 1.0 and 2.0.
4. Increment `versionCode` in `app/build.gradle.kts` for every release. Android will not
   install an APK with a version code lower than the one already installed.
