# Build & release

## Build the EliCode APK (build machine)

```sh
gradle :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
(`applicationIdSuffix ".debug"` → `com.elicode.app.debug`).

Debug APKs are distributed as GitHub Release assets (never committed
to git — `*.apk` is gitignored):

```sh
gh release create vX.Y.Z-debug app/build/outputs/apk/debug/app-debug.apk
```

Release (unsigned unless configured):

```sh
gradle :app:assembleRelease
```

To sign: add your keystore to `app/build.gradle` `signingConfigs`
(not committed).

## Build an *app inside EliCode* (on device)

1. Config → **⚡ Configurar tudo** (one-click: runtime + Node + OpenCode +
   Android toolchain), or Settings → Runtime → Install (base) +
   Android toolchain (`setup-android.sh`: JDK 17, Gradle 8.7,
   cmdline-tools, platform 34, build-tools 35 — guest versions are
   independent from this repo's host toolchain).
2. Projects → New → **Android (Kotlin + Compose)**.
3. Build tab → preflight must show ✓ java / ✓ gradle / ✓ android-sdk,
   then **▶ Build APK** (runs `<gradle> assembleDebug` in Ubuntu,
   streams the log, interprets common failures: missing platform,
   unresolved deps, OOM, x86 AAPT2).
4. **Install** (Package Installer via FileProvider; grants
   unknown-sources on first use) or **Share** the APK/AAB.

## End-to-end manual test

See `docs/MANUAL_TEST.md` — the 12-step flow from the spec
(open → runtime → create → edit → terminal → agent → build → APK → install).
