# JitPack publishing

The public MIT-licensed SDK is distributed by [JitPack](https://jitpack.io/#hivekit/client-android).
Consumers use `com.github.hivekit:client-android:v0.1.1`. Public installation
requires no account or access token. Pin release tags rather than branch snapshots.

## Build configuration

`jitpack.yml` selects OpenJDK 17 and runs the SDK checks and
`:sdk:publishToMavenLocal`. The Android sample is excluded, so publishing needs
no Android SDK. The standard Gradle `maven-publish` plugin produces the library,
sources, Dokka API documentation, POM and Gradle module metadata.

On JitPack, the publication uses its exact `VERSION` tag, including the `v`
prefix. The runtime `HivekitClient.version` omits that prefix, for example
`0.1.1` for tag `v0.1.1`. Normal local development uses `0.1.1-SNAPSHOT`.

Public publishing needs no repository secrets, signing key or paid subscription.
The previous Sonatype upload and signing configuration has been removed.

## Release

1. Run the SDK checks and inspect the local publication with JDK 17:

   ```shell
   bash gradlew :sdk:check :sdk:publishAllPublicationsToBuildRepository -PreleaseVersion=0.1.1
   ```

   On Windows use `.\gradlew.bat`. Artifacts are written under
   `sdk/build/repository/com/github/hivekit/client-android/0.1.1/`.

2. Commit and push the release source. Create a new tag; never move an existing
   release tag:

   ```shell
   git tag -a v0.1.1 -m "Release Hivekit Android SDK 0.1.1"
   git push origin v0.1.1
   ```

3. The **Publish to JitPack** GitHub workflow requests the release's public POM.
   That triggers JitPack to build the tagged source. The workflow then builds
   debug and optimized release APKs of the independent consumer app, runs lint,
   and installs/tests it on Android API 23. It uses the downloaded SDK with its
   transitive dependencies, with no local Maven repository or project dependency.

4. Wait for the workflow to succeed before announcing the release. Inspect the
   [JitPack build log](https://jitpack.io/com/github/hivekit/client-android/v0.1.1/build.log)
   if compilation or resolution fails. Fix the source and use a new patch tag.
   The workflow can also be run manually with an existing release tag.

5. Update the README's dependency version and the consumer fixture's default
   `sdkVersion` for the next release.

## Verify installation independently

The project in `verification/android-consumer` has its own Gradle settings and
resolves the published SDK exclusively from JitPack. With JDK 17 and an Android
SDK installed:

```shell
bash gradlew -p verification/android-consumer -PsdkVersion=v0.1.1 :app:assembleDebug :app:assembleRelease :app:lintDebug
bash gradlew -p verification/android-consumer -PsdkVersion=v0.1.1 :app:connectedDebugAndroidTest
```

The second command needs a connected device or emulator running API 23 or later.
The checks cover SDK initialization, transitive coroutine and JSON dependencies,
Java time desugaring, the release version, and launching the installed app.

See [JitPack's publishing guide](https://docs.jitpack.io/building/) for the service's
build environment and release behavior.
