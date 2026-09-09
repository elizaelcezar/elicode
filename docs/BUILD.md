# Build & release

## Build the EliCode APK (build machine)

```sh
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
(`applicationIdSuffix ".debug"` → `com.elicode.app.debug`).

Release (unsigned unless configured):

```sh
gradle :app:assembleRelease
```

To sign: add your keystore to `app/build.gradle` `signingConfigs`
(not committed).

## Build an *app inside EliCode* (on device)

1. Settings → Runtime → Install (base) + Android toolchain
   (`setup-android.sh` in the Terminal).
2. Projects → New → **Android (Kotlin + Compose)**.
3. Build tab → **▶ Build APK** (runs `<gradle> assembleDebug` in Ubuntu,
   streams the log, interprets common failures: missing platform,
   unresolved deps, OOM, x86 AAPT2).
4. **Install** (Package Installer via FileProvider; grants
   unknown-sources on first use) or **Share** the APK/AAB.

## End-to-end manual test

See `docs/MANUAL_TEST.md` — the 12-step flow from the spec
(open → runtime → create → edit → terminal → agent → build → APK → install).
