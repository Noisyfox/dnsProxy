package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.Application;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.net.*;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class Server implements Application {

    private int mServerPort = 7373;
    private int mMaxThread = 50;

    private ServerSocketChannel mServerChannel;
    private Selector mServerSelector;
    //private ServerSocket mListener;

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
                mServerSelector.select();
                Set<SelectionKey> keys = mServerSelector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    if (key.isAcceptable()) {  // 新的连接
                        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
                        SocketChannel sc = ssc.accept();
                        // TODO:检查该socket对应的地址是不是对应了太多连接
                        mThreadPool.execute(new ServerWorker(sc, mDnsProvider));
                    }
                    iter.remove(); //处理完事件的要从keys中删去
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mThreadPool.shutdownNow();
    }

    private boolean listenClient() {
        try {
            mServerChannel = ServerSocketChannel.open();
            mServerSelector = Selector.open();
            mServerChannel.socket().bind(new InetSocketAddress(mServerPort));
            mServerChannel.configureBlocking(false);
            mServerChannel.register(mServerSelector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    private void closeListen() {
        if (mServerSelector != null) {
            try {
                mServerSelector.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (mServerChannel != null) {
            try {
                mServerChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        mServerSelector = null;
        mServerChannel = null;
    }

    @Override
    public boolean init(String[] args, JSONObject config) {
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
