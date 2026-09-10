package com.encr.gnss_rover_base.controller;

/**
 *
 * @author Sandeep K
 */
import com.encr.gnss_rover_base.tool.Constant;
import com.encr.gnss_rover_base.tool.Tool;
import com.encr.gnss_rover_base.tool.Variable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;

public class TcpClientService {

    private static Socket socket;
    private static OutputStream output;
    private static PrintWriter writer;
    private static InputStream input;
    private static BufferedReader reader;
    private static volatile boolean serviceRunning = false;
    private static String hostAddress;
    private static int hostPort;

    // =========================================================
    // START TCP SERVICE
    // =========================================================
    public static void start_server(
            String host,
            int port) {

        hostAddress = host;
        hostPort = port;

        if (serviceRunning) {
            return;
        }

        serviceRunning = true;

        new Thread(() -> {

            while (serviceRunning) {

                try {

                    if (!isConnected()) {
                        Tool.dbg("TCP", "Trying connection to controller " + hostAddress + ":" + hostPort);
                        initializeSocket(hostAddress, hostPort);
                        Tool.dbg("TCP", "Connected to controller " + hostAddress + ":" + hostPort);
                    }

                    startReading();
                } catch (Exception e) {
                    e.printStackTrace();
                }

                close_connection();
                sleep(5000);
            }

        }, "TCP_Service_1").start();
    }

    // =========================================================
    // INITIALIZE SOCKET
    // =========================================================
    private static void initializeSocket(
            String host,
            int port) throws Exception {

        socket = new Socket(host, port);
        socket.setKeepAlive(true);
        output = socket.getOutputStream();
        writer = new PrintWriter(output, true);
        input = socket.getInputStream();
        reader = new BufferedReader(new InputStreamReader(input));
    }

    // =========================================================
    // START READING
    // =========================================================
    private static void startReading() throws Exception {

        String inputLine;

        while (serviceRunning
                && socket != null
                && socket.isConnected()
                && !socket.isClosed()
                && (inputLine = reader.readLine()) != null) {

            try {
                processReply(inputLine);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        throw new Exception(
                "Connection Closed");
    }

    // =========================================================
    // SEND COMMAND AND GET REPLY
    // =========================================================
    public static synchronized String sendCommandAndGetReply(String command) {
        try {
            if (!isConnected()) {
                return "";
            }
            Thread.sleep(200);
            sendCommand(command);
            waitforReply(Constant.COMMAND_REPLY_TIMEOUT_MS);
            if (Variable.got_reply) {
                return Tool.removeDoubleQuotes(Variable.reply);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "";
    }

    // Sends the command repeatedly until it has received OK (1000) `requiredOk`
// times in a row. Blocks forever until satisfied.
    public static void sendCommandUntilOkCount(String command, int requiredOk) {
        int okCount = 0;
        while (okCount < requiredOk) {
            if (isConnected()) {
                sendCommandAndGetReply(command);
                if (Variable.statusCode == Constant.OK_STATUS) {
                    okCount++;
                    System.out.println(command + " OK " + okCount + "/" + requiredOk);
                } else {
                    okCount = 0;   // reset — require consecutive OKs
                    System.out.println(command + " not OK (status " + Variable.statusCode + "), restarting count");
                }
            } else {
                okCount = 0;
                System.out.println(command + " not connected, waiting...");
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // =========================================================
    // SEND COMMAND
    // =========================================================
    public static synchronized void sendCommand(String msg) {

        try {

            resetReplyVariables();

            if (!isConnected()) {

                System.out.println("Send Failed : Not Connected");
                return;
            }

            String command = "$" + msg;

            if (Constant.DEBUG) {
                System.out.println("CMD 1 : " + command);
            }

            Tool.delayInMilliSecond(50);

            writer.print(command + "\r\n");

            writer.flush();

        } catch (Exception e) {

            e.printStackTrace();

            close_connection();
        }
    }

    // =========================================================
    // PROCESS REPLY
    // =========================================================
    private static void processReply(
            String inputLine) {

        try {

            inputLine = inputLine.trim();

            if (inputLine.length() <= 3
                    || !inputLine.contains("$")) {
                return;
            }

            Variable.reply = inputLine;

            Tool.dbg("TCP", "RLY <- " + Variable.reply);

            parseStatusCode();

            processDownloadData();

            processDownloadCompletion();

            Variable.got_reply = true;

            if (Constant.DEBUG) {

                System.out.println(
                        "RLY 1 : "
                        + Variable.reply);
            }

        } catch (Exception e) {

            e.printStackTrace();
        }
    }

    // =========================================================
    // PARSE STATUS CODE
    // =========================================================
    private static void parseStatusCode() {

        int startIndex = Variable.reply.indexOf("$") + 1;

        Variable.statusCode = Integer.parseInt(
                Variable.reply.substring(startIndex, startIndex + 4));

        if (Variable.statusCode == Constant.RECORD_AVAILABLE_STATUS) {
            Variable.downloadWatchdogTimer = System.currentTimeMillis();
        }

        if (Variable.reply.substring(startIndex)
                .length() > 4) {

            Variable.reply = Variable.reply.substring(startIndex + 5).trim();
        }
    }

    // =========================================================
    // PROCESS DOWNLOAD DATA
    // =========================================================
    private static void processDownloadData() {

        if (Variable.statusCode
                != Constant.RECORD_AVAILABLE_STATUS) {

            return;
        }

        char postchar_CR = 0x0D;
        char postchar_LF = 0x0A;

        Variable.reply
                = Variable.reply
                + postchar_CR
                + postchar_LF;

        Variable.rec_count++;

        if (Variable.rec_count >= 1000) {

            Variable.downloadData
                    += Variable.tempDownloadedData
                    + Variable.reply;

            Variable.rec_count = 0;

            Variable.tempDownloadedData = "";

        } else {

            Variable.tempDownloadedData
                    += Variable.reply;
        }
    }

    // =========================================================
    // PROCESS DOWNLOAD COMPLETION
    // =========================================================
    private static void processDownloadCompletion() {

        if (Constant.RECORD_DOWNLOADING_COMPLETED_STATUS == Variable.statusCode) {
            Variable.downloadData += Variable.tempDownloadedData;
            Variable.dataDownloadCompleted = true;
        }
        if (Constant.RECORD_UNAVAILABLE_STATUS == Variable.statusCode) {
            Variable.dataDownloadCompleted = true;
        }
    }

    // =========================================================
    // WAIT FOR REPLY
    // =========================================================
    public static void waitforReply(long time) {

        long start
                = System.currentTimeMillis();

        while (!Variable.got_reply) {

            if ((System.currentTimeMillis() - start) >= time) {
                break;
            }

            sleep(10);
        }
    }

    // =========================================================
    // RESET VARIABLES
    // =========================================================
    private static void resetReplyVariables() {

        Variable.statusCode
                = Constant.DEFAULT_STATUS;

        Variable.reply = "";

        Variable.got_reply = false;
    }

    // =========================================================
    // CONNECTION STATUS
    // =========================================================
    public static boolean isConnected() {

        return socket != null
                && socket.isConnected()
                && !socket.isClosed();
    }

    // =========================================================
    // STOP SERVICE
    // =========================================================
    public static void stopService() {

        serviceRunning = false;

        close_connection();
    }

    // =========================================================
    // CLOSE CONNECTION
    // =========================================================
    public static synchronized void close_connection() {

        try {

            if (reader != null) {
                reader.close();
            }

        } catch (Exception e) {
        }

        try {

            if (writer != null) {
                writer.close();
            }

        } catch (Exception e) {
        }

        try {

            if (input != null) {
                input.close();
            }

        } catch (Exception e) {
        }

        try {

            if (output != null) {
                output.close();
            }

        } catch (Exception e) {
        }

        try {

            if (socket != null
                    && !socket.isClosed()) {

                socket.close();
            }

        } catch (Exception e) {
        }

        socket = null;

        reader = null;
        writer = null;

        input = null;
        output = null;
    }

    // =========================================================
    // SLEEP
    // =========================================================
    private static void sleep(long ms) {

        try {

            Thread.sleep(ms);

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
        }
    }

    // Report the current system state to the controller: STATCODE,<code>
    public static void sendStatusCode(int code) {
        try {
            if (code == Variable.current_status_code) {
                return;
            }
            Tool.dbg("STATCODE", "sending STATCODE," + code + " (connected=" + isConnected() + ")");
            if (isConnected()) {
                sendCommandAndGetReply("STATCODE," + code);
                Variable.current_status_code = code;   // remember last-sent code (only on successful send)
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
