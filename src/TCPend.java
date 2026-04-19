import java.net.InetAddress;

public class TCPend {

    public static void main(String[] args) {
        try {
            Args parsed = parseArgs(args);

            TCPSocket sock = new TCPSocket(parsed.port, parsed.mtu);

            if (parsed.mode == Mode.SENDER) {
                sock.setRemote(InetAddress.getByName(parsed.remoteIP), parsed.remotePort);
                Sender sender = new Sender(sock, parsed.mtu, parsed.sws, parsed.file);
                sender.run();
            } else {
                Receiver receiver = new Receiver(sock, parsed.mtu, parsed.sws, parsed.file);
                receiver.run();
            }

            sock.close();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Args parseArgs(String[] args) {
        Args a = new Args();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-p": a.port = Integer.parseInt(args[++i]); break;
                case "-s":
                    a.mode = Mode.SENDER;
                    a.remoteIP = args[++i];
                    break;
                case "-a": a.remotePort = Integer.parseInt(args[++i]); break;
                case "-f": a.file = args[++i]; break;
                case "-m": a.mtu = Integer.parseInt(args[++i]); break;
                case "-c": a.sws = Integer.parseInt(args[++i]); break;
            }
        }
        return a;
    }

    private enum Mode { SENDER, RECEIVER }

    private static class Args {
        Mode mode = Mode.RECEIVER;
        int port = -1;
        String remoteIP = null;
        int remotePort = -1;
        String file = null;
        int mtu = 1430;
        int sws = 1;
    }
}
