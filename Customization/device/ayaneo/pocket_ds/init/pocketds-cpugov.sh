#!/system/bin/sh
# The CPU always uses the walt governor, so it only runs as fast as needed: the performance
# profiles of PocketDS Extra only set the frequency range (scaling_max_freq) of each cluster.
# Run once by init at the end of the boot: puts walt on every cpufreq policy.
CPUFREQ=/sys/devices/system/cpu/cpufreq
GOVERNOR=walt

for p in $CPUFREQ/policy*; do
    [ "$(cat $p/scaling_governor)" = "$GOVERNOR" ] || echo $GOVERNOR > $p/scaling_governor
done
