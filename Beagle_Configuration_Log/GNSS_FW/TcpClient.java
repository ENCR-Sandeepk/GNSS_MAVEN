import java.io.*;
import java.net.Socket;

public class TcpClient {

    public static void main(String[] args) {

        String serverIp = "192.168.10.100";
        int port = 2001;

        try (Socket socket = new Socket(serverIp, port)) {

            socket.setSoTimeout(5000); // 5 sec timeout

            System.out.println("Connected");

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 👉 तुम्हारा command
            String cmd = "$CALTEMP,\"?\"\r\n";

            out.write(cmd.getBytes());
            out.flush();

            System.out.println("Sent: " + cmd);

            // 👉 reply read करो
            byte[] buffer = new byte[1024];
            int n = in.read(buffer);

            if (n > 0) {
                String response = new String(buffer, 0, n);
                System.out.println("Received: " + response);
            } else {
                System.out.println("No response");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
