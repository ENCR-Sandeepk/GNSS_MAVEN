#!/bin/bash

# Text file se jar ka naam padho
JAR_FILE=$(cat /opt/gnss/javaprogram.txt)

# Cellular interface ke up hone ka wait (agar chahiye)
IFACE="eth2"
for i in {1..10}; do
    if ip link show "$IFACE" 2>/dev/null | grep -q "state UP"; then
        echo "$IFACE is up"
        break
    fi
    echo "Waiting for $IFACE..."
    sleep 2
done

# 5 sec ka delay extra
sleep 5

# Program start karo
#/usr/bin/java -jar /opt/gnss/$JAR_FILE
#cd /home/debian || exit 1
/usr/bin/java -jar /home/debian/$JAR_FILE
