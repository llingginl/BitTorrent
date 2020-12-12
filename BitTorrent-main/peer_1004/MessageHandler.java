import java.nio.*;
import java.util.*;

/**
 * @description Encapsulate all the operations about sending and receiving messages
 */
public class MessageHandler {
    public static final char CHOKE = '0';
    public static final char UNCHOKE = '1';
    public static final char INTERESTED = '2';
    public static final char NOT_INTERESTED = '3';
    public static final char HAVE = '4';
    public static final char BITFIELD = '5';
    public static final char REQUEST = '6';
    public static final char PIECE = '7';
    public static final String HANDSHAKE_HEADER = "P2PFILESHARINGPROJ";
    public static  final String ZERO_BITS ="0000000000";

    /**
     * General method for generating an actual message
     * @param len Message length, not include length field itself
     * @param type Message type
     * @param payload Actual data contained in the message
     * @return Message body
     */
    private synchronized byte[] generateActualMessage(int len, char type, List<Byte> payload) {
        List<Byte> tmpMsg = new ArrayList<>();
        // Add length field
        for (byte b : ByteBuffer.allocate(4).putInt(len).array()) tmpMsg.add(b);
        // Add type field
        tmpMsg.add((byte)type);
        // Add payload field if not null
        if (payload != null) tmpMsg.addAll(payload);

        byte[] msgBody = new byte[tmpMsg.size()];
        for (int i = 0; i < tmpMsg.size(); i++) msgBody[i] = tmpMsg.get(i);
        return msgBody;
    }

    /**
     * Generate CHOKE message
     */
    public synchronized byte[] generateChokeMessage() {
        return generateActualMessage(1, CHOKE, null);
    }

    /**
     * Generate UNCHOKE message
     */
    public synchronized byte[] generateUnchokeMessage() {
        return generateActualMessage(1, UNCHOKE, null);
    }

    /**
     * Generate INTERESTED message
     */
    public synchronized byte[] generateInterestedMessage() {
        return generateActualMessage(1, INTERESTED, null);
    }

    /**
     * Generate NOT_INTERESTED message
     */
    public synchronized byte[] generateNotInterestedMessage() {
        return generateActualMessage(1, NOT_INTERESTED, null);
    }

    /**
     * Generate HAVE message
     */
    public synchronized byte[] generateHaveMessage(int pieceIndex) {
        List<Byte> payload = new ArrayList<>();
        for (byte b : ByteBuffer.allocate(4).putInt(pieceIndex).array()) payload.add(b);
        return generateActualMessage(5, HAVE, payload);
    }

    /**
     * Generate BITFIELD message
     */
    public synchronized byte[] generateBitfieldMessage(boolean[] bitfield) {
        int byteLen = bitfield.length % 8 == 0 ? bitfield.length / 8 : bitfield.length / 8 + 1;
        List<Byte> payload = new ArrayList<>();
        for (int i = 0; i < byteLen; i++) {
            byte b = 0;
            for (int j = 0; j <= 7; j++) {
                if (j + i * 8 < bitfield.length && bitfield[j + i * 8])
                    b += (128 >> j);
            }
            payload.add(b);
        }
        return generateActualMessage(byteLen + 1, BITFIELD, payload);
    }

    /**
     * Generate REQUEST message
     */
    public synchronized byte[] generateRequestMessage(int pieceIndex) {
        List<Byte> payload = new ArrayList<>();
        for (byte b : ByteBuffer.allocate(4).putInt(pieceIndex).array()) payload.add(b);
        return generateActualMessage(5, REQUEST, payload);
    }

    /**
     * Generate PIECE message
     */
    public synchronized byte[] generatePieceMessage(int pieceIndex, byte[] content) {
        List<Byte> payload = new ArrayList<>();
        for (byte b : ByteBuffer.allocate(4).putInt(pieceIndex).array()) payload.add(b);
        for (byte b : content) payload.add(b);
        return generateActualMessage(5 + content.length, PIECE, payload);
    }

    /**
     * Generate handshake message
     * @param peerId peer Id field in the message
     * @return Message body
     */
    public synchronized byte[] generateHandshakeMessage(int peerId) {
        List<Byte> tmpMsg = new ArrayList<>();
        // Add header field
        for (byte b : HANDSHAKE_HEADER.getBytes()) tmpMsg.add(b);
        // Add zero-bits field
        for (byte b : ZERO_BITS.getBytes()) tmpMsg.add(b);
        // Add peer Id field
        for (byte b : ByteBuffer.allocate(4).putInt(peerId).array()) tmpMsg.add(b);

        byte[] msgBody = new byte[32];
        for (int i = 0; i < 32; i++) msgBody[i] = tmpMsg.get(i);
        return msgBody;
    }

    /**
     * Get peer Id field from handshake message
     */
    public synchronized int getPeerIdFromHandShakeMsg(byte[] handshakeMsg) {
        byte[] id = new byte[4];
        System.arraycopy(handshakeMsg, 28, id, 0, 4);
        return ByteBuffer.wrap(id).getInt();
    }

    /**
     * Get bitfield in the format of boolean array from a "pure" bitfield message
     * @param bitfieldMsg Pure bitfield message without "length" and "type" field
     * @param actualLen Actual length to represent bit fields
     */
    public synchronized boolean[] getBitfieldFromBitfieldMsg(byte[] bitfieldMsg, int actualLen) {
        boolean[] bitfield = new boolean[actualLen];
        int idx = 0;
        for (int i = 0; i < bitfieldMsg.length; i++) {
            byte b = bitfieldMsg[i];
            for (int j = 7; j >= 0; j--) {
                if (j + i * 8 < actualLen) bitfield[j + i * 8] = (b & 1) == 1;
                b = (byte)(b >> 1);
            }
        }
        return bitfield;
    }
}
