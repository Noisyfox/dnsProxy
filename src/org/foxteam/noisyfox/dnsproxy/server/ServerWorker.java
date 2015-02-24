package org.foxteam.noisyfox.dnsproxy.server;

import java.net.Socket;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ServerWorker implements Runnable {
    private final Socket mClientSocket;

    public ServerWorker(Socket clientSocket) {
        mClientSocket = clientSocket;
    }

    @Override
    public void run() {
        // 首先，协商加密

    }
}
