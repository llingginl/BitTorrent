package com.company.message;

public interface Massgae {
    public static final char CHOKE = '0';
    public static final char UNCHOKE = '1';
    public static final char INTERESTED = '2';
    public static final char NOT_INTERESTED = '3';
    public static final char HAVE = '4';
    public static final char BITFIELD = '5';
    public static final char REQUEST = '6';
    public static final char PIECE = '7';
    public static final String HANDSHAKE_HEADER = "P2PFILESHARINGPROJ";
    public static  final String Zero_bits ="0000000000";
    public static final int PIECE_INDEX_LEN = 4;
}
