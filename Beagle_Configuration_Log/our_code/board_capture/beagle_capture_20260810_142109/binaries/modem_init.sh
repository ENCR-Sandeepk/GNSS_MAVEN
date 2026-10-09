#!/bin/bash
echo "==== Modem Boot Init ====" >> /var/log/modem_full_log.txt
date >> /var/log/modem_full_log.txt

while [ ! -e /dev/ttyUSB3 ]; do
    sleep 1
done

echo "MODEM AT PORT READY: /dev/ttyUSB3" >> /var/log/modem_full_log.txt

echo -e "AT+QCFG=\"usbnet\",1" > /dev/ttyUSB3
echo "ECM Mode Set" >> /var/log/modem_full_log.txt
