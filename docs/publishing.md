# Maven Central publishing

The artifact is `io.hivekit:client-android`. The default development version is
`0.1.0-SNAPSHOT`; `"-PreleaseVersion=0.1.0"` selects the first release.
This configuration uses the Central Publisher Portal, the Vanniktech publishing
plugin 0.36.0 (compatible with Gradle 8), in-memory PGP signing and Dokka HTML API
documentation in the `javadoc` JAR.

No release has been uploaded by this setup. Normal builds and local Maven
publications require no Portal account or signing key. Central upload tasks
are enabled only with `-PcentralRelease=true` and an explicit non-SNAPSHOT version.
The default workflow stages a release for manual publishing in the Portal.

## Configured publisher

Set up on 22 October 2026:

- Namespace `io.hivekit` is verified in the Sonatype organization **Hivekit**.
- The `hivekit.io` ownership TXT record is installed in Route 53, alongside the
  existing TXT values.
- The Portal token **client-android GitHub Actions** expires on **22 October 2027**.
- All four secrets below are installed in the
  [GitHub `maven-central` environment](https://github.com/hivekit/client-android/settings/environments/25050255896/edit).
- The RSA 4096 signing key is **Hivekit Android SDK Releases**, fingerprint
  `3F09E18FDE4062F976A6BB0BC4F6237F23E3AA97`, expiring on **21 October 2028**.
  Its public key is available from `keyserver.ubuntu.com`.
- The encrypted private key, revocation certificate and a Windows-protected
  recovery passphrase are kept outside the checkout under
  `%USERPROFILE%\.hivekit\publishing\client-android`. That folder contains a
  `README.txt` and `Set-SigningEnvironment.ps1` for local recovery. The DPAPI
  passphrase backup is tied to this Windows account and machine; keep a portable
  backup in your team's secure credential store.

Renew the Portal token before its expiry and update both Maven Central secrets.
Extend or replace the PGP key before its expiry and publish the updated public
key. Keep all private material outside source control.

Before the first release, commit and push the reviewed SDK and release workflow
so the workflow is available on GitHub. Account setup does not publish a package.

## Account setup

1. Create or use a [Central Portal account](https://central.sonatype.com/).
2. Ensure that account has publishing access to the `io.hivekit` namespace.
   If the namespace is already registered, have its organization administrator
   grant access. Otherwise, register `io.hivekit` in the Portal and verify
   ownership of `hivekit.io` by adding the Portal's verification value as a DNS TXT
   record on that domain, then click Verify.
   See [Sonatype's namespace guide](https://central.sonatype.org/register/namespace/).
3. Generate a [Portal publishing token](https://central.sonatype.com/usertoken).
   Save both the generated username and password; these are different from
   your normal sign-in credentials. Note its expiry for rotation.
4. Use a Hivekit-controlled, signing-capable PGP key. Its public key must be
   available from a [Sonatype-supported public keyserver](https://central.sonatype.org/publish/requirements/gpg/).
5. Enable GitHub Actions for `hivekit/client-android`. Create an environment named
   `maven-central` under repository Settings → Environments and add the four
   secrets below. Repository secrets also work if preferred.

## GitHub secrets

| Name | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Generated Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Generated Portal token password |
| `SIGNING_KEY` | Complete ASCII-armored private PGP key, including BEGIN/END lines and actual newlines |
| `SIGNING_PASSWORD` | Passphrase protecting that private key |

Add these through GitHub's secret settings. Do not send private keys or tokens
in chat or commit them to the repository. A public key fingerprint and
confirmation that the namespace and secrets are ready are enough to coordinate
the first release.

If a key is needed, create it with GnuPG on a trusted machine using
`gpg --full-generate-key`, choosing a signing-capable RSA key (4096 bits), a real
maintainer identity and an expiry your team will maintain. Keep a backup and
revocation certificate. Obtain the full fingerprint with:

```shell
gpg --list-secret-keys --keyid-format long
```

Publish the public key, substituting the full fingerprint:

```shell
gpg --keyserver keyserver.ubuntu.com --send-keys FINGERPRINT
```

Export the private key to a secure location outside this checkout, then use its
contents as `SIGNING_KEY`:

```shell
gpg --armor --output /secure/path/hivekit-release-private.asc --export-secret-keys FINGERPRINT
```

Adjust the output path for your OS. If using a signing subkey instead of a full
primary key export, the plugin also accepts
`ORG_GRADLE_PROJECT_signingInMemoryKeyId`; wire that separately for that setup.
The supplied workflow expects the full key export above.

## Verify a release locally

Use JDK 17. These commands work with `bash gradlew` on macOS/Linux; on Windows
replace it with `.\gradlew.bat`.

```shell
bash gradlew :sdk:check :sdk:publishAllPublicationsToBuildRepository "-PreleaseVersion=0.1.0"
```

Inspect `sdk/build/repository/io/hivekit/client-android/0.1.0/`. It contains the
binary, sources, API documentation, POM, Gradle module metadata and checksums.
The runtime `HivekitClient.version` is generated from the same release version.
This command does not upload anything or require secrets.

To test signing locally, supply only the signing environment variables listed
below, then run:

```shell
bash gradlew :sdk:publishAllPublicationsToBuildRepository -PcentralRelease=true "-PreleaseVersion=0.1.0"
```

This still writes only to the local build repository; it also creates `.asc`
signatures. No Portal token is needed for this local signing check.

## Stage and publish a release

1. Commit and push the reviewed release source, including this workflow.
   The workflow must be present on the default branch to appear in Actions.
2. Create and push a tag matching the selected version, for example:

   ```shell
   git tag -a v0.1.0 -m "Release 0.1.0"
   git push origin v0.1.0
   ```

3. In GitHub Actions, run **Publish to Maven Central** with version `0.1.0`.
   The job checks out `refs/tags/v0.1.0`, runs the SDK checks, generates the
   artifacts, signs them and uploads a user-managed deployment.
4. Review the validation result in
   [Central Portal deployments](https://central.sonatype.com/publishing/deployments).
   Resolve any errors before clicking **Publish**.
5. After Central finishes publishing, consumers can use `mavenCentral()` and
   `implementation("io.hivekit:client-android:0.1.0")`.

Released versions on Central are immutable. Use a new version for subsequent
changes. Re-running an upload may create another staged deployment; inspect
existing Portal deployments before retrying a failed workflow.

For an upload from a maintainer's machine instead of GitHub, inject these
environment variables using a secure local secret mechanism:

```text
ORG_GRADLE_PROJECT_mavenCentralUsername
ORG_GRADLE_PROJECT_mavenCentralPassword
ORG_GRADLE_PROJECT_signingInMemoryKey
ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

Then run:

```shell
bash gradlew :sdk:check :sdk:publishToMavenCentral -PcentralRelease=true "-PreleaseVersion=0.1.0"
```

This also stages for manual Portal publishing. The plugin additionally exposes
`publishAndReleaseToMavenCentral`, which publishes automatically; the repository
workflow deliberately uses `publishToMavenCentral`.

The POM includes the project's MIT license, Hivekit developer information and
Git SCM URLs. Review these when repository ownership or licensing changes.
The namespace, token and PGP requirements come from
[Sonatype's publication requirements](https://central.sonatype.org/publish/requirements/).
