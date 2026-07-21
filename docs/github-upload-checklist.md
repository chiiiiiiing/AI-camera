# GitHub Upload Checklist

This checklist is for preparing a clean branch that contains only the GenWow work completed in this local project.

## Recommended to upload

Core project files:

- `settings.gradle.kts`
- `build.gradle.kts`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradle/wrapper/gradle-wrapper.jar`
- `.gitignore`
- `README.md`

Android app source:

- `app/build.gradle.kts`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/**`
- `app/src/main/res/**`
- `app/src/test/java/**`
- `app/src/androidTest/java/**`

Project documentation:

- `docs/design-audit.md`
- `docs/implementation-plan.md`
- `docs/figma/frame-inventory.md`
- `docs/figma/visual-validation.md`
- `docs/figma/visual-baseline/README.md`
- `docs/plans/2026-07-21-genwow-camera.md`
- `docs/plans/2026-07-21-figma-compose-refresh.md`
- `docs/github-upload-checklist.md`

## Do not upload

Local configuration:

- `local.properties`
- `.idea/`
- `*.iml`

Build output and caches:

- `.gradle/`
- `.kotlin/`
- `build/`
- `**/build/`
- `captures/`

Local screenshots and temporary evidence:

- `docs/screenshots/`
- `/tmp/genwow-*.png`
- `/tmp/genwow-figma-frames/`

Sensitive or release-only materials:

- `*.jks`
- `*.keystore`
- any signing passwords, tokens, personal keys, or account credentials

## Suggested upload flow

1. Create a new branch in your GitHub repository.
2. Copy only the files listed in "Recommended to upload", or stage only those paths.
3. Double-check that `local.properties`, build directories, screenshots, and any signing files are not staged.
4. Confirm the README and docs still match the code snapshot you want to publish.
5. Push the new branch after your final review.

## Suggested pre-push checks

Run these before uploading if you want to verify the snapshot again:

```bash
./gradlew compileDebugKotlin
./gradlew lint
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

If you have an emulator connected, you can also run:

```bash
./gradlew connectedDebugAndroidTest
```

## Scope reminder

This local project includes work we implemented for the GenWow Android prototype. It does not claim to include:

- a production backend
- a production AI inference pipeline
- production login or SMS verification
- a signed release package ready for app-store publication
