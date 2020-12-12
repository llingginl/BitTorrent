import java.io.*;
import java.nio.*;
import java.text.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * @description Define peer in P2P network
 */
public class peerProcess {
    // Basic properties
    private static int peerId;
    private static PeerInfo selfInfo;

    // Read from "Common.cfg"
    private static int preferredNeighborNum;
    private static int unchokingInterval, optimisticUnchokingInterval;
    private static String fileName;
    private static int fileSize, pieceSize;

    // For TCP connection
    private static int peerNum = 0;
    private static int serverConnectionNum = 0;
    private static Vector<Thread> threadList;

    // Message handling
    private static Vector<byte[]> filePieces;
    private static ConcurrentHashMap<Integer, boolean[]> peerBitfieldMap;
    private static MessageHandler messageHandler;
    private static ConcurrentHashMap<Integer, Double> downloadRateMap;
    private static Set<Integer> interestNeighborSet, preferredNeighborsSet;
    private static Integer optimisticallyNeighbor;
    private static ConcurrentHashMap<Integer, ObjectOutputStream> peerSocketMap;
    private static Set<Integer> sentHavePieceSet;

    /**
     * Encapsulate peer information for TCP connection
     */
    private static class PeerInfo {
        String address;
        int port;
        int hasCompleteFile;

        public PeerInfo(String address, int port, int hasCompleteFile) {
            this.address = address;
            this.port = port;
            this.hasCompleteFile = hasCompleteFile;
        }
    }

    /**
     * @description Client thread in TCP connection
     */
    private static class ClientThread implements Runnable {
        private final PeerInfo destPeer;

        public ClientThread(PeerInfo destPeer) {
            this.destPeer = destPeer;
        }

        @Override
        public void run(){
            ObjectInputStream inputStream;
            ObjectOutputStream outputStream;

            // Build TCP connection
            while (true) {
                try {
                    Socket requestSocket = new Socket(destPeer.address, destPeer.port);
                    outputStream = new ObjectOutputStream((requestSocket.getOutputStream()));
                    inputStream = new ObjectInputStream(requestSocket.getInputStream());
                    break;
                }
                catch (ConnectException e) {
                    System.err.println("Connection to " + destPeer.address + ":" + destPeer.port + " refused.");
                }
                catch (Exception ignored) {
                }
            }


            // Start send & receive messages
            int payloadLen = 0;
            char msgType = '0';
            Set<Integer> requestedIndexSet = new HashSet<>();
            boolean request = true;

            // Handshake message
            sendMessage(outputStream, messageHandler.generateHandshakeMessage(peerId)); // Send handshake message
            int destPeerId = messageHandler.getPeerIdFromHandShakeMsg(receiveMessage(inputStream, 32)); // Receive handshake message from socket, get and store its peer Id
            peerSocketMap.put(destPeerId, outputStream);
            writeToLog("Peer " + peerId + " receives handshake message from Peer " + destPeerId, true);
            writeToLog("Peer " + peerId + " makes a connection to Peer " + destPeerId, true); // Record in log file

            // Initialization
            peerBitfieldMap.put(destPeerId, new boolean[peerBitfieldMap.get(peerId).length]); // Initially set all the bitfield of that peer to be "false"
            downloadRateMap.put(destPeerId, 0.0); // Initially set download rate that is from that peer to zero

            // Bitfield message
            sendMessage(outputStream, messageHandler.generateBitfieldMessage(peerBitfieldMap.get(peerId))); // Send bitfield message
            payloadLen = getMsgLen(inputStream) - 1;
            getMsgType(inputStream);
            updatePeerBitfieldMap(inputStream, payloadLen, destPeerId);

            // Interested message
            if (getWantedPieceIndex(destPeerId).size() != 0) sendMessage(outputStream, messageHandler.generateInterestedMessage());
            else sendMessage(outputStream, messageHandler.generateNotInterestedMessage());

            try {
                while (!allHasCompleteFile()) {
                    // For each iteration, get a message from socket
                    payloadLen = getMsgLen(inputStream) - 1;
                    msgType = getMsgType(inputStream);

                    byte[] msgBody;
                    int pieceIndex, pieceNum = 0;
                    List<Integer> wantedList;

                    switch (msgType) {
                        case MessageHandler.CHOKE:
                            writeToLog("Peer " + peerId + " is choked by " + destPeerId, true);
                            request = false;
                            break;

                        case MessageHandler.UNCHOKE:
                            writeToLog("Peer " + peerId + " is unchoked by " + destPeerId, true);
                            request = true;
                            wantedList = getWantedPieceIndex(destPeerId);
                            for (int index : requestedIndexSet) wantedList.remove((Object)index);
                            if (wantedList.size() != 0) {
                                pieceIndex = randomlySelection(wantedList, 1).get(0);
                                requestedIndexSet.add(pieceIndex);
                                sendMessage(outputStream, messageHandler.generateRequestMessage(pieceIndex));
                            }
                            break;

                        case MessageHandler.INTERESTED:
                            writeToLog("Peer " + peerId + " received the 'interested' message from " + destPeerId, true);
                            interestNeighborSet.add(destPeerId);
                            break;

                        case MessageHandler.NOT_INTERESTED:
                            writeToLog("Peer " + peerId + " received the 'not interested' message from " + destPeerId, true);
                            interestNeighborSet.remove(destPeerId);
                            break;

                        case MessageHandler.HAVE:
                            msgBody = receiveMessage(inputStream, 4);
                            pieceIndex = ByteBuffer.wrap(msgBody).getInt();
                            writeToLog("Peer " + peerId + " received the 'have' message from " + destPeerId + " for the piece " + pieceIndex, true);

                            // Update bitfields
                            boolean[] bitmap = peerBitfieldMap.get(destPeerId);
                            bitmap[pieceIndex] = true;
                            peerBitfieldMap.put(destPeerId, bitmap);

                            // Then check and send interest or not-interest message back
                            if (getWantedPieceIndex(destPeerId).size() != 0) {
                                sendMessage(outputStream, messageHandler.generateInterestedMessage());
                            }
                            else {
                                sendMessage(outputStream, messageHandler.generateNotInterestedMessage());
                            }
                            break;

                        case MessageHandler.REQUEST:
                            msgBody = receiveMessage(inputStream, 4);
                            pieceIndex = ByteBuffer.wrap(msgBody).getInt();
                            sendMessage(outputStream, messageHandler.generatePieceMessage(pieceIndex, filePieces.get(pieceIndex)));
                            break;

                        case MessageHandler.PIECE:
                            msgBody = receiveMessage(inputStream, 4);
                            pieceIndex = ByteBuffer.wrap(msgBody).getInt();

                            // Download message
                            long startTime = System.nanoTime();
                            byte[] content = receiveMessage(inputStream, payloadLen - 4);
                            long endTime = System.nanoTime();

                            // Update download rate
                            double preAvg = downloadRateMap.get(destPeerId), curRate = content.length * 1.0 / (endTime - startTime);
                            if (preAvg == 0) {
                                downloadRateMap.put(destPeerId, curRate);
                                pieceNum = 1;
                            }
                            else {
                                pieceNum++;
                                downloadRateMap.put(destPeerId, preAvg + (curRate - preAvg) / pieceNum);
                            }

                            // Update bitmap
                            boolean[] selfBitmap = peerBitfieldMap.get(peerId);

                            if (!selfBitmap[pieceIndex]) {
                                filePieces.set(pieceIndex, content);
                                selfBitmap[pieceIndex] = true;
                                peerBitfieldMap.put(peerId, selfBitmap);

                                // Write into log
                                int cnt = 0;
                                for (boolean bit : peerBitfieldMap.get(peerId)) {
                                    if (bit) cnt++;
                                }
                                writeToLog("Peer " + peerId + " has downloaded the piece " + pieceIndex + " from " + destPeerId + ". Now the number of pieces it has is " + cnt + ".", true);
                                if (cnt == peerBitfieldMap.get(peerId).length) {
                                    writeToLog("Peer " + peerId + " has downloaded the complete file", true);
                                }
                            }

                            // Send 'have' message to other peers
                            for (ObjectOutputStream stream : peerSocketMap.values()) {
                                sendMessage(stream, messageHandler.generateHaveMessage(pieceIndex));
                            }
                            sentHavePieceSet.add(pieceIndex);

                            // Decide whether to send "not interest" and "request" message
                            wantedList = getWantedPieceIndex(destPeerId);
                            if (wantedList.size() == 0) {
                                sendMessage(outputStream, messageHandler.generateNotInterestedMessage());
                            }
                            else if (request) {
                                pieceIndex = randomlySelection(wantedList, 1).get(0);
                                requestedIndexSet.add(pieceIndex);
                                sendMessage(outputStream, messageHandler.generateRequestMessage(pieceIndex));
                            }
                            break;

                        default:
                            break;
                    }
                }
            }
            catch (Exception e) {
                System.exit(0);
            }
        }
    }

    /**
     * @description Thread for listening to the port and getting request from client peers
     */
    private static class ServerListenThread implements Runnable{
        private final int listenPort;

        public ServerListenThread(int listenPort) {
            this.listenPort = listenPort;
        }

        @Override
        public void run(){
            try {
                // Start listening
                ServerSocket serverSocket = new ServerSocket(listenPort);
                for (int i = 0; i < serverConnectionNum; i++) {
                    // After getting a request from a client peer, start a new working thread for communication with that client
                    Socket socket = serverSocket.accept();
                    Thread serverThread = new Thread(new ServerThread(socket));
                    threadList.add(serverThread);
                    serverThread.start();
                }
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * @description Actual server thread for one-to-one communication with client thread
     */
    private static class ServerThread implements Runnable {
        private final Socket socket;
        ObjectInputStream inputStream;
        ObjectOutputStream outputStream;

        public ServerThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // Build TCP connection
            try {
                // Initialize input and output stream
                inputStream = new ObjectInputStream(socket.getInputStream());
                outputStream = new ObjectOutputStream(socket.getOutputStream());
            }
            catch (Exception e) {
                e.printStackTrace();
            }

            // Start send & receive messages
            int payloadLen = 0;
            char msgType = '0';
            Set<Integer> requestedIndexSet = new HashSet<>();
            boolean request = true;

            // Handshake message
            sendMessage(outputStream, messageHandler.generateHandshakeMessage(peerId)); // Send handshake message
            int destPeerId = messageHandler.getPeerIdFromHandShakeMsg(receiveMessage(inputStream, 32)); // Receive handshake message from socket, get and store its peer Id
            peerSocketMap.put(destPeerId, outputStream);
            writeToLog("Peer " + peerId + " receives handshake message from Peer " + destPeerId, true);
            writeToLog("Peer " + peerId + " is connected from Peer " + destPeerId, true); // Record in log file

            // Initialization
            peerBitfieldMap.put(destPeerId, new boolean[peerBitfieldMap.get(peerId).length]); // Initially set all the bitfield of that peer to be "false"
            downloadRateMap.put(destPeerId, 0.0); // Initially set download rate that is from that peer to zero

            // Bitfield message
            sendMessage(outputStream, messageHandler.generateBitfieldMessage(peerBitfieldMap.get(peerId))); // Send bitfield message
            payloadLen = getMsgLen(inputStream) - 1;
            getMsgType(inputStream);
            updatePeerBitfieldMap(inputStream, payloadLen, destPeerId);

            // Interested message
            if (getWantedPieceIndex(destPeerId).size() != 0) sendMessage(outputStream, messageHandler.generateInterestedMessage());
            else sendMessage(outputStream, messageHandler.generateNotInterestedMessage());

            try {
                while (!allHasCompleteFile()) {
                    // For each iteration, get a message from socket
                    payloadLen = getMsgLen(inputStream) - 1;
                    msgType = getMsgType(inputStream);

                    byte[] msgBody;
                    int pieceIndex, pieceNum = 0;
                    List<Integer> wantedList;

                    switch (msgType) {
                        case MessageHandler.CHOKE:
                            writeToLog("Peer " + peerId + " is choked by " + destPeerId, true);
                            request = false;
                            break;

                        case MessageHandler.UNCHOKE:
                            writeToLog("Peer " + peerId + " is unchoked by " + destPeerId, true);
                            request = true;
                            wantedList = getWantedPieceIndex(destPeerId);
                            for (int index : requestedIndexSet) wantedList.remove((Object)index);
                            if (wantedList.size() != 0) {
                                pieceIndex = randomlySelection(wantedList, 1).get(0);
                                requestedIndexSet.add(pieceIndex);
                                sendMessage(outputStream, messageHandler.generateRequestMessage(pieceIndex));
                            }
                            break;

                        case MessageHandler.INTERESTED:
                            writeToLog("Peer " + peerId + " received the 'interested' message from " + destPeerId, true);
                            interestNeighborSet.add(destPeerId);
                            break;

                        case MessageHandler.NOT_INTERESTED:
                            writeToLog("Peer " + peerId + " received the 'not interested' message from " + destPeerId, true);
                            interestNeighborSet.remove(destPeerId);
                            break;

                        case MessageHandler.HAVE:
                            msgBody = receiveMessage(inputStream, 4);
                            pieceIndex = ByteBuffer.wrap(msgBody).getInt();
                            writeToLog("Peer " + peerId + " received the 'have' message from " + destPeerId + " for the piece " + pieceIndex, true);

                            // Update bitfields
                            boolean[] bitmap = peerBitfieldMap.get(destPeerId);
                            bitmap[pieceIndex] = true;
                            peerBitfieldMap.put(destPeerId, bitmap);

                            // Then check and send interest or not-interest message back
                            if (getWantedPieceIndex(destPeerId).size() != 0) {
                                sendMessage(outputStream, messageHandler.generateInterestedMessage());
                            }
                            else {
                                sendMessage(outputStream, messageHandler.generateNotInterestedMessage());
                            }
                            break;

                        case MessageHandler.REQUEST:
                            msgBody = receiveMessage(inputStream, 4);
                            pieceIndex = ByteBuffer.wrap(msgBody).getInt();
                            sendMessage(outputStream, messageHandler.generatePieceMessage(pieceIndex, filePieces.get(pieceIndex)));
                            break;

                        case MessageHandler.PIECE:
                            msgBody = receiveMessage(inputStream, 4);
                            pieceIndex = ByteBuffer.wrap(msgBody).getInt();

                            // Download message
                            long startTime = System.nanoTime();
                            byte[] content = receiveMessage(inputStream, payloadLen - 4);
                            long endTime = System.nanoTime();

                            // Update download rate
                            double preAvg = downloadRateMap.get(destPeerId), curRate = content.length * 1.0 / (endTime - startTime);
                            if (preAvg == 0) {
                                downloadRateMap.put(destPeerId, curRate);
                                pieceNum = 1;
                            }
                            else {
                                pieceNum++;
                                downloadRateMap.put(destPeerId, preAvg + (curRate - preAvg) / pieceNum);
                            }

                            // Update bitmap
                            boolean[] selfBitmap = peerBitfieldMap.get(peerId);

                            if (!selfBitmap[pieceIndex]) {
                                filePieces.set(pieceIndex, content);
                                selfBitmap[pieceIndex] = true;
                                peerBitfieldMap.put(peerId, selfBitmap);

                                // Write into log
                                int cnt = 0;
                                for (boolean bit : peerBitfieldMap.get(peerId)) {
                                    if (bit) cnt++;
                                }
                                writeToLog("Peer " + peerId + " has downloaded the piece " + pieceIndex + " from " + destPeerId + ". Now the number of pieces it has is " + cnt + ".", true);
                                if (cnt == peerBitfieldMap.get(peerId).length) {
                                    writeToLog("Peer " + peerId + " has downloaded the complete file", true);
                                }
                            }

                            // Send 'have' message to other peers
                            for (ObjectOutputStream stream : peerSocketMap.values()) {
                                sendMessage(stream, messageHandler.generateHaveMessage(pieceIndex));
                            }
                            sentHavePieceSet.add(pieceIndex);

                            // Decide whether to send "not interest" and "request" message
                            wantedList = getWantedPieceIndex(destPeerId);
                            if (wantedList.size() == 0) {
                                sendMessage(outputStream, messageHandler.generateNotInterestedMessage());
                            }
                            else if (request) {
                                pieceIndex = randomlySelection(wantedList, 1).get(0);
                                requestedIndexSet.add(pieceIndex);
                                sendMessage(outputStream, messageHandler.generateRequestMessage(pieceIndex));
                            }
                            break;

                        default:
                            break;
                    }
                }
            }
            catch (Exception e) {
                System.exit(0);
            }
        }
    }

    /**
     * Send a message through TCP socket
     * @param stream The specific output stream for a socket
     * @param msg Actual message
     */
    private static synchronized void sendMessage(ObjectOutputStream stream, byte[] msg) {
        try {
            stream.write(msg);
            stream.flush();
        }
        catch (Exception e) {
            System.exit(0);
        }
    }

    /**
     * For an actual message, get its "length" field to know the actual length
     */
    private static int getMsgLen(ObjectInputStream stream) {
        byte[] len = new byte[4];
        int index = 0, length = 0;
        try {
            while (index < 4) {
                length = stream.read(len, index, 4 - index);
                if (length > 0) index += length;
                else break;
            }
        }
        catch (Exception e) {
            System.exit(0);
        }
        return ByteBuffer.wrap(len).getInt();
    }

    /**
     * For an actual message, get its "type" field
     */
    private static char getMsgType(ObjectInputStream stream){
        char type = '0';
        try {
            type = (char)stream.readByte();
        }
        catch (Exception e) {
            System.exit(0);
        }
        return type;
    }

    /**
     * Receive a message through TCP socket
     * @param stream The specific input stream for a socket
     * @param msgLength The length of the receiving message in byte
     * @return Message body
     */
    private static byte[] receiveMessage(ObjectInputStream stream, int msgLength) {
        byte[] msg = new byte[msgLength];
        int index = 0, length = 0;
        try {
            while (index < msgLength) {
                length = stream.read(msg, index, msgLength - index);
                if (length > 0) index += length;
                else break;
            }
        }
        catch (Exception e) {
            System.exit(0);
        }
        return msg;
    }

    /**
     * When receiving BITFIELD message, update the bitfield map
     */
    private static synchronized void updatePeerBitfieldMap(ObjectInputStream stream, int payloadLen, int destPeerId) {
        int actualBitfieldLen = peerBitfieldMap.get(peerId).length;
        boolean[] destBitfield = messageHandler.getBitfieldFromBitfieldMsg(receiveMessage(stream, payloadLen), actualBitfieldLen);
        peerBitfieldMap.put(destPeerId, destBitfield);
        writeToLog("Peer " + peerId + " receives bitfield message from " + destPeerId, true);
    }

    /**
     * Check bitfield for a specific peer and see if there exist some pieces that it has but self don't have
     * @return Array of wanted piece indexes
     */
    private static synchronized List<Integer> getWantedPieceIndex(int destPeerId) {
        List<Integer> indexList = new ArrayList<>();
        boolean[] destBitfield = peerBitfieldMap.get(destPeerId), selfBitfield = peerBitfieldMap.get(peerId);
        for (int i = 0; i < destBitfield.length; i++) {
            if (destBitfield[i] && !selfBitfield[i]) indexList.add(i);
        }
        return indexList;
    }

    /**
     * Check if self already have the complete file
     */
    private static synchronized boolean hasCompleteFile() {
        boolean[] bitfield = peerBitfieldMap.get(peerId);
        for (boolean bit : bitfield) {
            if (!bit) return false;
        }
        return true;
    }

    /**
     * Check is all the peers already have the complete file
     */
    private static synchronized boolean allHasCompleteFile() {
        if (peerBitfieldMap.size() != peerNum) return false;
        else if (sentHavePieceSet.size() < filePieces.size()) return false;
        for (int peer : peerBitfieldMap.keySet()) {
            boolean[] bitfield = peerBitfieldMap.get(peer);
            for (boolean bit : bitfield) {
                if (!bit) return false;
            }
        }
        return true;
    }

    /**
     * Randomly select some peers from candidate list
     * @param num Number of peers to be picked out
     */
    private static synchronized List<Integer> randomlySelection(List<Integer> candidates, int num) {
        List<Integer> res = new ArrayList<>();
        Random random = new Random();
        while (res.size() < num && candidates.size() > 0) {
            int index = random.nextInt(candidates.size());
            int destPeerId = candidates.get(index);
            res.add(destPeerId);
            candidates.remove((Object)destPeerId);
        }
        return res;
    }

    /**
     * Write content into log file
     * @param content Actual content
     * @param append "true" for appending, "false" for overwriting
     */
    private static synchronized void writeToLog(String content, Boolean append){
        String fileName = "../peer_" + peerId + ".log";
        try {
            FileWriter fw = new FileWriter(fileName, append);

            // Get current time
            SimpleDateFormat sdf = new SimpleDateFormat();
            sdf.applyPattern("yyyy-MM-dd HH:mm:ss");
            Date curDate = new Date();
            String time = sdf.format(curDate);

            // Record a line into log file
            fw.write(time + " " + content + "\n\n");
            fw.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Finish some initialization work
     */
    private void Init() throws Exception {
        List<PeerInfo> serverList = new ArrayList<>();

        // Read "PeerInfo.cfg" to get information for all the peers
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream("PeerInfo.cfg")));
        String line;
        while ((line = reader.readLine()) != null) {
            peerNum++;
            String[] tokens = line.split(" ");
            int pId = Integer.parseInt(tokens[0]), port = Integer.parseInt(tokens[2]), hasCompleteFile = Integer.parseInt(tokens[3]);
            String address = tokens[1];

            // This peer should be the client for building TCP connection with previous peers
            if (pId < peerId) serverList.add(new PeerInfo(address, port, hasCompleteFile));
            else if (pId == peerId) selfInfo = new PeerInfo(address, port, hasCompleteFile);
            // This peer should also be the server for latter peers, thus the number of listening times should be counted
            else serverConnectionNum++;
        }

        // Initialize self bitfield
        int pieceNum = fileSize % pieceSize == 0 ? fileSize / pieceSize : fileSize / pieceSize + 1;
        boolean[] bitfield = new boolean[pieceNum];
        if (selfInfo.hasCompleteFile == 1) Arrays.fill(bitfield, true);

        // Store in the map
        peerBitfieldMap.put(peerId, bitfield);

        // Write bitfield into log file
        StringBuilder content = new StringBuilder("Peer " + peerId + " set bitfield to [");
        for (boolean bool : bitfield) content = bool ? content.append(1).append(", ") : content.append(0).append(", ");
        content.setCharAt(content.length() - 2, ']');
        writeToLog(content.toString(), true);

        // Initialize file pieces
        if (selfInfo.hasCompleteFile == 1) {
            int rtnLen = 0;
            FileInputStream stream = new FileInputStream(fileName);
            byte[] buffer = new byte[pieceSize];
            while ((rtnLen = stream.read(buffer)) != -1) {
                byte[] tmpBuf = new byte[rtnLen];
                if (tmpBuf.length >= 0) System.arraycopy(buffer, 0, tmpBuf, 0, tmpBuf.length);
                filePieces.add(tmpBuf);
            }
        }
        else {
            for (int i = 0; i < pieceNum; i++) {
                filePieces.add(null);
            }
        }

        // Start the server listening thread
        Thread serverListenThread = new Thread(new ServerListenThread(selfInfo.port));
        serverListenThread.start();

        // Start several client threads
        for (PeerInfo pInfo : serverList) {
            Thread clientThread = new Thread(new ClientThread(pInfo));
            threadList.add(clientThread);
            clientThread.start();
        }

        // Start a new thread for timer task to periodically select preferred neighbors
        Timer preferredNeighborSelectionTask = new Timer();
        preferredNeighborSelectionTask.schedule(new TimerTask() {
            @Override
            public void run() {
                if (peerBitfieldMap.size() == peerNum) {
                    Set<Integer> tmpNeighborSet = new HashSet<>(), tmpInterestSet = interestNeighborSet;

                    // If self don't have the complete file
                    if (!hasCompleteFile()) {
                        List<Map.Entry<Integer, Double>> list = new ArrayList<>(downloadRateMap.entrySet());
                        list.sort((o1, o2) -> o2.getValue().compareTo(o1.getValue()));

                        // Then select peers that are interested and with top download rate
                        for (Map.Entry<Integer, Double> integerIntegerEntry : list) {
                            int destPeerId = integerIntegerEntry.getKey();
                            if (tmpInterestSet.contains(destPeerId) && tmpNeighborSet.size() != preferredNeighborNum) {
                                tmpNeighborSet.add(destPeerId);
                            }
                        }
                    }

                    // Otherwise randomly select preferred neighbors
                    else {
                        List<Integer> candidates = new ArrayList<>(interestNeighborSet);
                        List<Integer> neighbors = randomlySelection(candidates, preferredNeighborNum);
                        tmpNeighborSet.addAll(neighbors);
                    }

                    // Write into log
                    if (!tmpNeighborSet.equals(preferredNeighborsSet)) {
                        StringBuilder content = new StringBuilder("Peer " + peerId + " has the preferred neighbors [");
                        if (tmpNeighborSet == null || tmpNeighborSet.size() == 0) content.append("]");
                        else {
                            for (int peer : tmpNeighborSet) content.append(peer).append(", ");
                            content.setCharAt(content.length() - 2, ']');
                        }
                        writeToLog(content.toString(), true);
                    }

                    // Send "unchoke" message to those preferred neighbors
                    for (int peer : tmpNeighborSet) {
                        if (!preferredNeighborsSet.contains(peer)) {
                            sendMessage(peerSocketMap.get(peer), messageHandler.generateUnchokeMessage());
                        }
                    }

                    // Send "choke" message
                    for (int peer : preferredNeighborsSet) {
                        if (!tmpNeighborSet.contains(peer) && optimisticallyNeighbor != null && optimisticallyNeighbor != peer) {
                            sendMessage(peerSocketMap.get(peer), messageHandler.generateChokeMessage());
                        }
                    }

                    // Reset download rate map and assign the updated neighbor set
                    downloadRateMap.replaceAll((i, v) -> 0.0);
                    preferredNeighborsSet = tmpNeighborSet;
                }
            }
        }, 100, unchokingInterval * 1000);

        // Start a new thread for timer task to periodically select optimistic neighbor
        Timer optimisticNeighborSelectionTask = new Timer();
        optimisticNeighborSelectionTask.schedule(new TimerTask() {
            @Override
            public void run() {
                if (peerBitfieldMap.size() == peerNum) {
                    Set<Integer> tmpNeighborSet = preferredNeighborsSet, tmpInterestSet = interestNeighborSet;
                    List<Integer> candidates = new ArrayList<>();

                    // Collect those dest peers that are interested and choked
                    for (int destPeerId : peerBitfieldMap.keySet()) {
                        if (!tmpNeighborSet.contains(destPeerId) && tmpInterestSet.contains(destPeerId)) {
                            candidates.add(destPeerId);
                        }
                    }

                    // Then randomly select one from candidates as optimistic neighbor
                    List<Integer> optNeighbor = randomlySelection(candidates, 1);
                    if (optNeighbor.size() == 0) optimisticallyNeighbor = null;
                    else {
                        optimisticallyNeighbor = optNeighbor.get(0);
                        sendMessage(peerSocketMap.get(optimisticallyNeighbor), messageHandler.generateUnchokeMessage());
                        writeToLog("Peer " + peerId + " has the optimistic neighbor " + optimisticallyNeighbor, true);
                    }
                }
            }
        }, 0, optimisticUnchokingInterval * 1000);

        // Wait until all the peers have the complete file
        while (true) {
            if (allHasCompleteFile()) {
                for (Thread thread : threadList) {
                    thread.interrupt();
                }
                preferredNeighborSelectionTask.cancel();
                optimisticNeighborSelectionTask.cancel();

                // Save file pieces into a complete file
                if (selfInfo.hasCompleteFile == 0) {
                    StringBuilder fileContent = new StringBuilder();
                    for (byte[] piece : filePieces) {
                        fileContent.append(new String(piece));
                    }
                    FileOutputStream stream = new FileOutputStream(new File(fileName));
                    stream.write(fileContent.toString().getBytes());
                }

                break;
            }
        }
    }

    public peerProcess(int pId) throws Exception {
        peerId = pId;

        // Read from "Common.cfg"
        Properties props = new Properties();
        props.load(new BufferedInputStream(new FileInputStream("Common.cfg")));
        preferredNeighborNum = Integer.parseInt(props.getProperty("NumberOfPreferredNeighbors"));
        unchokingInterval = Integer.parseInt(props.getProperty("UnchokingInterval"));
        optimisticUnchokingInterval = Integer.parseInt(props.getProperty("OptimisticUnchokingInterval"));
        fileName = props.getProperty("FileName");
        fileSize = Integer.parseInt(props.getProperty("FileSize"));
        pieceSize = Integer.parseInt(props.getProperty("PieceSize"));

        // Initial other properties
        threadList = new Vector<>();
        filePieces = new Vector<>();
        peerBitfieldMap = new ConcurrentHashMap<>();
        downloadRateMap = new ConcurrentHashMap<>();
        interestNeighborSet = Collections.synchronizedSet(new HashSet<>());
        preferredNeighborsSet = Collections.synchronizedSet(new HashSet<>());
        peerSocketMap = new ConcurrentHashMap<>();
        sentHavePieceSet = Collections.synchronizedSet(new HashSet<>());

        messageHandler = new MessageHandler();

        // Write parameters into log file
        String spaces = "\t\t\t\t\t";
        String content = "Peer" + peerId + " start, set variables to:\n" +
                spaces + "\t- NumberOfPreferredNeighbors: " + preferredNeighborNum + "\n" +
                spaces + "\t- UnchokingInterval: " + unchokingInterval + "\n" +
                spaces + "\t- OptimisticUnchokingInterval: " + optimisticUnchokingInterval + "\n" +
                spaces + "\t- FileName: " + fileName + "\n" +
                spaces + "\t- FileSize: " + fileSize + "\n" +
                spaces + "\t- PieceSize: " + pieceSize;
        writeToLog(content, false);
    }

    /**
     * Start a peer
     */
    public static void main(String[] args) throws Exception {
//        peerProcess peer = new peerProcess(1001);
        peerProcess peer = new peerProcess(Integer.parseInt(args[0]));
        peer.Init();
    }
}
