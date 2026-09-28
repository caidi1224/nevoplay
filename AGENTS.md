# AGENTS.md

Working agreement for agents (and humans) changing this repository. Read it
before editing; it overrides generic defaults. User-facing docs live in
`README.md` and `README.zh-CN.md`.

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
/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log
```

## 中文摘要

- **所有更新都必须单独提交一个 commit**，一个逻辑改动一个 commit，交付时不留未提交的改动，确保任何一处改动都能单独回滚。
- 已推送的历史（尤其 `master`）**不得强推、amend、rebase**；要撤销已推送的改动请用 `git revert <sha>` 生成一个新的可回滚 commit。只有尚未推送的本地 commit 才可以用 `git reset --hard HEAD~1`。
- 提交信息沿用现有风格：`feat:`、`fix(scope):`、`opti:`、`chore:`、`update README.md`，版本号提交直接写 `1.3.0`。
- 提交前先看 `git status`：不要提交 `build/`、APK、keystore、`local.properties`、日志等生成物或本地状态。
