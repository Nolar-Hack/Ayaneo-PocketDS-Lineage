# Patches of the LineageOS sources

`patches/<project path>/NNNN-<feature>.patch`: changes to a LineageOS project that cannot live in the
device tree (`frameworks/base`, `frameworks/native`, `packages/apps/Settings`). `scripts/build.sh`
applies them in the `--copy` phase through `scripts/patches.sh`.

## Rules

- **One patch for each feature** (the swap of the screens, the turbo buttons, the dead zone of the
  sticks...), named after the feature. A feature that needs several projects has one patch in each.
- **The number is the order of application.** A later patch may change lines of an earlier one.
- **Edit the patch of a feature when the feature changes** (do not add a "fix" patch for it). A new
  feature is a new patch with the next number.
- Make a patch with `diff -u --label a/<path> --label b/<path> <before> <after>` (or `git diff`
  in the project), add a `Subject:` line and a `diff --git` line, and keep the text above the
  diff short: what the patch does and why.

## What the build does

For every project `patches.sh` keeps, in the git directory of the project, the checksum of each patch
it applied. At each build:

- the patches it has not applied are applied;
- when a patch **changed**, was **removed** or **renamed**, or when the tree no longer has what is
  recorded (after a `git checkout`), the project is **redone**: the files the patches touch are put
  back as the synced tree has them, then all its patches are applied again, in order.

Nothing is guessed from the tree any more, so editing an old patch is safe.

```
scripts/patches.sh status   # applied / to apply / CHANGED, for each patch
scripts/patches.sh check    # every patch applies, in order, on the synced files (touches nothing)
DRY_RUN=1 scripts/patches.sh apply
```

Run `check` after editing a patch: it tells which patch no longer applies.
