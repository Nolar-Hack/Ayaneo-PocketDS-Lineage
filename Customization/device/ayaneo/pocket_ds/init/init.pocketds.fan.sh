#!/vendor/bin/sh
# The pwm-fan driver reports fan_power_state=1 after boot but the fan rail stays
# off (0 RPM at any pwm). Stock Ayaneo framework power-cycled it through xsu; do it
# here once the system is up. PocketDS Extra (FanController) then drives the speed.
F=/sys/devices/platform/soc/soc:pwm-fan
echo 0 > $F/fan_power_state
sleep 1
echo 1 > $F/fan_power_state
for P in $F/hwmon/hwmon*/pwm1; do
    [ -e "$P" ] || continue
    # PocketDS Extra (uid system) writes the speed itself, without waiting for root (the
    # root profile is only stored during the first boot of a fresh install).
    chown system system "$P"
    chmod 0664 "$P"
    # A speed written before the power cycle does not reach the fan again by itself.
    echo "$(cat "$P")" > "$P"
done
