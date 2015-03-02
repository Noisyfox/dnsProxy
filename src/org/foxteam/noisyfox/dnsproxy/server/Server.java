package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.Application;

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
public class Server implements Application {

    private int mServerPort = 7373;
    private int mMaxThread = 50;

    private ServerSocket mListener;
    private ServerThread mThread;

    private ExecutorService mThreadPool = Executors.newFixedThreadPool(mMaxThread);

    private InetAddress mDnsProvider;

    public Server() {
        try {
            mDnsProvider = InetAddress.getByName("8.8.8.8");
        } catch (UnknownHostException ignored) {
        }
    }

    private boolean parseArgs(String args[]) {
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

    private void loop() {
        while (!Thread.interrupted()) {
            try {
                Socket socket = mListener.accept();
                // TODO:检查该socket对应的地址是不是对应了太多连接
                mThreadPool.execute(new ServerWorker(socket, mDnsProvider));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mThreadPool.shutdownNow();
    }

    private boolean listenClient() {
        ServerSocket listener;
        try {
            listener = new ServerSocket(mServerPort);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        mListener = listener;
        return true;
    }

    private void closeListen() {
        if (mListener == null) {
            return;
        }

        try {
            mListener.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mListener = null;
    }

    @Override
    public boolean init(String[] args) {
        if (!parseArgs(args)) {
            return false;
        }

        System.out.println(String.format("Server listen on %d, using dns %s", mServerPort, mDnsProvider.getHostAddress()));

        if (!listenClient()) {
            return false;
        }

        return true;
    }

    @Override
    public boolean start() {
        mThread = new ServerThread();
        mThread.start();

        return true;
    }

    @Override
    public boolean stop() {
        if (mThread == null) {
            return false;
        }
        mThread.interrupt();
        try {
            mThread.join();
        } catch (InterruptedException ignored) {
        }
        return true;
    }

    @Override
    public void waitToStop() {
        if (mThread == null) {
            return;
        }
        try {
            mThread.join();
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public boolean destroy() {
        closeListen();

        return true;
    }

    private class ServerThread extends Thread {
        @Override
        public void run() {
            loop();
        }
    }
}
