package com.company.message;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MakeMessage implements Massgae{

    public byte[] makeMessage(int len, char type, byte[] payload){
        byte[] MSG;
        byte[] MSG_length;
        byte MSG_Type =(byte)type;
        int index;
        if(type=='0'||type=='1'||type=='2'||type=='3'){
            MSG = new byte[len + 4];
            MSG_length= ByteBuffer.allocate(4).putInt(len).array();
                index = 0;
                for(byte x :MSG_length) {
                    MSG[index] = x;
                    index++;
                }
            MSG[index] = MSG_Type;}
        else if (type =='4'||type=='5'||type=='6'||type=='7') {

            MSG= new byte[len + 4];
            MSG_length = ByteBuffer.allocate(4).putInt(len).array();
            index = 0;
            for (byte x : MSG_length) {
                MSG[index] = x;
                index++;
            }
            MSG[index] = MSG_Type;
            for (byte x : payload) {
                MSG[index] = x;
                index++;
            }
        }
        else{
                MSG = new byte[0];
                System.out.println("ERROR in Message: " + type);
        }
        return MSG;
    }

    public byte[] getChokeMessage(){
        return makeMessage(1, CHOKE, null);
    }

    public byte[] getUnchokeMessage(){
        return makeMessage(1, UNCHOKE, null);
    }

    public byte[] getInterestedMessage(){
        return makeMessage(1, INTERESTED, null);
    }

    public byte[] getNotInterestedMessage(){
        return makeMessage(1, NOT_INTERESTED, null);
    }

    public byte[] getHaveMessage(int pieceIndex){
        byte[] payload = ByteBuffer.allocate(4).putInt(pieceIndex).array();
        return makeMessage(5, HAVE, payload);
    }

    public byte[] getBitfieldMessage(int[] bitfield){
        int len = 1 + (4 * bitfield.length);
        byte[] payload = new byte[len - 1];
        int counter = 0;
        for(int bit : bitfield){
            byte[] bitBytes = ByteBuffer.allocate(4).putInt(bit).array();
            for(byte b : bitBytes){
                payload[counter] = b;
                counter++;
            }
        }
        return makeMessage(len, BITFIELD, payload);
    }

    public byte[] getRequestMessage(int pieceIndex){
        byte[] payload = ByteBuffer.allocate(4).putInt(pieceIndex).array();
        return makeMessage(5, REQUEST, payload);
    }

    public byte[] getPieceMessage(int pieceIndex, byte[] piece){
        byte[] payload = new byte[4 + piece.length];
        byte[] indexBytes = ByteBuffer.allocate(4).putInt(pieceIndex).array();
        System.arraycopy(indexBytes, 0, payload, 0, 4);
        System.arraycopy(piece, 0, payload, 4, piece.length);
        return makeMessage((5 + piece.length), PIECE, payload);
    }

    public byte[] getHandshakeMessage(int peerID){
        byte[] MSG= new byte[32];
        byte[] protocolId = HANDSHAKE_HEADER.getBytes();
        byte[] zerobits = Zero_bits.getBytes();
        byte[] id = new byte[4];
        byte[] _peerID = ByteBuffer.allocate(4).putInt(peerID).array();
        if (_peerID.length> 4) {
            throw new ArrayIndexOutOfBoundsException("peerId max length is 4, while "
                    + Arrays.toString (_peerID) + "'s length is "+ _peerID.length);
        }
        int i = 0;
        for (byte b : _peerID) {
            id[i++] = b;
        }

        int index = 0;
        for(byte b : protocolId){
            MSG[index ] = b;
            index ++;
        }
        for(byte b : zerobits){
            MSG[index ] = b;
            index ++;
        }
        for(byte b : id){
            MSG[index ] = b;
            index ++;
        }
        return MSG;
    }
}


