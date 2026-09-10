/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class NextIntervalTime {

    public static LocalDateTime getNextMultipleDateTime(int intervalHours) {

        LocalDateTime now = LocalDateTime.now();

        int nextHour = (((now.getHour() / intervalHours) + 1) * intervalHours) % 24;

        LocalDateTime nextTime = now.truncatedTo(ChronoUnit.DAYS)
                .plusHours(nextHour);

        if (nextHour <= now.getHour()) {
            nextTime = nextTime.plusDays(1);
        }

        // If next boundary is within 2 minutes, skip it
        long minutesToNext = Duration.between(now, nextTime).toMinutes();

        if (minutesToNext <= 2) {
            nextTime = nextTime.plusHours(intervalHours);
        }

        return nextTime;
    }

    public static long getDifferenceInMinutes(String presentTime, String nextTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");

        LocalTime present = LocalTime.parse(presentTime, formatter);
        LocalTime next = LocalTime.parse(nextTime, formatter);

        long minutes = Duration.between(present, next).toMinutes();

        // If next time is on the next day
        if (minutes < 0) {
            minutes += 24 * 60;
        }

        return minutes;
    }

    public static long getDifferenceInSeconds(String presentTime, String nextTime) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");
        LocalTime present = LocalTime.parse(presentTime, formatter);
        LocalTime next = LocalTime.parse(nextTime, formatter);
        long seconds = Duration.between(present, next).getSeconds();   // no truncation to minutes
        if (seconds < 0) {
            seconds += 24L * 60 * 60;   // next boundary is on the following day
        }
        return seconds;
    }

    public static String getCurrentTime(String format) {
        return LocalTime.now()
                .format(DateTimeFormatter.ofPattern(format));
    }

    public static void main(String[] args) {
        LocalDateTime nextTime = getNextMultipleDateTime(3);

        System.out.println(
                nextTime.format(
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                )
        );

        System.out.println(getDifferenceInMinutes("08:20:00", "09:30:00")); // 70
        System.out.println(getDifferenceInMinutes("23:50:00", "00:10:00")); // 20
        System.out.println(getDifferenceInMinutes("12:00:00", "12:00:00")); // 0

    }
}
