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
    private static final int PACKET_SIZE = 10; // Tamanho fixo de cada pacote do arquivo
    private static final long INITIAL_TIMEOUT = 10000; // Timeout inicial em milissegundos
    private static final double LOSS_PROBABILITY = 0.5; // Probabilidade de perda de pacotes
    private static final Random random = new Random();

    private static int cwnd = 1; // Janela de congestionamento inicial
    private static int threshold = 64; // Limiar de congestionamento

    private static final Map<Integer, String> sentPackets = new ConcurrentHashMap<>(); // Pacotes enviados e não confirmados
    private static final Set<Integer> ackedPackets = Collections.synchronizedSet(new HashSet<>()); // Pacotes confirmados
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

        // Enviar o arquivo para o servidor
        startHandShakingAndSendFile(fileName, fileData);

        // Aguardar mensagem de fechamento do servidor
        waitForCloseMessage(clientSocket);
    }

    /*
     * Envia os dados do arquivo para o servidor em pacotes de tamanho fixo.
     */
    private static void sendData(byte[] fileData) throws IOException, InterruptedException {
        int start = 0;
        while (start < fileData.length) {
            while (start < fileData.length && sentPackets.size() < cwnd) {
                // Calcular o índice final do pacote 
                int end = Math.min(start + PACKET_SIZE, fileData.length);
                // Obter os dados do pacote
                byte[] packetData = Arrays.copyOfRange(fileData, start, end);

                // Preenche com espaços em branco se o tamanho do pacote for menor que PACKET_SIZE
                if (packetData.length < PACKET_SIZE) {
                    packetData = Arrays.copyOf(packetData, PACKET_SIZE);
                    Arrays.fill(packetData, end - start, PACKET_SIZE, (byte) ' ');
                }

                // Enviar o pacote para o servidor
                sendPacket(new String(packetData), sequenceNumber.getAndIncrement());
                start += PACKET_SIZE;
                // Aguardar um pouco antes de enviar o próximo pacote
                Thread.sleep(500);
            }
            
            // Aguardar a confirmação de todos os pacotes enviados
            waitForAck();
        }
    }

    /*
     * Calcula o valor CRC32 para os dados do arquivo.
     */
    private static long calculateCRC(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /*
     * Calcula o valor MD5 para os dados do arquivo.
     */
    private static String calculateMD5(byte[] fileData) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(fileData);
        return HexFormat.of().formatHex(digest);
    }

    /*
     * Envia um pacote para o servidor com o número de sequência e o conteúdo.
     */
    private static void sendPacket(String content, int seqNum) throws IOException, InterruptedException {
        byte[] dataBytes = content.getBytes();
        long crcValue = calculateCRC(dataBytes);
        // Formato do pacote: <número de sequência>:<valor CRC>:<conteúdo>
        String packet = seqNum + ":" + crcValue + ":" + content;  // Incluindo CRC no pacote
        byte[] sendData = packet.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, IPAddress, SERVER_PORT);

        // Simulação de perda de pacotes
        if (random.nextDouble() >= LOSS_PROBABILITY) {
            clientSocket.send(sendPacket);
            System.out.println("Sent: " + packet);
            sentPackets.put(seqNum, content);
            scheduleTimeout(seqNum);
        } else {
            // Retransmitir o pacote perdido
            System.out.println("Packet loss, sequence number: " + seqNum);
            sendPacket(content, seqNum);
        }

        Thread.sleep(500);
    }

    /*
     * Aguarda a confirmação de todos os pacotes enviados.
     */
    private static void waitForAck() throws IOException, InterruptedException {
        // Aguardar a confirmação de todos os pacotes enviados
        while (ackedPackets.size() < sentPackets.size()) {
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);
            String response = new String(receivePacket.getData()).trim();

            // Se a mensagem for um ACK, então remover o pacote da lista de pacotes enviados
            if (response.startsWith("ACK")) {
                int ackNum = Integer.parseInt(response.split(" ")[1]);
                ackedPackets.add(ackNum);
                sentPackets.remove(ackNum);
                // Atualizar a janela de congestionamento
                manageCongestionControl();
                System.out.println("Received ACK for sequence number: " + ackNum);
                Thread.sleep(500);
            }
        }
    }

    /*
     * Aguarda um tempo de espera antes de reenviar o pacote não confirmado.
     */
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
                        try {
                            sendPacket(sentPackets.get(sequenceNumber), sequenceNumber);
                            Thread.sleep(500);

                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, INITIAL_TIMEOUT);
    }

    private static void manageCongestionControl() {
        // Se a janela de congestionamento for menor que o limiar, então aplicar Slow Start
        if (cwnd < threshold) {
            cwnd *= 2; // Slow Start (exponencial)
            System.out.println("Slow Start: cwnd increased to " + cwnd);
        } else {
            // Se a janela de congestionamento for maior ou igual ao limiar, então aplicar Congestion Avoidance
            cwnd += 1; // Congestion Avoidance (linear)
            System.out.println("Congestion Avoidance: cwnd incremented to " + cwnd);
        }
    }

    private static void startHandShakingAndSendFile(String fileName, byte[] fileData) throws IOException, NoSuchAlgorithmException, InterruptedException {
        // Necessário enviar SYN para iniciar a conexão
        sendPacket("SYN", 0);
        // Aguardar a confirmação do servidor
        waitForAck();

        // Enviar o nome do arquivo para o servidor antes de enviar o arquivo para o servidor poder salvar com o nome correto
        sendPacket(fileName, 1);
        waitForAck();
        
        // Enviar o arquivo para o servidor em pacotes de tamanho fixo
        sendData(fileData);

        // Enviar o hash MD5 do arquivo antes de enviar o FIN
        String fileHash = calculateMD5(fileData);
        sendPacket("HASH:" + fileHash, sequenceNumber.getAndIncrement());
        waitForAck();

        // Agora enviar FIN como o último pacote para fechar a conexão
        sendPacket("FIN", sequenceNumber.getAndIncrement());
        waitForAck();
    }

    /* 
     * O servidor envia uma mensagem de fechamento para o cliente para indicar que o arquivo foi salvo com sucesso.
     * O cliente pode fechar a conexão após receber a mensagem de fechamento.
     */
    private static void waitForCloseMessage(DatagramSocket clientSocket) throws IOException, InterruptedException {
        while (true) {
            byte[] receiveData = new byte[1024];
            DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
            clientSocket.receive(receivePacket);
            String message = new String(receivePacket.getData()).trim();

            if (message.equals("CLOSE")) {
                System.out.println("Received CLOSE message from server.");
                clientSocket.close();
                System.exit(0);
                break;
            }
            Thread.sleep(500);
        }
    }
}