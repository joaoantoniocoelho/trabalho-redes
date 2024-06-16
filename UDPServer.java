import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.CRC32;

public class UDPServer {
    private static final int PORT = 9876;
    private static DatagramSocket serverSocket;
    private static int expectedSequenceNumber = 0;

    public static void main(String args[]) throws Exception {
        serverSocket = new DatagramSocket(PORT);
        System.out.println("Server is running...");

        String originalFileName = null;
        StringBuilder fileContent = new StringBuilder();

        while (true) {
            DatagramPacket receivedPacket = receivePacket();
            String message = new String(receivedPacket.getData(), 0, receivedPacket.getLength());
            int sequenceNumber = Integer.parseInt(message.split(":")[0]);
            long receivedCrc = Long.parseLong(message.split(":")[1]);
            String content = message.substring(message.indexOf(':', message.indexOf(':') + 1) + 1);

            int packetSize = content.length(); // Calcula o tamanho do conteúdo do pacote ANTES de trim()

            if (sequenceNumber == expectedSequenceNumber && verifyCRC(content, receivedCrc)) {
                System.out.println("Received packet: Sequence Number = " + sequenceNumber + ", Content = '" + content + "', Size = " + packetSize + " bytes");
                
                if (sequenceNumber == 0 && content.trim().equals("SYN")) {
                    originalFileName = null;
                    fileContent.setLength(0);
                } else if (sequenceNumber == 1) {
                    originalFileName = content.trim();
                } else if (content.trim().equals("FIN")) {
                    saveFile(fileContent.toString(), originalFileName);
                    System.out.println("Connection closed by FIN.");
                    sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());
                    break;
                } else {
                    fileContent.append(content.trim()); // Aqui aplica trim ao reconstruir o conteúdo do arquivo
                }
                sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());
                expectedSequenceNumber++;
            } else {
                System.out.println("CRC check failed or unexpected sequence number. Packet discarded.");
            }
        }

        serverSocket.close();
    }

    private static boolean verifyCRC(String content, long receivedCrc) {
        CRC32 crc = new CRC32();
        crc.update(content.getBytes());
        return crc.getValue() == receivedCrc;
    }

    private static void sendAck(int sequenceNumber, InetAddress address, int port) throws IOException {
        String ack = "ACK " + sequenceNumber;
        byte[] sendData = ack.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
        serverSocket.send(sendPacket);
        System.out.println("Sent ACK for Sequence Number: " + sequenceNumber);
    }

    private static DatagramPacket receivePacket() throws IOException {
        byte[] receiveData = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        serverSocket.receive(receivePacket);
        return receivePacket;
    }

    private static void saveFile(String content, String originalFileName) throws IOException {
        File directory = new File("received_files");
        if (!directory.exists()) {
            directory.mkdir();
        }
        String newFileName = directory.getAbsolutePath() + File.separator + UUID.randomUUID().toString() + "-" + originalFileName + ".txt";
        Files.write(Paths.get(newFileName), content.getBytes());
        System.out.println("File saved as " + newFileName);
    }
}
