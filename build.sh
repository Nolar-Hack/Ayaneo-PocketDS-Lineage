#!/usr/bin/env bash
# Entry point of the project: everything goes through Customization/scripts/build.sh.
#   ./build.sh --help          options
#   ./build.sh --build ...     build the ROM (then OTA/Full and OTA/Incremental)
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/Customization/scripts/build.sh" "$@"
