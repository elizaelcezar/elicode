# EliCode Linux runtime

## What gets installed (and where)

Everything lives under the app-private dir `filesDir/elicode/runtime`
(never public storage):

| Path | Content |
|---|---|
| `tools/proot` | PRoot 5.1.107 aarch64 (Termux build, GPL-2.0) |
| `tools/loader`, `tools/loader32` | PRoot ELF loader helpers (`PROOT_LOADER` / `PROOT_LOADER_32`, from the proot .deb) |
| `tools/lib/` | `libtalloc.so`, `libandroid-shmem.so` (`LD_LIBRARY_PATH`) |
| `rootfs/` | Ubuntu 22.04 base ARM64 (~30MB tgz, ~110MB extracted) |
| `downloads/` | Cached `.deb`/`.tgz` + `.part` resume files |
| `home/`, `tmp/` | Bind-mounted as `/root`, `/tmp` |
| `version.json` | Installed core version marker |

Pinned URLs + SHA-256: `app/src/main/assets/bootstrap/runtime.json`
(verified 2026-09-08 against `cdimage.ubuntu.com` and
`packages.termux.org`).

## Bootstrap sequence (`RuntimeInstaller`)

1. ARM64 ABI check + free-space check (1GB).
2. Download 3 `.deb`s (resume via HTTP Range, SHA-256 each).
3. Extract `data.tar.xz` from each (pure-JVM ar/tar/xz, path-traversal guard).
4. ELF check (`e_machine == 183`) + `proot --version` smoke test.
5. Download + SHA-256 + extract Ubuntu rootfs (progress by entries).
6. Write `resolv.conf`, `.bashrc`, then run `RuntimeValidator` core checks
   (arch, proot binary, proot runs, `bin/bash`, guest `echo`).
7. Only then write `version.json` — the app never shows "installed" early.

Cancel keeps the download cache; retry resumes. `Repair` re-extracts from
cache without downloading. Each package lists `url` + `mirrors` and the
installer tries them in order (HTTPS first — Android blocks cleartext http
by default, so an http-only primary URL used to fail every on-device
install). A checksum failure deletes both the cached file and its `.part`
so the next retry re-downloads instead of looping on the same bad bytes.
Settings → Runtime also offers "Clear downloads" (drop caches) and "Wipe
runtime" (delete the extracted tree + version marker) for manual recovery
from a half-finished install.

## Guest command shape (`ProotLauncher`)

```
proot -r rootfs -0 --kernel-release=5.15.0 \
  -b /dev -b /proc -b /sys \
  -b home:/root -b tmp:/tmp -b projects:/projects [-b ext:/ext] \
  -w <workdir> /bin/bash --login -c "<cmd>"
```

`-0` = fakeroot (apt/dpkg-friendly, no Android root needed).

Every launch exports `PROOT_LOADER` (+ `PROOT_LOADER_32`) pointing
at the **effective** loader: the APK-bundled copy
(`jniLibs/*/libproot_loader*.so`, extracted to the exec-allowed
native-library dir) wins; the installer-extracted `tools/loader`
is fallback. The Termux proot build defaults to
`/data/data/com.termux/.../libexec/proot/loader`, which never exists
under EliCode's app id — without the override, guest exec fails with
`execve("/usr/bin/bash")` ENOENT ("the loader was not found") even
when bash is present. A `tools/`-only loader fixes ENOENT but still
gets `Permission denied` on W^X devices (targetSdk 29+, e.g. Android
16), which deny exec on app-private files — hence the bundled copy.
The installer copies both helpers out of the proot `.deb`
(`libexec/proot/loader*`); builds ≤ 0.2.1 discarded them.

Guest INTERP chain (Ubuntu 22.04 merged-/usr): bash asks for
`/lib/ld-linux-aarch64.so.1` (x86_64: `ld-linux-x86-64.so.2`);
`/lib → usr/lib`, and `usr/lib/<loader> → <triplet>/<loader>`.
Either symlink missing → kernel ENOENT on the same
`execve("/usr/bin/bash")`. `RuntimeValidator` checks both the
proot loader files and this chain (`rootfs-loader`), so Repair
can tell "rootfs corrupt" apart from "loader missing".

## Optional toolchains (on demand, inside Ubuntu shell)

- Node/OpenCode: `assets/bootstrap/setup-node.sh`
  (`apt install nodejs`, `npm i -g opencode-ai`).
- Android builds: `assets/bootstrap/setup-android.sh`
  (JDK 17, Gradle 8.7, cmdline-tools, platform 34, build-tools 35).

## Limitations (honest)

- PRoot ≠ sandbox: no container/KVM isolation. Documented in-app.
- Terminal: real PTY via the JNI bridge (`libelicode_bridge.so`,
  arm64-v8a + x86_64, `native/`): the guest sees a tty, so line editing,
  ^C/SIGINT to the process group, hidden password prompts, job control
  and process-group kill trees work; one-shots use pipe mode (split
  stdout/stderr). Fullscreen TUIs render through the in-app VT screen
  emulator (`VtEmulator`: alt screen, cursor, SGR 16/256/truecolor,
  margins) — the Terminal tab boots straight into the pure `opencode`
  TUI, with a `shell` fallback toggle. Diagnostics (Settings → Runtime)
  shows the `native-pty` check; without the .so the app falls back to
  JVM pipes.
- Architectures: ARM64 devices use Ubuntu arm64, x86_64 emulators use
  Ubuntu amd64 (manifest v2, auto-selected by ABI, ARM64 preferred).
  32-bit ABIs (armeabi-v7a, x86) remain unsupported.
