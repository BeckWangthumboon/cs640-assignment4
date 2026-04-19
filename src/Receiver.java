import java.io.*;
import java.util.*;

public class Receiver {
    private TCPSocket sock;
    private int mtu;
    private int sws;
    private String fileName;

    // Connection state
    private int nextExpected = 1; // next byte expected (after SYN)
    private int totalReceived = 0;

    // Out-of-order buffer: seq -> data
    private TreeMap<Integer, byte[]> buffer = new TreeMap<>();

    // Output
    private FileOutputStream fos;

    // Stats
    private long packetsReceived = 0;
    private long outOfSequence = 0;
    private long checksumDiscards = 0;

    // Latest sender timestamp for ACK echo
    private long latestSenderTimestamp = 0;

    public Receiver(TCPSocket sock, int mtu, int sws, String fileName) {
        this.sock = sock;
        this.mtu = mtu;
        this.sws = sws;
        this.fileName = fileName;
    }

    public void run() throws IOException {
        sock.setTimeout(30000); // long timeout for initial SYN

        // Wait for SYN
        Packet syn = null;
        while (true) {
            Packet p = sock.receive();
            if (p == null) continue;
            // Could be a checksum discard
            System.out.println(p.formatLine(false));
            if (p.isSyn()) {
                syn = p;
                packetsReceived++;
                break;
            }
            // Count as received
            packetsReceived++;
        }

        int senderSeq = syn.getAckNum(); // not used much
        nextExpected = 1; // after SYN, data starts at seq=1

        // Send SYN+ACK
        Packet synAck = new Packet(0, 1, System.nanoTime(), new byte[0], true, true, false);
        sock.send(synAck);
        System.out.println(synAck.formatLine(true));

        // Wait for ACK (handshake complete)
        while (true) {
            Packet p = sock.receive();
            if (p == null) continue;
            System.out.println(p.formatLine(false));
            packetsReceived++;
            if (p.isAck() && !p.isSyn() && !p.isFin()) {
                if (p.getDataLength() > 0) {
                    // Data arrived with the final handshake ACK
                    processData(p);
                }
                break;
            }
            if (p.isFin()) {
                // Edge case: sender immediately closes
                handleFinAndClose(p);
                printStats();
                return;
            }
        }

        // Open output file
        fos = new FileOutputStream(fileName);

        // Data transfer loop
        sock.setTimeout(100);
        boolean closed = false;

        while (!closed) {
            Packet p = sock.receive();
            if (p == null) continue;
            System.out.println(p.formatLine(false));
            packetsReceived++;

            if (p.isFin()) {
                // Flush buffer before closing
                flushBuffer();
                handleFinAndClose(p);
                closed = true;
                break;
            }

            if (p.getDataLength() > 0) {
                processData(p);
            } else if (p.isAck()) {
                // Pure ACK, ignore
            }
        }

        fos.close();
        printStats();
    }

    private void processData(Packet p) throws IOException {
        int seq = p.getSeqNum();
        int len = p.getDataLength();
        byte[] data = p.getData();

        // Track latest sender timestamp
        if (p.getTimestamp() > latestSenderTimestamp) {
            latestSenderTimestamp = p.getTimestamp();
        }

        // Send cumulative ACK
        if (seq == nextExpected) {
            // In-order segment
            fos.write(data);
            totalReceived += len;
            nextExpected += len;

            // Check buffer for now-contiguous segments
            flushBuffer();

            sendAck();
        } else if (seq > nextExpected) {
            // Out of order — buffer it
            if (!buffer.containsKey(seq)) {
                buffer.put(seq, data);
                outOfSequence++;
            }
            // Send cumulative ACK for what we have
            sendAck();
        } else {
            // seq < nextExpected: duplicate, just re-ACK
            sendAck();
        }
    }

    private void flushBuffer() throws IOException {
        while (buffer.containsKey(nextExpected)) {
            byte[] data = buffer.remove(nextExpected);
            fos.write(data);
            totalReceived += data.length;
            nextExpected += data.length;
        }
    }

    private void sendAck() throws IOException {
        Packet ack = new Packet(nextExpected, nextExpected, latestSenderTimestamp,
                new byte[0], false, true, false);
        sock.send(ack);
        System.out.println(ack.formatLine(true));
    }

    private void handleFinAndClose(Packet finPkt) throws IOException {
        // ACK their FIN
        int finAck = finPkt.getSeqNum() + 1;
        Packet ack = new Packet(nextExpected, finAck, System.nanoTime(),
                new byte[0], false, true, false);
        sock.send(ack);
        System.out.println(ack.formatLine(true));

        // Send our FIN
        Packet fin = new Packet(nextExpected, finAck, System.nanoTime(),
                new byte[0], false, false, true);
        sock.send(fin);
        System.out.println(fin.formatLine(true));

        // Wait for ACK of our FIN
        sock.setTimeout(10000);
        int retries = 0;
        while (retries < 16) {
            Packet p = sock.receive();
            if (p == null) {
                // Retransmit FIN
                sock.send(fin);
                System.out.println(fin.formatLine(true));
                retries++;
                continue;
            }
            System.out.println(p.formatLine(false));
            packetsReceived++;
            if (p.isAck()) {
                break;
            }
        }
    }

    private void printStats() {
        System.out.printf("%d %d %d %d %d %d %d%n",
                totalReceived, 0, packetsReceived, outOfSequence,
                checksumDiscards, 0, 0);
    }
}
