package org.foxteam.noisyfox.dnsproxy.client;

import java.io.IOException;
import java.net.*;

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

        // 开始监听本地端口
        final DatagramSocket localSocket;
        try {
            localSocket = new DatagramSocket(53);
        } catch (SocketException e) {
            e.printStackTrace();
            return;
        }

        RequestFlinger requestFlinger;
        try {
            requestFlinger = new RequestFlinger(localSocket);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            localSocket.close();
            return;
        }
        requestFlinger.start();

        while (true) {
            try {
                requestFlinger.waitWhileRequestEmpty();// 等待本机发出请求
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            }

            // 连接服务器
            try {
                Socket mServerConnection = new Socket(mServerAddress, mServerPort);
                ClientWorker clientWorker = new ClientWorker(mServerConnection, requestFlinger);
                clientWorker.start();
                try {
                    clientWorker.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (Thread.interrupted()) {
                break;
            }
        }

        requestFlinger.stop();
        localSocket.close();
    }
}
