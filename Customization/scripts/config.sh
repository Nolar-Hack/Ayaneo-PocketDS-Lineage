# Reads the PocketDS settings: Customization/pocketds.conf, then pocketds.local.conf (yours).
# Sourced by build.sh, make-ota.sh and publish-release.sh; the command line wins over both.
POCKETDS_CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for _f in "$POCKETDS_CONFIG_DIR/pocketds.conf" "$POCKETDS_CONFIG_DIR/pocketds.local.conf"; do
    # shellcheck disable=SC1090
    [[ -f "$_f" ]] && source "$_f"
done
unset _f
# The update.json of the latest release of RELEASE_REPO, unless an address is given.
if [[ -z "${UPDATE_URL:-}" && -n "${RELEASE_REPO:-}" ]]; then
    UPDATE_URL="https://github.com/$RELEASE_REPO/releases/latest/download/update.json"
fi
