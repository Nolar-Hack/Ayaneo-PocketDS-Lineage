#!/usr/bin/env bash
# Source patches of the LineageOS projects (patches/<project path>/*.patch).
#
# One patch for each feature, numbered in the order they apply (a later one may change lines of an
# earlier one). A patch can be edited at any time: every project keeps the checksum of each patch
# it applied, and when one patch has changed, was removed or was renamed, the files the patches
# touch are put back as the synced tree has them and ALL the patches of that project are applied
# again, in order. Nothing is guessed from the state of the tree any more.
#
#   patches.sh status [src-dir]    what is applied, what changed
#   patches.sh apply [src-dir]     apply what is missing, redo a project whose patch changed
#   patches.sh check               every project's patches apply, in order, on the synced files
#
# build.sh sources this file (phase --copy); DRY_RUN=1 only says what would happen.

PATCH_REPO_DIR="${PATCH_REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if ! declare -F info >/dev/null; then
    info() { echo "[INFO] $*"; }
    ok()   { echo "[OK] $*"; }
    warn() { echo "[WARN] $*"; }
    die()  { echo "[ERROR] $*" >&2; exit 1; }
fi

# The projects that have patches: one line each, the path under patches/ (frameworks/base ...).
patch_projects() {
    find "$PATCH_REPO_DIR/patches" -name '*.patch' -printf '%h\n' 2>/dev/null | sort -u \
        | sed "s|^$PATCH_REPO_DIR/patches/||"
}

patch_files() {   # <project>: its patches, in order
    find "$PATCH_REPO_DIR/patches/$1" -maxdepth 1 -name '*.patch' | sort
}

patch_sum() { sha256sum "$1" | cut -d' ' -f1; }

# The files a patch touches (from its own text).
patch_touched() {
    # (outside any repository: inside one, git only lists the paths under the current directory)
    git -C / apply --numstat "$(readlink -f "$1")" | awk -F'\t' '{print $3}' | sed 's/.* => //'
}

patch_state_file() {   # <project dir>
    echo "$(git -C "$1" rev-parse --absolute-git-dir)/pocketds-patches.state"
}

# The patches are recorded once, the first time: until then the old rule decides which ones the
# tree already has (the last one that can be reversed, and all before it).
patch_bootstrap() {   # <project dir> <patch>...
    local dir="$1" i top=-1
    shift
    local -a list=("$@")
    for ((i = ${#list[@]} - 1; i >= 0; i--)); do
        if git -C "$dir" apply --reverse --check "${list[i]}" &>/dev/null; then top=$i; break; fi
    done
    for ((i = 0; i <= top; i++)); do
        echo "$(patch_sum "${list[i]}") $(basename "${list[i]}")"
    done
}

# What the recorded patches touched, kept next to the state: a patch that is removed later still
# has its files put back.
patch_record_files() {   # <state file> <patch>
    patch_touched "$2" >> "$1.files"
    sort -u -o "$1.files" "$1.files"
}

# <project> <src dir> [status]
patch_project() {
    local proj="$1" src="$2" only_status="${3:-}"
    local dir="$src/$proj" state p name sum f redo=false seen_new=false
    [[ -e "$dir/.git" ]] || { warn "Not a git project: $proj (patches skipped)"; return 0; }
    state="$(patch_state_file "$dir")"
    local -a list=() touched=()
    while IFS= read -r p; do list+=("$p"); done < <(patch_files "$proj")

    local lines
    if [[ -f "$state" ]]; then
        lines="$(cat "$state")"
    else
        info "$proj: first run of the patch list, recording what the tree has"
        lines="$(patch_bootstrap "$dir" "${list[@]}")"
        if [[ -z "${DRY_RUN:-}" && -z "$only_status" ]]; then
            printf '%s\n' "$lines" | sed '/^$/d' > "$state"
            : > "$state.files"
            for p in "${list[@]}"; do
                grep -q " $(basename "$p")\$" "$state" && patch_record_files "$state" "$p"
            done
        fi
    fi
    declare -A rec=()
    while read -r sum name; do [[ -n "$name" ]] && rec["$name"]="$sum"; done <<< "$lines"

    for p in "${list[@]}"; do
        name="$(basename "$p")"; sum="$(patch_sum "$p")"
        if [[ -n "${rec[$name]:-}" && "${rec[$name]}" != "$sum" ]]; then
            warn "$proj/$name changed since it was applied"; redo=true
        fi
    done
    for name in "${!rec[@]}"; do
        [[ -f "$PATCH_REPO_DIR/patches/$proj/$name" ]] || { warn "$proj/$name is no longer in the list"; redo=true; }
    done
    # An applied patch is never after one that is not (the order of the list is the order of the tree).
    for p in "${list[@]}"; do
        name="$(basename "$p")"
        if [[ -z "${rec[$name]:-}" ]]; then seen_new=true
        elif $seen_new; then warn "$proj/$name is applied but an earlier patch is not"; redo=true; fi
    done

    # The record must match the tree: the last patch it lists can still be reversed (a checkout or
    # a sync that put the files back would have made the record stale).
    if ! $redo; then
        local last=""
        for p in "${list[@]}"; do [[ -n "${rec[$(basename "$p")]:-}" ]] && last="$p"; done
        if [[ -n "$last" ]] && ! git -C "$dir" apply --reverse --check "$last" &>/dev/null; then
            warn "$proj/$(basename "$last") is recorded as applied but the tree does not have it"
            redo=true
        fi
    fi

    if [[ -n "$only_status" ]]; then
        for p in "${list[@]}"; do
            name="$(basename "$p")"
            if [[ -z "${rec[$name]:-}" ]]; then info "  to apply  $proj/$name"
            elif [[ "${rec[$name]}" != "$(patch_sum "$p")" ]]; then info "  CHANGED   $proj/$name"
            else info "  applied   $proj/$name"; fi
        done
        $redo && info "  -> $proj will be redone from the synced files at the next apply"
        return 0
    fi

    if $redo; then
        for p in "${list[@]}"; do while IFS= read -r f; do touched+=("$f"); done < <(patch_touched "$p"); done
        [[ -f "$state.files" ]] && while IFS= read -r f; do touched+=("$f"); done < "$state.files"
        mapfile -t touched < <(printf '%s\n' "${touched[@]}" | sed '/^$/d' | sort -u)
        if [[ -n "${DRY_RUN:-}" ]]; then
            info "Would redo $proj: put back ${#touched[@]} files, apply ${#list[@]} patches"
            return 0
        fi
        info "$proj: putting back ${#touched[@]} files as the synced tree has them, then applying all ${#list[@]} patches"
        for f in "${touched[@]}"; do
            if git -C "$dir" ls-files --error-unmatch -- "$f" &>/dev/null; then
                git -C "$dir" checkout -- "$f"
            else
                rm -f "$dir/$f"
            fi
        done
        : > "$state"
        : > "$state.files"
        rec=()
    fi

    for p in "${list[@]}"; do
        name="$(basename "$p")"
        if [[ -n "${rec[$name]:-}" ]]; then info "Already applied: $proj/$name"; continue; fi
        if [[ -n "${DRY_RUN:-}" ]]; then
            git -C "$dir" apply --check "$p" &>/dev/null && info "Would apply: $proj/$name" \
                || die "Patch does not apply: $proj/$name"
            continue
        fi
        git -C "$dir" apply "$p" || die "Patch does not apply: $proj/$name (patches.sh check tells which)"
        echo "$(patch_sum "$p") $name" >> "$state"
        patch_record_files "$state" "$p"
        ok "Applied: $proj/$name"
    done
}

# Before a repo sync: every file the patches touched goes back to the synced state (an upstream
# change to one of them would otherwise stop the checkout: "local changes would be overwritten"),
# and the records are cleared so that the next apply puts all the patches on again.
patch_put_back() {   # [src dir]
    local src="${1:-$SRC_DIR}" proj dir state p f
    while IFS= read -r proj; do
        dir="$src/$proj"
        [[ -e "$dir/.git" ]] || continue
        state="$(patch_state_file "$dir")"
        local -a touched=()
        while IFS= read -r p; do
            while IFS= read -r f; do touched+=("$f"); done < <(patch_touched "$p")
        done < <(patch_files "$proj")
        [[ -f "$state.files" ]] && while IFS= read -r f; do touched+=("$f"); done < "$state.files"
        mapfile -t touched < <(printf '%s\n' "${touched[@]}" | sed '/^$/d' | sort -u)
        info "$proj: putting back ${#touched[@]} patched files before the sync"
        for f in "${touched[@]}"; do
            if git -C "$dir" ls-files --error-unmatch -- "$f" &>/dev/null; then
                git -C "$dir" checkout -- "$f"
            else
                rm -f "$dir/$f"
            fi
        done
        : > "$state"
        : > "$state.files"
    done < <(patch_projects)
}

apply_patches() {   # [src dir]
    local src="${1:-$SRC_DIR}" proj
    while IFS= read -r proj; do patch_project "$proj" "$src"; done < <(patch_projects)
}

# Every project's patches, in order, on the files as the synced tree has them (git HEAD): catches a
# patch that no longer applies after another one changed, without touching the real tree.
patch_check() {
    local src="${1:-$SRC_DIR}" proj p f t bad=0
    while IFS= read -r proj; do
        t="$(mktemp -d)"
        git -C "$t" init -q
        local -a files=()
        while IFS= read -r p; do while IFS= read -r f; do files+=("$f"); done < <(patch_touched "$p"); done < <(patch_files "$proj")
        mapfile -t files < <(printf '%s\n' "${files[@]}" | sed '/^$/d' | sort -u)
        for f in "${files[@]}"; do
            if git -C "$src/$proj" cat-file -e "HEAD:$f" 2>/dev/null; then
                mkdir -p "$t/$(dirname "$f")"
                git -C "$src/$proj" show "HEAD:$f" > "$t/$f"
            fi
        done
        while IFS= read -r p; do
            if git -C "$t" apply "$p" 2>/tmp/pocketds-check.err; then
                ok "$proj/$(basename "$p")"
            else
                warn "$proj/$(basename "$p") does not apply: $(head -1 /tmp/pocketds-check.err)"
                bad=1
            fi
        done < <(patch_files "$proj")
        rm -rf "$t"
    done < <(patch_projects)
    return $bad
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    set -euo pipefail
    SRC_DIR="${2:-$PATCH_REPO_DIR/../src}"
    case "${1:-}" in
        status) while IFS= read -r proj; do patch_project "$proj" "$SRC_DIR" status; done < <(patch_projects) ;;
        apply) apply_patches "$SRC_DIR" ;;
        check) patch_check "$SRC_DIR" ;;
        *) sed -n 2,14p "$0"; exit 1 ;;
    esac
fi
