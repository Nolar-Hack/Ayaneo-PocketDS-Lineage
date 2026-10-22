#!/system/bin/sh
# Bring-up only. Saves the kernel log + boot state to /metadata/bootlog every few
# seconds, so a hang can be diagnosed afterwards from recovery (see pstore-dump.rc).
# Each pass is synced: a forced power-off on this device is a cold reset that also
# drops the page cache. A USB serial port (ACM, /dev/ttyACM0 on the PC) is tried
# in the background as a live view.
D=/metadata/bootlog

usb_serial() {
    mount -t configfs none /config 2>/dev/null
    G=/config/usb_gadget/dbg
    mkdir -p $G/strings/0x409 $G/functions/acm.gs0 $G/configs/c.1/strings/0x409
    echo 0x18d1 > $G/idVendor
    echo 0x4ee7 > $G/idProduct
    echo pocketds-bootlog > $G/strings/0x409/serialnumber
    echo "Pocket DS bootlog" > $G/strings/0x409/product
    echo "ACM" > $G/configs/c.1/strings/0x409/configuration
    ln -s $G/functions/acm.gs0 $G/configs/c.1/ 2>/dev/null
    # The serial gadget only works with the PC cable plugged in: the port is then in
    # device mode (Type-C role). Never force it: the internal controller needs host mode.
    i=0
    while [ $i -lt 120 ]; do
        udc=$(ls /sys/class/udc 2>/dev/null | head -1)
        [ -n "$udc" ] && break
        sleep 1; i=$((i + 1))
    done
    echo "$udc" > $G/UDC 2>/dev/null
    sleep 3
    # Root shell on the port (bring-up console without adb): from the PC, write
    # commands to /dev/ttyACM0 and read the output back.
    while true; do
        sh < /dev/ttyGS0 > /dev/ttyGS0 2>&1
        sleep 1
    done
}
# The ACM gadget takes the only UDC, so adb cannot work while it runs. Off by
# default; set USB_SERIAL=1 here (e.g. with debugfs on the system_ext image) for a
# root shell on /dev/ttyACM0 when adb is not available (boot hang, broken USB HAL).
USB_SERIAL=0
[ "$USB_SERIAL" = 1 ] && usb_serial &

n=0
while [ $n -lt 1000 ]; do
    echo "bootlog: pass $n" > /dev/kmsg
    if grep -q " /metadata " /proc/mounts; then
        mkdir -p $D
        dmesg > $D/dmesg.txt 2>&1
        {
            echo "===== pass $n uptime $(cat /proc/uptime)"
            cat /proc/mounts
            getprop
            ps -A -o PID,PPID,STAT,WCHAN,NAME
        } > $D/state.txt 2>&1
        logcat -d -b all -v threadtime > $D/logcat.txt 2>&1
        sync
    fi
    n=$((n + 1))
    sleep 5
done
