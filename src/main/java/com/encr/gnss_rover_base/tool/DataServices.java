/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.encr.gnss_rover_base.tool;

/**
 *
 * @author Sandeep K
 */
public class DataServices {

    /**
     * Returns the conversion factor based on the reporting unit.
     *
     * @param reportingUnit
     * @return
     */
    public static double getConversionFactor(String reportingUnit) {
        if (reportingUnit == null || reportingUnit.isEmpty()) {
            return 1.0;
        }
        return switch (reportingUnit.toLowerCase()) {
            case "mm" ->
                1000.0;
            case "cm" ->
                100.0;
            case "inch" ->
                39.3701;
            case "feet" ->
                3.28084;
            default ->
                1.0;
        };
    }

    private static String formatValue(String value) {
        int decimalDigits;
        decimalDigits = switch (Variable.reporting_unit.toLowerCase()) {
            case "mm" ->
                1;
            case "cm", "inch" ->
                2;
            default ->
                4;
        };
        return Tool.setDecimalDigitsWithoutE(value, decimalDigits);
    }

    /**
     * Converts the value to the target unit using the conversion factor.
     *
     * @param value
     * @param conversionFactor
     * @return
     */
    public static String convertUnit(String value, double conversionFactor) {
        double doubleValue = Double.parseDouble(value);
        return formatValue(String.valueOf(doubleValue * conversionFactor));
    }

    public static String getHeader(boolean includeGeodeticData) {

        String c1 = Variable.axis_enable ? "dALONG" : "dNORTHING";
        String c2 = Variable.axis_enable ? "dTRANSVERSE" : "dEASTING";
// then build the header using c1, c2 in place of the literal "dNORTHING"/"dEASTING"
// e.g.  ",..." + c1 + "(" + Variable.reporting_unit + ")," + c2 + "(" + ... + ")," + ...

        if (includeGeodeticData) {
            return "DATE/TIME"
                    + "," + c1 + "(" + Variable.reporting_unit + ")"
                    + "," + c2 + "(" + Variable.reporting_unit + ")"
                    + ",dALTITUDE(" + Variable.reporting_unit + ")"
                    + ",NORTHING"
                    + ",EASTING"
                    + ",ALTITUDE(m)"
                    + ",SATELLITES"
                    + ",BATTERY(V)"
                    + ",TEMPERATURE(DEG)";
        } else {
            return "DATE/TIME" + ",dNORTHING(" + Variable.reporting_unit + ")" + ",dEASTING(" + Variable.reporting_unit + ")" + ",dALTITUDE(" + Variable.reporting_unit + ")" + ",SATELLITES";
        }
    }

    public static String getData_static(String[] data, boolean includeGeodeticData) {
        StringBuilder process_data = new StringBuilder();
        try {
            if (includeGeodeticData) {
                process_data.append(",").append(data[0]);
                process_data.append(",").append(data[1]);
                process_data.append(",").append(data[2]);
                process_data.append(",").append(data[4]);
                process_data.append(",").append(data[5]);
                process_data.append(",").append(data[6]);
                process_data.append(",").append(data[3]);
            } else {
                process_data.append(",").append(data[0]);
                process_data.append(",").append(data[1]);
                process_data.append(",").append(data[2]);
                process_data.append(",").append(data[3]);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return process_data.toString();
    }

    public static String getData_dynamic(String[] data, int rover_index) {
        StringBuilder process_data = new StringBuilder();
        try {
            process_data.append(",").append(data[0]);
            process_data.append(",").append(data[1]);
            process_data.append(",").append(data[2]);
            process_data.append(",").append(data[3]);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return process_data.toString();
    }
}
