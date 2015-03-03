package org.foxteam.noisyfox.dnsproxy.client;

import org.foxteam.noisyfox.dnsproxy.Application;
import org.json.simple.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.channels.DatagramChannel;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class Client implements Application {

    private DatagramChannel mLocalChannel;
    private InetAddress mServerAddress;
    private int mServerPort = 7373;
    private ClientThread mThread = null;

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
        return true;
    }

    private boolean readConfig(JSONObject cfg) {
        if (cfg == null) {
            return true;
        }

        String p = (String) cfg.get("port");
        if (p != null) {
            int port = -1;
            try {
                port = Integer.parseInt(p);
                if (port <= 0 || port >= 65536) {
                    port = -1;
                }
            } catch (NumberFormatException ignored) {
            }
            if (port == -1) {
                System.out.println("Illegal port number " + p);
                return false;
            } else {
                mServerPort = port;
            }
        }

        String s = (String) cfg.get("server");
        if (s != null) {
            try {
                mServerAddress = InetAddress.getByName(s);
            } catch (UnknownHostException e) {
                e.printStackTrace();
                System.out.println("Illegal server address " + s);
                return false;
            }
        }

        return true;
    }

    private boolean listenDNSPort() {
        // 开始监听本地端口
        DatagramChannel localChannel = null;
        try {
            localChannel = DatagramChannel.open();
            localChannel.socket().bind(new InetSocketAddress(53));
        } catch (IOException e) {
            e.printStackTrace();
            if (localChannel != null) {
                try {
                    localChannel.disconnect();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
            return false;
        }
        mLocalChannel = localChannel;

        return true;
    }

    private void closeDNSPort() {
        if (mLocalChannel == null) {
            return;
        }

        try {
            mLocalChannel.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mLocalChannel = null;
    }

    private void startProxy() {
        RequestFlinger requestFlinger;
        try {
            requestFlinger = new RequestFlinger(mLocalChannel);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            try {
                mLocalChannel.disconnect();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            return;
        }
        requestFlinger.start();

        while (true) {
            try {
                requestFlinger.waitWhileRequestEmpty();// 等待本机发出请求
            } catch (InterruptedException e) {
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
                    break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (Thread.interrupted()) {
                break;
            }
        }
        requestFlinger.stop();
    }

    @Override
    public boolean init(String[] args, JSONObject config) {
        if (!parseArgs(args)) {
            return false;
        }
        if (!readConfig(config)) {
            return false;
        }

        if (mServerAddress == null) {
            System.out.println("Must specifies the server address!");
            return false;
        }

        System.out.println(String.format("Client connect to %s:%d", mServerAddress.getHostAddress(), mServerPort));

        if (!listenDNSPort()) {
            return false;
        }

        return true;
    }

    @Override
    public boolean start() {
        mThread = new ClientThread();
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
        closeDNSPort();
        return true;
    }

    private class ClientThread extends Thread {
        @Override
        public void run() {
            startProxy();
        }
    }
}
