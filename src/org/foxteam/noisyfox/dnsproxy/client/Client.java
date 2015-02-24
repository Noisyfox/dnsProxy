package org.foxteam.noisyfox.dnsproxy.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class Client {

    private InetAddress mServerAddress;
    private int mServerPort = 9473;

    public void startProxy() {
        try {
            mServerAddress = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return;
        }

        while (true) {
            // 连接服务器
            try {
                Socket mServerConnection = new Socket(mServerAddress, mServerPort);
                ClientWorker clientWorker = new ClientWorker(mServerConnection);
                clientWorker.start();
                try {
                    clientWorker.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
