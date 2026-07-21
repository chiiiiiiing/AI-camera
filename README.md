# GenWow

GenWow is an Android AI composition camera prototype built with Kotlin, Jetpack Compose, and CameraX.

The app keeps the camera preview at the center of the experience and uses lightweight overlays, motion cues, and composition progress feedback to guide users toward a recommended framing position before capture.

## Project scope

This repository snapshot contains the product work completed locally for the GenWow prototype:

- Figma-driven visual refresh for splash, auth, camera, feed, and profile flows
- Compose-based camera shell and interaction components
- CameraX preview, capture, save-to-gallery, and thumbnail refresh
- AI guidance overlay architecture for pitch, yaw, roll, translation, and subject outline input
- Motion system, previews, unit tests, UI tests, and build verification
- Design audit, implementation plan, and visual validation notes

## Stack

- Kotlin
- Jetpack Compose
- Material 3 foundation with custom visual tokens
- CameraX
- Navigation Compose
- JUnit4
- Compose UI Test

## Current app coverage

- Splash screen
- Login and register flows using local demo auth state
- Camera screen with real CameraX preview and image capture
- Composition guidance overlays and animated feedback states
- Feed and profile demo pages using local sample data
- Permission and error states

## AI guidance integration boundary

The UI is ready to receive real-time AI guidance frames through the camera route layer.

Primary model-facing types:

- `GuidanceSample`
- `SubjectOutline`
- `SubjectContour`
- `NormalizedPoint`

The current project keeps production AI integration isolated. If a real model stream is provided later, it can replace the local demo guidance flow without rewriting the overlay components.

## Build environment

- JDK 17
- Gradle 8.10.2
- Android Gradle Plugin 8.8.2
- `compileSdk 35`
- `targetSdk 35`
- `minSdk 24`

## Run locally

```bash
./gradlew assembleDebug
```

Common verification commands:

```bash
./gradlew compileDebugKotlin
./gradlew lint
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug
./gradlew assembleRelease
```

## APK outputs

Debug APK:

- `app/build/outputs/apk/debug/app-debug.apk`

Unsigned release APK:

- `app/build/outputs/apk/release/app-release-unsigned.apk`

The release artifact is intentionally unsigned. Production signing must be configured separately and should not be committed into version control.

## Repository hygiene

The project is set up to keep local-only and sensitive files out of Git:

- `local.properties`
- `.gradle/`
- `.kotlin/`
- `build/`
- `**/build/`
- `.idea/`
- `captures/`
- `docs/screenshots/`
- `*.jks`
- `*.keystore`

## Key documents

- [Design audit](docs/design-audit.md)
- [Implementation plan](docs/implementation-plan.md)
- [Figma visual validation](docs/figma/visual-validation.md)
- [GitHub upload checklist](docs/github-upload-checklist.md)

## Notes

- Feed, profile, login, registration, and verification code use clearly isolated local demo data.
- The app does not include a production backend, production AI model, or release signing material.
- Screenshots and build artifacts remain local unless you intentionally export them elsewhere.
