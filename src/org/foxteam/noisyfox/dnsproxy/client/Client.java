package org.foxteam.noisyfox.dnsproxy.client;

import java.io.IOException;
import java.net.*;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class Client {

    private InetAddress mServerAddress;
    private int mServerPort = 7373;

    public boolean parseArgs(String args[]) {
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if ("-p".equals(arg) || "--port".equals(arg)) {
                if (i == args.length - 1) {
                    System.out.println("option " + arg + " requires argument");
                    return false;
                }
                String p = args[++i];
                try {
                    int port = Integer.parseInt(p);
                    if (port > 0 && port < 65536) {
                        mServerPort = port;
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
                System.out.println("Illegal port number " + p);
                return false;
            } else if ("-s".equals(arg) || "--server".equals(arg)) {
                if (i == args.length - 1) {
                    System.out.println("option " + arg + " requires argument");
                    return false;
                }
                String s = args[++i];
                try {
                    mServerAddress = InetAddress.getByName(s);
                    continue;
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
                System.out.println("Illegal server address " + s);
                return false;
            }
        }
        if (mServerAddress == null) {
            System.out.println("Must specifies the server address!");
            return false;
        }
        return true;
    }

    public void startProxy() {
        System.out.println(String.format("Client connect to %s:%d", mServerAddress.getHostAddress(), mServerPort));
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
