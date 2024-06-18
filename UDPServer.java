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
    // O servidor espera receber pacotes com sequência de números crescentes
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

            // Formato da mensagem: <sequence_number>:<crc>:<content>
            String content = message.substring(message.indexOf(':', message.indexOf(':') + 1) + 1);

            // Se o pacote recebido tem o número de sequência esperado e o CRC é válido, o servidor processa o pacote
            if (sequenceNumber == expectedSequenceNumber && verifyCRC(content.getBytes(), receivedCrc)) {
                System.out.println("Received packet: Sequence Number = " + sequenceNumber + ", Content = '" + content + "', Size = " + content.length() + " bytes");

                if (content.trim().equals("SYN")) {
                    originalFileName = null;
                    fileContent.setLength(0);
                } else if (sequenceNumber == 1) {
                    originalFileName = content.trim();
                } else if (content.startsWith("HASH:")) {
                    // Formato da mensagem: HASH:<md5_hash>
                    // Recupera o hash esperado para o conteúdo do arquivo
                    expectedHash = content.split(":")[1];

                    if (verifyMD5(fileContent.toString(), expectedHash)) {
                        // Se o hash MD5 do conteúdo do arquivo recebido é igual ao hash esperado, o servidor envia um ACK
                        System.out.println("MD5 hash check passed.");
                    } else {
                        System.out.println("MD5 hash check failed.");
                    }
                    // Envia ACK para o cliente
                    sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());
                    Thread.sleep(500);  
                    expectedSequenceNumber++;
                    continue;
                } else if (content.trim().equals("FIN")) {
                    // Ao receber o pacote FIN, o servidor salva o conteúdo do arquivo e envia um ACK
                    saveFile(fileContent.toString(), originalFileName);
                    System.out.println("Connection closed by FIN.");
                    sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());

                    // Envia mensagem CLOSE para o cliente e encerra a conexão
                    sendCloseMessage(receivedPacket.getAddress(), receivedPacket.getPort());
                    Thread.sleep(500);  
                    break;
                } else {
                    fileContent.append(content.trim()); // Append the packet content trimming the padding
                }
                sendAck(sequenceNumber + 1, receivedPacket.getAddress(), receivedPacket.getPort());
                expectedSequenceNumber++;
            } else {
                System.out.println("CRC check failed or unexpected sequence number. Packet discarded.");
            }
            Thread.sleep(500);  
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

    private static void sendAck(int sequenceNumber, InetAddress address, int port) throws IOException, InterruptedException {
        String ack = "ACK " + sequenceNumber;
        byte[] sendData = ack.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
        serverSocket.send(sendPacket);
        System.out.println("Sent ACK for Sequence Number: " + sequenceNumber);
        Thread.sleep(500);  // Pause after sending ACK
    }

    private static DatagramPacket receivePacket() throws IOException {
        byte[] receiveData = new byte[1024];
        DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
        serverSocket.receive(receivePacket);
        return receivePacket;
    }

    private static void saveFile(String content, String originalFileName) throws IOException, InterruptedException {
        File directory = new File("received_files");
        if (!directory.exists()) {
            directory.mkdir();
        }
        String newFileName = directory.getAbsolutePath() + File.separator + UUID.randomUUID().toString() + "-" + originalFileName + ".txt";
        Files.write(Paths.get(newFileName), content.getBytes());
        System.out.println("File saved as " + newFileName);
        Thread.sleep(500); 
    }

    private static void sendCloseMessage(InetAddress address, int port) throws IOException {
        String closeMessage = "CLOSE";
        byte[] sendData = closeMessage.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, address, port);
        serverSocket.send(sendPacket);
        System.out.println("Sent CLOSE message to client.");
    }
}
