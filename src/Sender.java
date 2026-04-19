import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class Sender {
    private TCPSocket sock;
    private int mtu;
    private int sws; // sliding window size in segments
    private String fileName;

    // Congestion / RTT state
    private double eRTT = 0;
    private double eDEV = 0;
    private double timeOut = 5.0; // initial

    // Send buffer state
    private int baseSeq;       // oldest unACKed byte
    private int nextSeq;       // next byte to send
    private int fileSize;
    private byte[] fileData;

    // Window tracking: seq -> Packet
    private TreeMap<Integer, SegmentEntry> window = new TreeMap<>();

    // Duplicate ACK tracking: ackNum -> count
    private Map<Integer, Integer> dupAckCount = new HashMap<>();

    // Stats
    private long packetsSent = 0;
    private long retransmissions = 0;

    // Receiver ack state for connection tracking
    private int peerAckSeq = 0;
    private long peerTimestamp = 0;

    private static class SegmentEntry {
        Packet pkt;
        long sendTime; // System.nanoTime() when sent
        int retryCount;
        long timerDeadline; // System.nanoTime() deadline for retransmit
    }

    public Sender(TCPSocket sock, int mtu, int sws, String fileName) {
        this.sock = sock;
        this.mtu = mtu;
        this.sws = sws;
        this.fileName = fileName;
    }

    /**
     * Full send lifecycle: handshake -> data transfer -> close
     */
    public void run() throws IOException {
        // Read file
        fileData = readFile(fileName);
        fileSize = fileData.length;

        // 3-way handshake
        handshake();

        // Data transfer
        transferData();

        // 4-way close
        closeConnection();

        // Print stats
        // sender stats: data_bytes, pkts_sent, pkts_rcvd, out_of_seq, cksum_discards, retrans, dup_acks
        System.out.printf("%d %d %d %d %d %d %d%n",
                fileSize, packetsSent, 0, 0, 0, retransmissions, 0);
    }

    // --- Handshake ---

    private void handshake() throws IOException {
        // Send SYN
        Packet syn = new Packet(0, 0, System.nanoTime(), new byte[0], true, false, false);
        sock.send(syn);
        packetsSent++;
        System.out.println(syn.formatLine(true));

        // Wait for SYN+ACK
        while (true) {
            Packet resp = sock.receive();
            if (resp == null) {
                // timeout, resend SYN
                sock.send(syn);
                packetsSent++;
                retransmissions++;
                System.out.println(syn.formatLine(true));
                continue;
            }
            System.out.println(resp.formatLine(false));
            if (resp.isSyn() && resp.isAck()) {
                peerAckSeq = resp.getAckNum();
                peerTimestamp = resp.getTimestamp();
                break;
            }
        }

        // Send ACK completing handshake
        Packet ack = new Packet(1, peerAckSeq, System.nanoTime(), new byte[0], false, true, false);
        sock.send(ack);
        packetsSent++;
        System.out.println(ack.formatLine(true));

        baseSeq = 1; // after SYN
        nextSeq = 1;
    }

    // --- Data Transfer ---

    private void transferData() throws IOException {
        sock.setTimeout(100); // short timeout for recv loop

        while (baseSeq < fileSize + 1) {
            // Fill window
            while (window.size() < sws && nextSeq <= fileSize) {
                int end = Math.min(nextSeq + mtu, fileSize + 1);
                int segLen = end - nextSeq;
                byte[] segData = Arrays.copyOfRange(fileData, nextSeq - 1, end - 1);
                Packet pkt = new Packet(nextSeq, peerAckSeq, System.nanoTime(), segData,
                        false, true, false);
                sendInWindow(pkt);
                nextSeq = end;
            }

            // Try to receive ACK
            Packet ack = sock.receive();
            if (ack == null) {
                // Check for retransmissions
                retransmitTimedOut();
                continue;
            }
            System.out.println(ack.formatLine(false));

            if (ack.isFin()) {
                // Remote initiated close — handle
                peerAckSeq = ack.getAckNum();
                // ACK the FIN
                Packet finAck = new Packet(nextSeq, ack.getAckNum() + 1,
                        System.nanoTime(), new byte[0], false, true, false);
                sock.send(finAck);
                packetsSent++;
                System.out.println(finAck.formatLine(true));
                return;
            }

            int ackNum = ack.getAckNum();
            long ackTs = ack.getTimestamp();
            peerAckSeq = ackNum;

            // Duplicate ACK detection
            if (ackNum <= baseSeq && ack.getDataLength() == 0) {
                int count = dupAckCount.getOrDefault(ackNum, 0) + 1;
                dupAckCount.put(ackNum, count);
                if (count >= 3) {
                    // Fast retransmit
                    SegmentEntry entry = window.get(baseSeq);
                    if (entry != null) {
                        sock.send(entry.pkt);
                        retransmissions++;
                        packetsSent++;
                        System.out.println(entry.pkt.formatLine(true));
                        entry.sendTime = System.nanoTime();
                        entry.timerDeadline = computeDeadline();
                        entry.retryCount++;
                    }
                    dupAckCount.put(ackNum, 0); // reset after fast retransmit
                }
                continue;
            }

            // New ACK — update RTT and slide window
            if (ackTs > 0 && ackNum > baseSeq) {
                updateTimeout(ackTs);
            }

            // Remove ACKed segments from window
            List<Integer> toRemove = new ArrayList<>();
            for (Map.Entry<Integer, SegmentEntry> e : window.entrySet()) {
                int seq = e.getKey();
                if (seq + e.getValue().pkt.getDataLength() <= ackNum) {
                    toRemove.add(seq);
                } else {
                    break;
                }
            }
            for (int seq : toRemove) {
                window.remove(seq);
            }
            baseSeq = ackNum;

            // Reset dup ack counter on new cumulative ACK
            dupAckCount.clear();

            // Check retransmissions
            retransmitTimedOut();
        }
    }

    private void sendInWindow(Packet pkt) throws IOException {
        long now = System.nanoTime();
        SegmentEntry entry = new SegmentEntry();
        entry.pkt = pkt;
        entry.sendTime = now;
        entry.retryCount = 0;
        entry.timerDeadline = computeDeadline();
        window.put(pkt.getSeqNum(), entry);
        sock.send(pkt);
        packetsSent++;
        System.out.println(pkt.formatLine(true));
    }

    private void retransmitTimedOut() throws IOException {
        long now = System.nanoTime();
        for (SegmentEntry entry : window.values()) {
            if (now >= entry.timerDeadline) {
                if (entry.retryCount >= 16) {
                    System.err.println("Max retransmissions exceeded. Aborting.");
                    System.exit(1);
                }
                sock.send(entry.pkt);
                retransmissions++;
                packetsSent++;
                System.out.println(entry.pkt.formatLine(true));
                entry.sendTime = now;
                entry.timerDeadline = computeDeadline();
                entry.retryCount++;
                // Only retransmit the oldest one per check (like Go-Back-N)
                break;
            }
        }
    }

    // --- RTT / Timeout ---

    private long computeDeadline() {
        // Convert timeout (seconds) to nanoseconds
        return System.nanoTime() + (long) (timeOut * 1_000_000_000L);
    }

    private void updateTimeout(long packetTimestamp) {
        double a = 0.875;
        double b = 0.75;
        long currentNanos = System.nanoTime();
        double srtt = (currentNanos - packetTimestamp) / 1_000_000_000.0;

        if (baseSeq == 1 && eRTT == 0) {
            // First measurement (seq=0 was SYN)
            eRTT = srtt;
            eDEV = 0;
            timeOut = 2 * eRTT;
            if (timeOut < 0.5) timeOut = 0.5; // minimum
        } else {
            double sdev = Math.abs(srtt - eRTT);
            eRTT = a * eRTT + (1 - a) * srtt;
            eDEV = b * eDEV + (1 - b) * sdev;
            timeOut = eRTT + 4 * eDEV;
        }
    }

    // --- Close Connection ---

    private void closeConnection() throws IOException {
        sock.setTimeout(5000);

        // Send FIN
        Packet fin = new Packet(nextSeq, peerAckSeq, System.nanoTime(), new byte[0],
                false, false, true);
        sock.send(fin);
        packetsSent++;
        System.out.println(fin.formatLine(true));

        // Wait for ACK of FIN
        while (true) {
            Packet resp = sock.receive();
            if (resp == null) {
                // retransmit FIN
                Packet finResend = new Packet(nextSeq, peerAckSeq, System.nanoTime(),
                        new byte[0], false, false, true);
                sock.send(finResend);
                packetsSent++;
                retransmissions++;
                System.out.println(finResend.formatLine(true));
                continue;
            }
            System.out.println(resp.formatLine(false));
            if (resp.isAck() && !resp.isFin()) {
                break;
            }
            if (resp.isFin()) {
                // Simultaneous close: ACK their FIN, done
                Packet finAck = new Packet(nextSeq, resp.getAckNum() + 1,
                        System.nanoTime(), new byte[0], false, true, false);
                sock.send(finAck);
                packetsSent++;
                System.out.println(finAck.formatLine(true));
                return;
            }
        }

        // Wait for peer FIN
        while (true) {
            Packet resp = sock.receive();
            if (resp == null) continue;
            System.out.println(resp.formatLine(false));
            if (resp.isFin()) {
                // ACK their FIN
                Packet finAck = new Packet(nextSeq, resp.getAckNum() + 1,
                        System.nanoTime(), new byte[0], false, true, false);
                sock.send(finAck);
                packetsSent++;
                System.out.println(finAck.formatLine(true));
                break;
            }
        }
    }

    // --- File I/O ---

    private byte[] readFile(String path) throws IOException {
        RandomAccessFile f = new RandomAccessFile(path, "r");
        byte[] data = new byte[(int) f.length()];
        f.readFully(data);
        f.close();
        return data;
    }
}
