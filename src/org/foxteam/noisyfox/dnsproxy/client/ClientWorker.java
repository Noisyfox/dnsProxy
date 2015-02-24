package org.foxteam.noisyfox.dnsproxy.client;

import java.net.Socket;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ClientWorker extends Thread {
    private final Socket mServerSocket;

    public ClientWorker(Socket serverSocket) {
        mServerSocket = serverSocket;
    }

    @Override
    public void run() {
        // 首先，协商加密

    }
}
