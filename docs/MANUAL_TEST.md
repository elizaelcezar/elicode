# Manual end-to-end test (on an ARM64 device)

The product test from the spec — must pass before any release:

1. [ ] Install `app-debug.apk`, open EliCode → onboarding shows 5 features.
2. [ ] **Prepare environment** → real progress (MB + stages) → "ready".
   Settings → Diagnostics shows core checks ✓ and tool probes.
3. [ ] Projects → New → **Android (Kotlin + Compose)** named `E2E`.
4. [ ] Editor: open `MainActivity.kt`, change greeting text, save (• clears).
5. [ ] Terminal: `pwd`, `ls /projects`, `git status`, `node --version`
   (after node setup) — stdout/stderr/exit visible; `^C` + kill work.
6. [ ] AI tab: status shows runtime/node/opencode; install opencode if
   needed; prompt *"Add a subtitle Text under the greeting"* → output
   streams → file tree refreshes → git status lists the change.
7. [ ] Terminal: `cd /projects/E2E` equivalent + `./gradlew assembleDebug`
   (after Android toolchain setup) — or one-tap **Build APK**.
8. [ ] Build tab lists `app-debug.apk` with size + path.
9. [ ] **Install** → Package Installer opens → app installs → launches.
10. [ ] Git: init/add/commit in E2E; GitHub: connect token, list repos,
    clone a real repo.
11. [ ] Preview: New → Vite (or static web) → ▶ Run → page renders in WebView.
12. [ ] Background: start a build, minimize app + lock screen → build
    continues (foreground notification); reopen → log intact.

Known honest limitations (shown in-app, not hidden):

- No true PTY yet: `vim`, `htop`, sudo-password prompts don't work in the
  terminal; use the editor + non-interactive commands.
- First Gradle/Node builds need network (dependency downloads).
- x86_64 emulators supported (amd64 rootfs, manifest v2, auto-selected by ABI); 32-bit ABIs (armeabi-v7a, x86) remain unsupported.
