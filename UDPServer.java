import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.HexFormat;

public class UDPServer {
    private static final int PORT = 9876;
    private static DatagramSocket serverSocket;
    private static int expectedSequenceNumber = 0;

    public static void main(String args[]) throws Exception {
        serverSocket = new DatagramSocket(PORT);
        System.out.println("Server is running...");

        String originalFileName = null;
        StringBuilder fileContent = new StringBuilder();
        String expectedHash = null;

        while (true) {
            DatagramPacket receivedPacket = receivePacket();
            String message = new String(receivedPacket.getData(), 0, receivedPacket.getLength());
            int sequenceNumber = Integer.parseInt(message.split(":")[0]);
            long receivedCrc = Long.parseLong(message.split(":")[1]);
            String content = message.substring(message.indexOf(':', message.indexOf(':') + 1) + 1);

            if (sequenceNumber == expectedSequenceNumber && verifyCRC(content.getBytes(), receivedCrc)) {
                System.out.println("Received packet: Sequence Number = " + sequenceNumber + ", Content = '" + content + "', Size = " + content.length() + " bytes");

                if (content.trim().equals("SYN")) {
                    originalFileName = null;
                    fileContent.setLength(0);
                } else if (sequenceNumber == 1) {
                    originalFileName = content.trim();
                } else if (content.startsWith("HASH:")) {
                    // apenas avise que recebeu, envie ack e continue
                    expectedHash = content.split(":")[1];

                    if (verifyMD5(fileContent.toString(), expectedHash)) {
                        System.out.println("MD5 hash check passed.");
                    } else {
                        System.out.println("MD5 hash check failed.");
                    }
                    sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());
                    expectedSequenceNumber++;
                    continue;

                } else if (content.trim().equals("FIN")) {
                    saveFile(fileContent.toString(), originalFileName);
                    System.out.println("Connection closed by FIN.");
                    sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());

                    // Enviar mensagem de fechamento de conexão
                    sendCloseMessage(receivedPacket.getAddress(), receivedPacket.getPort());
                    break;
                } else {
                    fileContent.append(content.trim()); // Append the packet content trimming the padding
                }
                sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());
                expectedSequenceNumber++;
            } else {
                System.out.println("CRC check failed or unexpected sequence number. Packet discarded.");
            }
        }

        serverSocket.close();
    }

    private static boolean verifyCRC(byte[] content, long receivedCrc) {
        CRC32 crc = new CRC32();
        crc.update(content);
        return crc.getValue() == receivedCrc;
    }

    private static boolean verifyMD5(String content, String receivedHash) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(content.getBytes());
        String calculatedHash = HexFormat.of().formatHex(digest);
        return calculatedHash.equals(receivedHash);
    }

    private static void sendAck(int sequenceNumber, InetAddress address, int port) throws IOException {
        String ack = "ACK " + sequenceNumber;
        byte[] sendData = ack.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
        serverSocket.send(sendPacket);
        System.out.println("Sent ACK for Sequence Number: " + sequenceNumber);
    }

    private static void sendCloseMessage(InetAddress address, int port) throws IOException {
        String closeMessage = "CLOSE";
        byte[] sendData = closeMessage.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
        serverSocket.send(sendPacket);
        System.out.println("Sent CLOSE message to client.");
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
