/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.controller.TcpClientService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BeagleControllerConnectionChecker {

    private static ScheduledExecutorService scheduler;

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                String reply = TcpClientService.sendCommandAndGetReply("WATCHDOG,\"ENABLE\"");
                System.out.println("Reply: " + reply);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    public static void stopWatchdog() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
