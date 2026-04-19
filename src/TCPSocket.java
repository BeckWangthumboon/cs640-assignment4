import java.io.*;
import java.net.*;

public class TCPSocket {
    private DatagramSocket socket;
    private InetAddress remoteAddr;
    private int remotePort;
    private int localPort;
    private int mtu;

    public TCPSocket(int localPort, int mtu) throws SocketException {
        this.localPort = localPort;
        this.mtu = mtu;
        this.socket = new DatagramSocket(localPort);
        this.socket.setSoTimeout(5000); // default recv timeout
    }

    public void setRemote(InetAddress addr, int port) {
        this.remoteAddr = addr;
        this.remotePort = port;
    }

    public void setTimeout(int ms) throws SocketException {
        socket.setSoTimeout(ms);
    }

    public void send(Packet pkt) throws IOException {
        byte[] raw = pkt.serialize();
        DatagramPacket dg = new DatagramPacket(raw, raw.length, remoteAddr, remotePort);
        socket.send(dg);
    }

    /**
     * Receive a packet. Returns null on timeout or checksum failure.
     */
    public Packet receive() throws IOException {
        byte[] buf = new byte[HEADER_SIZE + mtu + 100]; // generous buffer
        DatagramPacket dg = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(dg);
        } catch (SocketTimeoutException e) {
            return null;
        }
        Packet pkt = Packet.deserialize(dg);
        if (pkt == null) {
            // checksum discard
            return null;
        }
        // capture sender info
        this.remoteAddr = dg.getAddress();
        this.remotePort = dg.getPort();
        return pkt;
    }

    public void close() {
        socket.close();
    }

    public InetAddress getRemoteAddr() { return remoteAddr; }
    public int getRemotePort() { return remotePort; }
    public int getLocalPort() { return localPort; }

    private static final int HEADER_SIZE = Packet.HEADER_SIZE;
}
