# Firebase build modes

## CI/offline demonstration build

GitHub Actions creates a non-production `google-services.json` for project `demo-sleep-tracker-ci`. The resulting CI APK supports local recording, Room, outbox state, Firebase Emulator rules evidence and all offline demonstrations. It must not be presented as connected to the student's real Firebase project.

## Authorized Firebase-integrated build

The workstation currently has an ignored `app/google-services.json` whose project ID is `sleeptrackersimulator` and package is `com.example.sleeptrackersimulator`. The file is not committed and its API configuration is not copied into evidence.

To reproduce an authorized build:

1. Obtain the Android client configuration from the legitimate Firebase project owner.
2. Confirm the registered package is `com.example.sleeptrackersimulator`.
3. Place the file at `app/google-services.json`; keep it ignored by Git.
4. Deploy the repository's `database.rules.json` through an authorized Firebase account.
5. Build locally with `gradlew :app:assembleDebug`.
6. In the app, verify anonymous authentication, online sync, offline pending outbox, restoration, retry and deduplication.
7. Confirm the resulting summary in Firebase Console under the authenticated user's path.

No service-account key belongs in the APK or repository. CI success with demo credentials proves build compatibility and rules tests, not real-project connectivity.
