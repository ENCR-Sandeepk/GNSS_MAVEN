///*
// * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
// * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
// */
//package com.encr.gnss_rover_base.calculation;
//
///**
// *
// * @author Sandeep K
// */
//public class GGAtoUTMConverter {
//
//    public static String convertGGAtoUTM(String line) {
//        String[] parts = line.split(",");
//        double[] latLon = parseGGA(parts);
//        double[] utm;
//        if (isRTKFixed(parts) && (latLon != null)) {
//            utm = convertToUTM(latLon[0], latLon[1]);
//            return "" + utm[0] + "," + utm[1] + "," + parts[9] + "," + parts[7];
//        } else {
//            return null;
//        }
//    }
//
//    private static boolean isRTKFixed(String[] parts) {
//        try {
//            if (parts == null || parts.length < 15) {
//                return false; // Incomplete or null input
//            }
//
//            if (!parts[0].startsWith("$GPGGA")) {
//                return false; // Not a GGA sentence
//            }
//
//            // Ensure fix quality is 4 (RTK Fixed)
//            if ("4".equals(parts[6])) {
//                return true;
//            }
//
//        } catch (Exception e) {
//            return false;
//        }
//        return false;
//    }
//
//    public static double[] parseGGA(String[] parts) {
//        try {
//            if (parts.length < 6 || !parts[0].contains("$GPGGA")) {
//                return null;
//            }
//
//            double latitude = convertDMStoDecimal(Double.parseDouble(parts[2]), parts[3]);
//            double longitude = convertDMStoDecimal(Double.parseDouble(parts[4]), parts[5]);
//            return new double[]{latitude, longitude};
//        } catch (Exception e) {
//            return null;
//        }
//    }
//
//    private static double convertDMStoDecimal(double dms, String hemisphere) {
//        double degrees = Math.floor(dms / 100);
//        double minutes = dms - (degrees * 100);
//        double decimalDegrees = degrees + (minutes / 60);
//        if (hemisphere.equals("S") || hemisphere.equals("W")) {
//            decimalDegrees *= -1;
//        }
//        return decimalDegrees;
//    }
//
//    private static double[] convertToUTM(double lat, double lon) {
//        int zone = (int) ((lon + 180) / 6) + 1;
//        char hemisphere = (lat >= 0) ? 'N' : 'S';
//
//        double lambda0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
//        double phi = Math.toRadians(lat);
//        double lambda = Math.toRadians(lon);
//
//        final double EQUATORIAL_RADIUS = 6378137.0;
//        final double FLATTENING = 1 / 298.257223563;
//        final double K0 = 0.9996;
//        final double E = Math.sqrt(FLATTENING * (2 - FLATTENING));
//        final double E2 = E * E / (1 - E * E);
//
//        double N = EQUATORIAL_RADIUS / Math.sqrt(1 - Math.pow(E * Math.sin(phi), 2));
//        double T = Math.pow(Math.tan(phi), 2);
//        double C = E2 * Math.pow(Math.cos(phi), 2);
//        double A = Math.cos(phi) * (lambda - lambda0);
//
//        double M = EQUATORIAL_RADIUS * ((1 - Math.pow(E, 2) / 4 - 3 * Math.pow(E, 4) / 64 - 5 * Math.pow(E, 6) / 256) * phi
//                - (3 * Math.pow(E, 2) / 8 + 3 * Math.pow(E, 4) / 32 + 45 * Math.pow(E, 6) / 1024) * Math.sin(2 * phi)
//                + (15 * Math.pow(E, 4) / 256 + 45 * Math.pow(E, 6) / 1024) * Math.sin(4 * phi)
//                - (35 * Math.pow(E, 6) / 3072) * Math.sin(6 * phi));
//
//        double easting = K0 * N * (A + (1 - T + C) * Math.pow(A, 3) / 6
//                + (5 - 18 * T + T * T + 72 * C - 58 * E2) * Math.pow(A, 5) / 120) + 500000;
//
//        double northing = K0 * (M + N * Math.tan(phi) * (Math.pow(A, 2) / 2
//                + (5 - T + 9 * C + 4 * C * C) * Math.pow(A, 4) / 24
//                + (61 - 58 * T + T * T + 600 * C - 330 * E2) * Math.pow(A, 6) / 720));
//
//        if (lat < 0) {
//            northing += 10000000;
//        }
//
//        return new double[]{easting, northing, zone, hemisphere};
//    }
//}
