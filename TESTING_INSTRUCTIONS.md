# Testing Instructions

## Setup

**1. Start Mininet:**
```bash
sudo ./run_mininet.py topos/single_rt.topo -a
```

**2. Start POX controller:**
```bash
./run_pox.sh
```

**3. Start the virtual router (drops ~5% of packets):**
```bash
java -jar VirtualNetwork.jar -v r1 -r rtable.r1 -a arp_cache
```

## Running the Transfer

**4. Open terminals on h1 and h2 from the mininet console:**
```
xterm h1 h2
```

**5. On h2 (receiver):**
```bash
java TCPend -p <port> -m <mtu> -c <sws> -f <output_file>
```

**6. On h1 (sender):**
```bash
java TCPend -p <port> -s <h2_IP> -a <h2_port> -f <file> -m <mtu> -c <sws>
```

## Verification

**7. Check that the files are identical:**
```bash
sha256sum <original_file> <received_file>
```

## Notes

- Modify the drop rate by editing `forwardIPPacket()` in `Router.java`
- MTU max is 1430 bytes unless your environment supports Ethernet Jumbo Frames
- TAs will test with small, medium, and large files at various drop rates
- The received file must be byte-for-byte identical to the sent file
