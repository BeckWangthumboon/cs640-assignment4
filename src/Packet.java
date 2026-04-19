import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Binary packet format (all big-endian):
 *   seqNum:     4 bytes (int)
 *   ackNum:     4 bytes (int)
 *   timestamp:  8 bytes (long, nanos)
 *   lenFlags:   4 bytes — bit31=S, bit30=F, bit29=A, bits28-0=data length
 *   reserved:   2 bytes (zeros)
 *   checksum:   2 bytes (one's complement)
 *   data:       variable length
 * Total header = 24 bytes.
 */
public class Packet {

    public static final int HEADER_SIZE = 24;
    public static final int MAX_DATA_LEN = (1 << 29) - 1; // 29 bits for length

    private int seqNum;
    private int ackNum;
    private long timestamp;
    private int dataLength;
    private boolean synFlag;
    private boolean finFlag;
    private boolean ackFlag;
    private byte[] data;

    public Packet() {
        this.data = new byte[0];
    }

    public Packet(int seqNum, int ackNum, long timestamp, byte[] data,
                  boolean syn, boolean ack, boolean fin) {
        this.seqNum = seqNum;
        this.ackNum = ackNum;
        this.timestamp = timestamp;
        this.data = (data != null) ? data : new byte[0];
        this.dataLength = this.data.length;
        this.synFlag = syn;
        this.ackFlag = ack;
        this.finFlag = fin;
    }

    // --- Getters / Setters ---

    public int getSeqNum() { return seqNum; }
    public void setSeqNum(int s) { this.seqNum = s; }
    public int getAckNum() { return ackNum; }
    public void setAckNum(int a) { this.ackNum = a; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long t) { this.timestamp = t; }
    public int getDataLength() { return dataLength; }
    public byte[] getData() { return data; }
    public boolean isSyn() { return synFlag; }
    public boolean isFin() { return finFlag; }
    public boolean isAck() { return ackFlag; }
    public void setSyn(boolean v) { synFlag = v; }
    public void setFin(boolean v) { finFlag = v; }
    public void setAck(boolean v) { ackFlag = v; }

    // --- Length+Flags field ---

    private int encodeLenFlags() {
        int val = dataLength & 0x1FFFFFFF; // 29 bits
        if (synFlag) val |= (1 << 31);
        if (finFlag) val |= (1 << 30);
        if (ackFlag) val |= (1 << 29);
        return val;
    }

    private void decodeLenFlags(int val) {
        synFlag = (val & (1 << 31)) != 0;
        finFlag = (val & (1 << 30)) != 0;
        ackFlag = (val & (1 << 29)) != 0;
        dataLength = val & 0x1FFFFFFF;
    }

    // --- Serialization ---

    public byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(seqNum);
        buf.putInt(ackNum);
        buf.putLong(timestamp);
        buf.putInt(encodeLenFlags());
        buf.putShort((short) 0);   // reserved
        buf.putShort((short) 0);   // checksum placeholder
        buf.put(data);

        byte[] pkt = buf.array();
        // compute and insert checksum
        short cksum = computeChecksum(pkt);
        pkt[22] = (byte) ((cksum >> 8) & 0xFF);
        pkt[23] = (byte) (cksum & 0xFF);
        return pkt;
    }

    // --- Deserialization ---

    public static Packet deserialize(DatagramPacket dg) {
        byte[] raw = dg.getData();
        int len = dg.getLength();
        if (len < HEADER_SIZE) return null;

        // verify checksum
        short receivedCksum = (short) (((raw[22] & 0xFF) << 8) | (raw[23] & 0xFF));
        byte[] copy = new byte[len];
        System.arraycopy(raw, 0, copy, 0, len);
        copy[22] = 0; copy[23] = 0;
        short computed = computeChecksum(copy);
        if (receivedCksum != computed) return null; // checksum mismatch

        ByteBuffer buf = ByteBuffer.wrap(raw, 0, len);
        buf.order(ByteOrder.BIG_ENDIAN);
        Packet p = new Packet();
        p.seqNum = buf.getInt();
        p.ackNum = buf.getInt();
        p.timestamp = buf.getLong();
        int lf = buf.getInt();
        p.decodeLenFlags(lf);
        buf.getShort(); // reserved
        buf.getShort(); // checksum
        p.data = new byte[p.dataLength];
        if (p.dataLength > 0) {
            buf.get(p.data);
        }
        return p;
    }

    public static Packet deserialize(byte[] raw) {
        return deserialize(new DatagramPacket(raw, raw.length));
    }

    // --- One's complement checksum ---

    public static short computeChecksum(byte[] data) {
        int sum = 0;
        int len = data.length;
        int i = 0;
        while (i + 1 < len) {
            int hi = data[i] & 0xFF;
            int lo = data[i + 1] & 0xFF;
            sum += (hi << 8) | lo;
            // end-around carry
            if ((sum & 0x10000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
            i += 2;
        }
        // odd trailing byte
        if (i < len) {
            sum += (data[i] & 0xFF) << 8;
            if ((sum & 0x10000) != 0) {
                sum = (sum & 0xFFFF) + 1;
            }
        }
        return (short) (~sum & 0xFFFF);
    }

    // --- Output formatting ---

    public String formatLine(boolean sent) {
        String dir = sent ? "snd" : "rcv";
        String time = String.format("%.3f", System.currentTimeMillis() / 1000.0);
        String S = synFlag ? "S" : "-";
        String A = ackFlag ? "A" : "-";
        String F = finFlag ? "F" : "-";
        String D = (dataLength > 0) ? "D" : "-";
        return String.format("%s %s %s %s %s %s %d %d %d",
                dir, time, S, A, F, D, seqNum, dataLength, ackNum);
    }

    @Override
    public String toString() {
        return String.format("Packet[seq=%d,ack=%d,len=%d,syn=%b,ack=%b,fin=%b]",
                seqNum, ackNum, dataLength, synFlag, ackFlag, finFlag);
    }
}
