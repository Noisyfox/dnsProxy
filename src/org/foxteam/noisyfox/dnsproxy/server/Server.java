package org.foxteam.noisyfox.dnsproxy.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class Server {

    private int mServerPort = 7373;
    private int mMaxThread = 50;

    private ExecutorService mThreadPool = Executors.newFixedThreadPool(mMaxThread);

    private InetAddress mDnsProvider;

    public Server() {
        try {
            mDnsProvider = InetAddress.getByName("8.8.8.8");
        } catch (UnknownHostException ignored) {
        }
    }

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
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
                System.out.println("Illegal port number " + p);
                return false;
            } else if ("-d".equals(arg) || "--dns".equals(arg)) {
                if (i == args.length - 1) {
                    System.out.println("option " + arg + " requires argument");
                    return false;
                }
                String d = args[++i];
                try {
                    mDnsProvider = InetAddress.getByName(d);
                    continue;
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
                System.out.println("Illegal dns provider " + d);
                return false;
            }
        }
        return true;
    }

    public void loop() {
        System.out.println(String.format("Server listen on %d, using dns %s", mServerPort, mDnsProvider.getHostAddress()));
        ServerSocket listener;
        try {
            listener = new ServerSocket(mServerPort);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        while (true) {
            try {
                Socket socket = listener.accept();
                // TODO:检查该socket对应的地址是不是对应了太多连接
                mThreadPool.execute(new ServerWorker(socket, mDnsProvider));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
