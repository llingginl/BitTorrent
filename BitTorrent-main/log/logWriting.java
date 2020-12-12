package log;

import java.io.*;

public class logWriting {
    static FileOutputStream logFile;
    static OutputStreamWriter writer;

    public static void stat(String logFileName) throws FileNotFoundException, UnsupportedEncodingException {
        logFile = new FileOutputStream(logFileName);
        writer = new OutputStreamWriter(logFile, "UTF-8");
    }

    public static void finish(){
        try {
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            logFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void generateLog(String logInfo){
        try {
            writer.write(logInfo + '\n');
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Showing the log message
     * Format: [Time]: Peer [peer_ID] [message]
     * */
    public static void logShowing(String logMessage){
        generateLog(logDate.getTime() + ": Peer " + logMessage);
    }
}
