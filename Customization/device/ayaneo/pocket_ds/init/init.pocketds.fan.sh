#!/vendor/bin/sh
# The pwm-fan driver reports fan_power_state=1 after boot but the fan rail stays
# off (0 RPM at any pwm). Stock Ayaneo framework power-cycled it through xsu; do it
# here once the system is up. The kernel thermal framework then drives the speed
# (pwm-fan cooling device).
F=/sys/devices/platform/soc/soc:pwm-fan
echo 0 > $F/fan_power_state
sleep 1
echo 1 > $F/fan_power_state
