package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.crypto.DH;
import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ServerWorker implements Runnable {
    private final static int MAX_CACHED_PACKET = 50;

    private final ExecutorService mThreadPool = Executors.newCachedThreadPool();
    private final HashMap<Integer, ProxyWorker> mThreadMap = new HashMap<Integer, ProxyWorker>();
    private final Socket mClientSocket;
    private final InetAddress mDNSAddress;

    public ServerWorker(Socket clientSocket) throws UnknownHostException {
        mDNSAddress = InetAddress.getByName("8.8.8.8");
        mClientSocket = clientSocket;
    }

    @Override
    public void run() {
        try {
            doJob();
        } finally {
            try {
                mClientSocket.close(); // 确保连接关闭
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        System.out.println("ServerWorker done!");
    }

    private void doJob() {
        OutputStream outputStream;
        InputStream inputStream;
        try {
            outputStream = mClientSocket.getOutputStream();
            inputStream = mClientSocket.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        // 首先，协商加密
        // 创建dh密钥对
        SecureRandom rnd = new SecureRandom();
        DH dh = new DH(256, rnd);
        dh.generateKeyPair();
        // 开始握手
        ServerHandshakeMachine handshakeMachine = new ServerHandshakeMachine(inputStream, outputStream, dh);
        dh = null;// 丢弃
        boolean handshakeSuccess = handshakeMachine.start();

        if (!handshakeSuccess) {
            return;
        }

        System.out.println("ServerWorker handshake success!");
        // 握手完成，开始加密传输

        outputStream = handshakeMachine.getEncryptedOutputStream();
        inputStream = handshakeMachine.getEncrpytedInputStream();

        // 本线程负责接收从客户端发来的请求，并创建Worker进行处理
        while (!Thread.interrupted()) {
            UDPDataFrame dataFrame = obtainDataFrame();
            try {
                dataFrame.readFromStream(inputStream);
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
            ProxyWorker worker = mThreadMap.get(dataFrame.getPort());
            if (worker == null || !worker.offerRequestOrFail(dataFrame)) {
                if (worker == null) {
                    worker = new ProxyWorker(outputStream);
                }

                // 随机分配UDP
                Random random = new Random();
                int failCount = 0;
                DatagramSocket proxySocket = null;
                while (failCount < 5) {
                    int randomPort = 3000 + random.nextInt(2000);
                    try {
                        proxySocket = new DatagramSocket(randomPort);
                        break;
                    } catch (SocketException ignored) {
                    }
                    failCount++;
                }
                if (proxySocket == null) {
                    continue;
                }
                worker.reset(proxySocket, dataFrame.getPort());
                mThreadMap.put(dataFrame.getPort(), worker);
                worker.offerRequestOrFail(dataFrame);
                mThreadPool.execute(worker);
            }
        }

        mThreadPool.shutdownNow();
    }

    private final ReentrantLock mDataFrameCacheLock = new ReentrantLock();
    private final Queue<UDPDataFrame> mDataFrameCache = new LinkedList<UDPDataFrame>();

    private UDPDataFrame obtainDataFrame() {
        if (mDataFrameCache.isEmpty()) {
            return new UDPDataFrame();
        }
        mDataFrameCacheLock.lock();
        try {
            if (mDataFrameCache.isEmpty()) {
                return new UDPDataFrame();
            } else {
                return mDataFrameCache.poll();
            }
        } finally {
            mDataFrameCacheLock.unlock();
        }
    }

    private void releaseDataFrame(UDPDataFrame dataFrame) {
        mDataFrameCacheLock.lock();
        try {
            if (mDataFrameCache.size() < MAX_CACHED_PACKET) {
                mDataFrameCache.offer(dataFrame);
            }
        } finally {
            mDataFrameCacheLock.unlock();
        }
    }

    private final static int MAX_PACKET_SIZE = 65507;
    private final Queue<DatagramPacket> mDatagramPackets = new LinkedList<DatagramPacket>();
    private final ReentrantLock mPacketCacheLock = new ReentrantLock();

    private DatagramPacket obtainDatagramPacket() {
        if (mDatagramPackets.isEmpty()) {
            byte[] buffer = new byte[MAX_PACKET_SIZE];
            return new DatagramPacket(buffer, buffer.length);
        }

        mPacketCacheLock.lock();
        try {
            if (mDatagramPackets.isEmpty()) {
                byte[] buffer = new byte[MAX_PACKET_SIZE];
                return new DatagramPacket(buffer, buffer.length);
            } else {
                return mDatagramPackets.poll();
            }
        } finally {
            mPacketCacheLock.unlock();
        }
    }

    private void releaseDatagramPacket(DatagramPacket packet) {
        mPacketCacheLock.lock();
        try {
            if (mDatagramPackets.size() < MAX_CACHED_PACKET) {
                mDatagramPackets.offer(packet);
            }
        } finally {
            mPacketCacheLock.unlock();
        }
    }

    private class ProxyWorker implements Runnable {

        private final OutputStream mOut;
        private final ReentrantLock mWorkerLock = new ReentrantLock();
        private final ReentrantLock mRequestLock = new ReentrantLock();
        private final Condition mRequestCondition = mRequestLock.newCondition();
        private final Queue<UDPDataFrame> mRequests = new LinkedList<UDPDataFrame>();

        private volatile boolean mWorkerFinished = false;

        private int mPort;
        private DatagramSocket mSocket;

        public ProxyWorker(OutputStream out) {
            mOut = out;
        }

        public void reset(DatagramSocket socket, int port) {
            mPort = port;
            mSocket = socket;
            mWorkerFinished = false;
        }

        /**
         * 向本Worker提交任务，或者如果此时本Worker已经退出，则失败
         */
        public boolean offerRequestOrFail(UDPDataFrame dataFrame) {
            mWorkerLock.lock();
            try {
                if (mWorkerFinished) {
                    return false;
                }

                // 此处追加任务
                mRequestLock.lock();
                try {
                    mRequests.offer(dataFrame);
                    mRequestCondition.signalAll();
                } finally {
                    mRequestLock.unlock();
                }

                return true;
            } finally {
                mWorkerLock.unlock();
            }
        }

        @Override
        public void run() {
            RespondThread respondThread = new RespondThread();
            respondThread.start();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    doJob();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            mWorkerFinished = false;
            Thread.interrupted();
            respondThread.interrupt();
            try {
                respondThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        private void doJob() {
            DatagramPacket packet = obtainDatagramPacket();
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    UDPDataFrame request = null;
                    mRequestLock.lock();
                    try {
                        request = mRequests.poll();
                        if (request == null) {
                            try {
                                mRequestCondition.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    } finally {
                        mRequestLock.unlock();
                    }

                    if (request == null) {
                        continue;
                    }

                    // 构造数据包
                    packet.setAddress(mDNSAddress);
                    packet.setPort(53);
                    byte data[] = packet.getData();
                    request.readData(data);
                    packet.setData(data, 0, request.getDataLength());
                    releaseDataFrame(request);

                    try {
                        mSocket.send(packet);
                    } catch (IOException e) {
                        e.printStackTrace();
                        Thread.currentThread().interrupt();
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            releaseDatagramPacket(packet);
        }

        private class RespondThread extends Thread {
            @Override
            public void run() {
                DatagramPacket packet = obtainDatagramPacket();
                try {
                    while (!interrupted()) {
                        try {
                            UDPDataFrame respondFrame = null;
                            try {
                                packet.setData(packet.getData());
                                mSocket.receive(packet);
                                System.out.println("Respond receive! Length:" + packet.getLength());
                                respondFrame = obtainDataFrame();
                                respondFrame.fillData(packet.getData(), packet.getOffset(), packet.getLength());
                                respondFrame.setPort(mPort);
                                respondFrame.writeToStream(mOut);
                                mOut.flush();
                            } catch (IOException e) {
                                e.printStackTrace();
                            } finally {
                                if (respondFrame != null) {
                                    releaseDataFrame(respondFrame);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                } finally {
                    releaseDatagramPacket(packet);
                }
            }
        }
    }
}
