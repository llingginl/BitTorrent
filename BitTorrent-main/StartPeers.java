import java.util.*;
import java.io.*;

/**
 * @author Jiaxu Zhu
 * @description Start multiple peerProcess processes automatically
 * (Just for running and testing locally, this class will be updated later for running processes on CISE servers remotely)
 */
public class StartPeers {
    private final List<Integer> peerList;

    private static class ExecPeerProcess implements Runnable {
        private final String command;

        public ExecPeerProcess(String command) {
            this.command = command;
        }

        @Override
        public void run() {
            try {
                Process process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});
                process.waitFor();
            }
            catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public StartPeers() throws Exception {
        String line;
        BufferedReader reader = new BufferedReader(new FileReader("PeerInfo.cfg"));
        peerList = new ArrayList<>();
        while ((line = reader.readLine()) != null) {
            String[] tokens = line.split(" ");
            peerList.add(Integer.parseInt(tokens[0]));
        }
    }

    public static void main(String[] args) throws Exception {
        StartPeers startPeers = new StartPeers();
        /*
            For each peer, create a process to run peerProcess program
         */
        for (int peerId : startPeers.peerList) {
            String subDir = "peer_" + peerId + " ";
            String command = "cd " + subDir + ";" + "java peerProcess " + peerId;
            Thread execPeerProcess = new Thread(new ExecPeerProcess(command));
            execPeerProcess.start();
        }
    }
}
