#!/vendor/bin/sh

echo "<0> Set fan_speed start" > /dev/kmsg
speed=`tsnv_test -r -s 4 -n 25`
echo "<0> tsnv fan speed is $speed" > /dev/kmsg
if [ -z $(echo $speed) ]; then
	speed=0
fi
echo $speed > /sys/devices/platform/soc/soc:pwm-fan/hwmon/hwmon0/pwm1
echo "<0> Set fan_speed $speed end" > /dev/kmsg
