import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class FullDuplexClient {

    private static final String SERVER_IP = "10.200.1.1";
    private static final int SERVER_PORT = 2001;

    private static volatile boolean running = true;

    public static void main(String[] args) {
        String ip = SERVER_IP;
        int port = SERVER_PORT;

        if (args.length >= 1) {
            ip = args[0];
        }
        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        try (Socket socket = new Socket(ip, port)) {
            System.out.println("Connected to server: " + ip + ":" + port);

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            Thread rxThread = new Thread(() -> receiveLoop(in), "RX-Thread");
            Thread txThread = new Thread(() -> sendLoop(out, socket), "TX-Thread");

            rxThread.start();
            txThread.start();

            txThread.join();
            running = false;

            try {
                socket.shutdownInput();
            } catch (IOException e) {
                // ignore
            }

            rxThread.join();
            System.out.println("Client terminated.");

        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void receiveLoop(InputStream in) {
        byte[] buffer = new byte[1024];

        try {
            while (running) {
                int n = in.read(buffer);
                if (n == -1) {
                    System.out.println("\nServer closed connection.");
                    running = false;
                    break;
                }

                String data = new String(buffer, 0, n, StandardCharsets.UTF_8);
                System.out.print("\n[RX from server] " + data);
                System.out.print("[TX to server] ");
                System.out.flush();
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("\nReceive thread stopped: " + e.getMessage());
            }
        }
    }

    private static void sendLoop(OutputStream out, Socket socket) {
        try (BufferedReader console =
                     new BufferedReader(new InputStreamReader(System.in))) {

            String line;

            while (running) {
                System.out.print("[TX to server] ");
                System.out.flush();

                line = console.readLine();
                if (line == null) {
                    running = false;
                    break;
                }

                if (line.equalsIgnoreCase("exit")) {
                    running = false;
                    try {
                        socket.shutdownOutput();
                    } catch (IOException e) {
                        // ignore
                    }
                    break;
                }

                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

        } catch (IOException e) {
            if (running) {
                System.out.println("\nSend thread stopped: " + e.getMessage());
            }
        }
    }
}
