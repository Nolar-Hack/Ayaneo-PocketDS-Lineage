# Ayaneo Pocket DS — LineageOS 24

    ./build.sh --help        everything starts here

    Customization/   everything that is ours: device tree, patches, apps, tools, scripts, manifests
    src/             LineageOS (fetched by ./build.sh --sync, never edited by hand)
    Backup/          Original: the console as received (never written by a script)
    OTA/             Full / Incremental packages, and Work/ (Current, Compare) used to make them
    logs/            build, flash and OTA logs

See Customization/README.md for the details.
