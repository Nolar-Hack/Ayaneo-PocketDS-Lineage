# KernelSU Next

`scripts/fetch-kernelsu.sh` downloads a pinned release (checksums verified) into
`prebuilt/`. `scripts/build.sh` patches the built `init_boot.img` with the LKM, so the
ROM is rooted from the first boot (unpatched copy: `init_boot.nosu.img`).

The manager app cannot be a preinstalled system app (its APK stores compressed native
libraries, which a presigned v2 system app cannot have). It ships as a plain file in
`/system_ext/etc/pocketds/` and is installed as a user app on the first boot by
`device/ayaneo/pocket_ds/init/pocketds-firstboot.sh`; it then updates itself.
