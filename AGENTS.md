# AGENTS.md

Working agreement for agents (and humans) changing this repository. Read it
before editing; it overrides generic defaults. User-facing docs live in
`README.md` and `README.zh-CN.md`.

## Repository scope (mandatory)

This checkout is a **personal fork**. Everything stays in the owner's own
repositories — `origin` = `github.com/caidi1224/xcertplay` — and on this
machine.

**Never publish to `github.com/shilapi/xcertplay`.** That project is read-only
here: fetch from it to stay current, never push to it, and do not open pull
requests against it unless the owner explicitly asks for one in the current
conversation.

```bash
git fetch upstream            # allowed: read upstream updates
git merge upstream/master      # allowed: bring them into the fork
git push origin master         # allowed: publish to the fork
git push upstream ...          # FORBIDDEN
git push --mirror              # FORBIDDEN (would reach every remote)
```

Three independent guards enforce this, so no single mistake can publish
upstream:

1. GitHub itself: the owner's account has no write access to
   `shilapi/xcertplay`, so any push there is rejected with 403.
2. `upstream` has its **push URL disabled** in `.git/config`
   (`DISABLED://never-push-to-upstream`) while its fetch URL stays valid.
3. The version-controlled hook `.githooks/pre-push` refuses any push whose
   remote name or URL mentions `shilapi/xcertplay`. It is activated per clone
   with `git config core.hooksPath .githooks`; re-run that after a fresh clone
   or the hook is not consulted.

Do not remove or weaken any of the three. If a task seems to require writing
upstream, stop and ask the owner.

## Project

`xcertplay` is an Android head-unit CarPlay receiver: CarPlay host applications
for Android and Android Automotive OS, an MFi chip reached either through a
CH341 I2C bridge or directly through the board's `/dev/i2c-N` controller, wired
and wireless CarPlay, and Remote MFI authentication.

Gradle multi-module project, Kotlin + Compose:

| Module | Role |
| --- | --- |
| `:shared` | iAP2/CarPlay protocol stack, MFi, wireless session logic |
| `:common` | Android UI and utilities shared by both apps |
| `:mobile` | phone / tablet host app |
| `:automotive` | Android Automotive OS host app |

## Commit policy (mandatory)

**Every update gets its own commit, so that any single change can be rolled
back on its own.**

1. One logical change = one commit. Never bundle unrelated edits, and never
   finish a task with a dirty working tree — commit what you changed, or revert
   it if the experiment failed.
2. The policy applies to *all* updates: source, resources, Gradle files, CI
   workflows, docs (`README*`, this file), `.gitignore`, version bumps.
3. Commit as soon as the change is verified. Do not accumulate a pile of
   uncommitted work "to commit later" — that is exactly what makes rollback
   impossible.
4. Keep commits small enough that `git revert <sha>` cleanly undoes one intent.
   If two changes are independently revertible, they belong in two commits.
5. Do not commit generated or local state. `.gitignore` already excludes
   `build/`, `.gradle/`, `.idea/`, `local.properties`, `*.iml`, `captures/`,
   `mobile/debug/`, `mobile/release/`, `tools/`, `agent_docs/`, `research/`,
   and log files. Check `git status` before staging: no build output, no APKs,
   no keystores, no passwords or tokens.
6. **Never rewrite published history.** No `git push --force`, no `amend` or
   `rebase` on commits that are already on `origin` (especially `master`, which
   is a release branch that CI builds on every push). To undo something that
   has been pushed, revert it forward:

   ```bash
   git revert <sha>          # creates a new, revertible commit
   git log --oneline -5      # confirm
   ```

7. Local, unpushed commits are the cheap rollback path while working:

   ```bash
   git reset --hard HEAD~1   # undo the last local commit (unpushed only)
   git reflog                # recover it if that was a mistake
   ```

   Once a commit is pushed, use `git revert`, not `reset`/`--force`.

### Messages

Match the existing history style — a short imperative subject, lowercase type
prefix, optional scope, no trailing period:

- `feat: carplay file transfer`
- `fix(bt): device filter`
- `opti: better wireless carplay ip binding` (this repo's prefix for
  performance/behaviour tuning)
- `chore: bump version code`
- `update README.md`
- bare version for a release bump: `1.3.0`

Add a body only when the *why* is not obvious from the subject. Never mention
secrets, credentials, or keystore contents in a message.

### Releases

A release is a version-bump commit plus a tag. The tag drives
`.github/workflows/android.yml`, which builds the signed release APKs and
attaches them to the tag's GitHub release. Never commit APKs or signing
material by hand.

### Version bumps (mandatory)

Upstream's version is the first three parts (`1.3.1`). **Every change delivered
from this fork appends a fourth part and increments it**: `1.3.1.1`,
`1.3.1.2`, and so on. Never reuse a value that has already been built, and
re-sync the base whenever upstream is merged (a merge to `1.3.2` restarts at
`1.3.2.1`).

Both numbers live in one place, `gradle/libs.versions.toml`:

```toml
xcertplayVersionName = "1.3.1.1"   # <upstream>.<n>
xcertplayVersionCode = "130101"    # <1301> * 100 + n, always increasing
```

`mobile` and `automotive` read them from the version catalog, so the two
modules cannot drift apart. Every build also carries
`BuildConfig.BUILD_ID` (`<commit>[+run<id>]`), which is written into the first
line of the session log and shown in Settings → Diagnostics — that is how a log
is matched to the APK that produced it.

## Build, test, and lint

CI runs on JDK 25 with Android SDK `platforms;android-37.0`,
`build-tools;36.0.0`, and `ndk;28.2.13676358`. The same commands work locally
(give them a long timeout; first run downloads Gradle and the SDK pieces):

```bash
# what CI runs for every push (dev)
./gradlew \
  :shared:testDebugUnitTest \
  :common:lintDebug :mobile:lintDebug :automotive:lintDebug \
  :mobile:assembleDebug :automotive:assembleDebug \
  --stacktrace

# release variant (requires the Android keystore env vars used by CI)
./gradlew \
  :shared:testDebugUnitTest \
  :common:lintRelease :mobile:lintRelease :automotive:lintRelease \
  :mobile:assembleRelease :automotive:assembleRelease \
  --stacktrace
```

### Local toolchain on this machine

Installed so changes can be compiled **before** pushing - CI is slow and a
typo otherwise costs a build and a version number:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
# Android SDK: ~/Library/Android/sdk (cmdline-tools, platform-tools,
# platforms;android-37.0, build-tools;36.0.0, ndk;28.2.13676358)
# local.properties points at it and is gitignored - never commit it.
```

Fast check while editing (about a minute after the first run, which downloads
Gradle 9.5 and the dependencies):

```bash
./gradlew :shared:compileDebugKotlin :common:compileDebugKotlin
```

Full debug APK, including the JNI/NDK path and packaging:

```bash
./gradlew :mobile:assembleDebug   # -> mobile/build/outputs/apk/debug/
```

#### Local signing

The build reads its signing identity from the same environment variables CI
uses, and the debug variant uses it too when it is present - so a local APK can
be installed over one built by CI, with no uninstall first:

```bash
source ~/.dsh/xcertplay-signing.env   # exports ANDROID_KEYSTORE_* (600, outside the repo)
./gradlew :mobile:assembleDebug       # signed with the fork key
```

The keystore and that file live outside the repository and must never be
committed. Losing the keystore means a new signing identity and one uninstall
on every head unit, so keep a backup of `~/.dsh/xcertplay-fork.jks` and its
password. The current certificate fingerprint is
`744abb7537cac9237ed7566010d920527be90921d899fdea0f32f0b9d185405e`.

Debug APKs land in `mobile/build/outputs/apk/debug/` and
`automotive/build/outputs/apk/debug/`. Release signing needs
`ANDROID_KEYSTORE_PATH` (or `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`); without them, build the debug
variants.

`org.gradle.configuration-cache=true` is enabled project-wide — if a task
misbehaves with the configuration cache, prove it with
`--no-configuration-cache` before changing that setting.

## Bug reports

Ask for the on-device log before guessing at protocol problems:

```
/sdcard/Download/xcertplay/xcertplay.log
```

The session log goes to the shared Downloads collection (no permission needed,
readable by file managers and over MTP) because `Android/data/<package>/` is
hidden from file managers on Android 11+. Settings → Diagnostics shows the path
actually in use. On Android 9, or when the media store refuses the write, it
falls back to:

```
/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log
```

## 中文摘要

- **本仓库只属于你自己的 fork 与本地**：`origin` = `github.com/caidi1224/xcertplay`。**禁止向 `github.com/shilapi/xcertplay` 推送**，也不要向它提 PR（除非你在对话里明确要求）。upstream 只能 fetch 用于同步更新。
- 三重保障不要拆掉：GitHub 侧你没有 upstream 的写权限（403）；本仓库里 `upstream` 的 **push URL 已被禁用**（`DISABLED://never-push-to-upstream`），fetch 仍正常；版本化的 `.githooks/pre-push` 会拦下任何目标里含 `shilapi/xcertplay` 的推送。新克隆后需执行一次 `git config core.hooksPath .githooks` 启用钩子。
- **所有更新都必须单独提交一个 commit**，一个逻辑改动一个 commit，交付时不留未提交的改动，确保任何一处改动都能单独回滚。
- 已推送的历史（尤其 `master`）**不得强推、amend、rebase**；要撤销已推送的改动请用 `git revert <sha>` 生成一个新的可回滚 commit。只有尚未推送的本地 commit 才可以用 `git reset --hard HEAD~1`。
- 提交信息沿用现有风格：`feat:`、`fix(scope):`、`opti:`、`chore:`、`update README.md`，版本号提交直接写 `1.3.0`。
- **每交付一个改动，版本号末尾的小版本号 +1**（`1.3.1` → `1.3.1.1` → `1.3.1.2`…），不重复使用已经构建过的值；合并上游后以新的三段版本为基准重新从 `.1` 开始。两个数字集中在 `gradle/libs.versions.toml`（`xcertplayVersionName` / `xcertplayVersionCode`），mobile 与 automotive 都从那里读取，不会各写一份。
- 每个构建还带 `BuildConfig.BUILD_ID`（提交号 `[+run<CI运行号>]`）：写在会话日志**首行**，也显示在 设置 → 诊断 里——这是判断“车上装的是哪一版、日志出自哪一版”的依据。
- 提交前先看 `git status`：不要提交 `build/`、APK、keystore、`local.properties`、日志等生成物或本地状态。
