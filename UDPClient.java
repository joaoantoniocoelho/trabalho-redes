import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

public class UDPClient {
    private static DatagramSocket clientSocket;
    private static InetAddress IPAddress;
    private static final int SERVER_PORT = 9876;
    private static final int PACKET_SIZE = 10; // Tamanho fixo de cada pacote
    private static final long INITIAL_TIMEOUT = 1000; // Timeout inicial em milissegundos

    private static int cwnd = 1; // Janela de congestionamento inicial
    private static int threshold = 64; // Limiar de congestionamento

    private static final Map<Integer, String> sentPackets = new ConcurrentHashMap<>();
    private static final Set<Integer> ackedPackets = Collections.synchronizedSet(new HashSet<>());
    private static final Timer timer = new Timer();
    private static final AtomicInteger sequenceNumber = new AtomicInteger(2);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java UDPClient <file_name>");
            return;
        }
        String fileName = args[0];
        byte[] fileData = Files.readAllBytes(Paths.get(fileName));

        clientSocket = new DatagramSocket();
        IPAddress = InetAddress.getByName("localhost");

        startHandShaking(fileName, fileData);
        System.out.println("Connection closed.");
        clientSocket.close();
    }

    private static void sendData(byte[] fileData) throws IOException {
        int start = 0;
        while (start < fileData.length) {
            while (start < fileData.length && sentPackets.size() < cwnd) {
                int end = Math.min(start + PACKET_SIZE, fileData.length);
                byte[] packetData = Arrays.copyOfRange(fileData, start, end);

                // Preenche com espaços em branco se o tamanho do pacote for menor que PACKET_SIZE
                if (packetData.length < PACKET_SIZE) {
                    packetData = Arrays.copyOf(packetData, PACKET_SIZE);
                    Arrays.fill(packetData, end - start, PACKET_SIZE, (byte) ' ');
                }

                sendPacket(new String(packetData), sequenceNumber.getAndIncrement());
                start += PACKET_SIZE;
            }
            waitForAck();
            manageCongestionControl();
        }
    }

    private static long calculateCRC(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void sendPacket(String content, int seqNum) throws IOException {
        if (clientSocket.isClosed()) {
            System.out.println("Socket is closed, cannot send packet.");
            return; 
        }
        byte[] dataBytes = content.getBytes();
        long crcValue = calculateCRC(dataBytes);
        String packet = seqNum + ":" + crcValue + ":" + content;  // Incluindo CRC no pacote
        byte[] sendData = packet.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, SERVER_PORT);
        clientSocket.send(sendPacket);
        System.out.println("Sent: " + packet);
        sentPackets.put(seqNum, content);
        scheduleTimeout(seqNum);
    }
    

    private static void waitForAck() throws IOException {
        while (ackedPackets.size() < sentPackets.size()) {
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);
            String response = new String(receivePacket.getData()).trim();

            if (response.startsWith("ACK")) {
                int ackNum = Integer.parseInt(response.split(" ")[1]);
                ackedPackets.add(ackNum);
                sentPackets.remove(ackNum);
            }
        }
    }

    private static void scheduleTimeout(int sequenceNumber) {
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!ackedPackets.contains(sequenceNumber) && sentPackets.containsKey(sequenceNumber)) {
                    try {
                        System.out.println("Timeout, resending sequence number: " + sequenceNumber);
    
                        // Resetar a janela de congestionamento para 1
                        cwnd = 1;
                        // Reduzir o limiar pela metade, não menor que 2
                        threshold = Math.max(threshold / 2, 2);
                        System.out.println("Timeout occurred: cwnd reset to 1, threshold set to " + threshold);
    
                        // Reenviar o pacote
                        sendPacket(sentPackets.get(sequenceNumber), sequenceNumber);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, INITIAL_TIMEOUT);
    }
    

    private static void manageCongestionControl() {
        if (cwnd < threshold) {
            cwnd *= 2; // Slow Start
            System.out.println("Slow Start: cwnd increased to " + cwnd);
        } else {
            cwnd += 1; // Congestion Avoidance
            System.out.println("Congestion Avoidance: cwnd incremented to " + cwnd);
        }
    }
    private static void startHandShaking(String fileName, byte[] fileData) throws IOException, NoSuchAlgorithmException {
        sendPacket("SYN", 0);
        waitForAck();
    
        sendPacket(fileName, 1);
        waitForAck();
    
        sendData(fileData);
    
        // Enviar o hash MD5 do arquivo antes de enviar o FIN
        String fileHash = calculateMD5(fileData);
        sendPacket("HASH:" + fileHash, sequenceNumber.getAndIncrement());
        waitForAck();
    
        // Agora enviar FIN como o último pacote para fechar a conexão
        sendPacket("FIN", sequenceNumber.getAndIncrement());
        waitForAck();
    }
    
    private static String calculateMD5(byte[] fileData) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(fileData);
        return HexFormat.of().formatHex(digest);
    }
}
