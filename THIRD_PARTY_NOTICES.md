# Third-party notices (EliCode)

EliCode's own source code is MIT (see `LICENSE`). The runtime integrates the
following open-source components, downloaded at setup time (never copied from
Termux-the-app; no Termux dependency). Their licenses are preserved and
complied with below.

## PRoot — GPL-2.0

- What: user-space `chroot`/bind-mount emulation used to run Ubuntu on Android.
- Source: https://github.com/proot-me/proot (and Termux fork https://github.com/termux/proot)
- License: GNU General Public License v2.0.
- Binary origin: Termux APT repository (`termux-main`), pinned in
  `app/src/main/assets/bootstrap/runtime.json`.
- Compliance: PRoot is downloaded to the user's device at runtime setup; we do
  not distribute a modified PRoot binary in the APK. The GPL-2.0 license text
  reference is bundled conceptually; full text at
  https://www.gnu.org/licenses/old-licenses/gpl-2.0.html. If you redistribute
  EliCode with a bundled PRoot binary, you must include the GPL-2.0 text and
  corresponding source offer.

## libandroid-shmem — BSD-3-Clause

- What: System V shared-memory emulation over ashmem (required by PRoot).
- Source: https://github.com/termux/libandroid-shmem
- License: BSD-3-Clause. Copyright notices of the upstream project apply.
- Binary origin: Termux APT repository, pinned in `runtime.json`.

## talloc (libtalloc) — LGPL-3.0-or-later

- What: hierarchical memory-pool library linked by PRoot.
- Source: https://tdb.samba.org/ / https://github.com/samba-team/samba (lib/talloc)
- License: GNU Lesser General Public License v3.0 or later.
- Binary origin: Termux APT repository, pinned in `runtime.json`.
- Compliance: used as an unmodified shared library; LGPL relinking rights are
  preserved (the .deb can be replaced by the user).

## ubuntu-base (Ubuntu 22.04 LTS, ARM64) — mixed (mostly GPL/LGPL + permissive)

- What: minimal Ubuntu root filesystem extracted on-device at setup time.
- Origin: http://cdimage.ubuntu.com/ubuntu-base/releases/ (Canonical), pinned
  with SHA-256 in `runtime.json`.
- License: Ubuntu base system copyrights belong to their respective owners
  (Canonical and upstream projects); see `/usr/share/doc/*/copyright` inside
  the installed rootfs for per-package texts.

## Mobile-Harness (reference architecture only)

- https://github.com/techjarves/Mobile-Harness
- No code was copied into EliCode; it served as an architectural reference
  (private Ubuntu, PRoot launcher shape, foreground service, build/install
  flow). If any snippet is ever adapted, its copyright header must be kept
  here and in the source file.

## Gradle / Android SDK / Kotlin / Jetpack libraries

- Build-time and in-runtime toolchains remain property of their owners
  (Google, JetBrains, Gradle Inc., Apache Software Foundation) under their
  respective licenses (Apache-2.0 for most AndroidX/Kotlin artifacts).
