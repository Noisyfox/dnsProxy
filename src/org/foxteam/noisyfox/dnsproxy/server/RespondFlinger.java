package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/28.
 * 响应投递者，每个worker使用1个。Worker线程向其提交客户端发来的请求，并获得请求结果返回给客户端
 */
public class RespondFlinger {
    private final static int MAX_PACKET_SIZE = 65507;
    private final static int MAX_CACHED_PACKET = 50;

    private final ReentrantLock mPacketCacheLock = new ReentrantLock();

    private final ReentrantLock mRespondLock = new ReentrantLock();
    private final Condition mRespondCondition = mRespondLock.newCondition();

    private final ReentrantLock mWorkerLock = new ReentrantLock();
    private final HashMap<Integer, FlingerWorker> mPortMapper = new HashMap<Integer, FlingerWorker>();
    private final ExecutorService mWorkerPool = Executors.newCachedThreadPool();

    private final Queue<DatagramPacket> mDatagramPackets = new LinkedList<DatagramPacket>();

    private final Queue<DatagramPacket> mRespondQueue = new LinkedList<DatagramPacket>();

    private final InetAddress mDNSAddress;

    public RespondFlinger(InetAddress dnsAddress) {
        mDNSAddress = dnsAddress;
    }

    public void start() {

    }

    public void stop() {
        mWorkerPool.shutdownNow();
    }

    public void queueRequestAndNotify(UDPDataFrame request) {
        DatagramPacket packet = obtainDatagramPacket();
        packet.setAddress(mDNSAddress);
        packet.setPort(53);
        byte data[] = packet.getData();
        request.readData(data);
        packet.setData(data, 0, request.getDataLength());

        mWorkerLock.lock();
        try {
            FlingerWorker worker = mPortMapper.get(request.getPort());
            if (worker == null || !worker.queueRequestOrFail(packet)) {
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
                    return;
                }
                worker = new FlingerWorker(proxySocket, request.getPort());
                worker.queueRequestOrFail(packet);
                mPortMapper.put(request.getPort(), worker);
                mWorkerPool.execute(worker);
            }
        } finally {
            mWorkerLock.unlock();
        }
    }

    public void pollRespondOrWait(UDPDataFrame respond) throws InterruptedException {
        mRespondLock.lock();
        try {
            while (mRespondQueue.isEmpty()) {
                mRespondCondition.await();
            }
            DatagramPacket packet = mRespondQueue.poll();
            respond.fillData(packet.getData(), packet.getOffset(), packet.getLength());
            respond.setPort(packet.getPort());

            releaseDatagramPacket(packet);
        } finally {
            mRespondLock.unlock();
        }
    }

    private void queueRespondAndNotify(DatagramPacket packet) {
        mRespondLock.lock();
        try {
            mRespondQueue.offer(packet);
            mRespondCondition.signalAll();
        } finally {
            mRespondLock.unlock();
        }
    }

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

    private class FlingerWorker implements Runnable {

        private final ReentrantLock mThreadLock = new ReentrantLock();
        private final Condition mThreadCondition = mThreadLock.newCondition();

        private final ReentrantLock mRequestLock = new ReentrantLock();
        private final Condition mRequestCondition = mRequestLock.newCondition();

        private final Queue<DatagramPacket> mRequestQueue = new LinkedList<DatagramPacket>();
        private final DatagramSocket mSocket;
        private final int mPort;

        private volatile boolean mStopped = false;
        private Thread mCurrentThread;

        public FlingerWorker(DatagramSocket remoteSocket, int port) {
            mSocket = remoteSocket;
            mPort = port;
        }

        public boolean queueRequestOrFail(DatagramPacket request) {
            mRequestLock.lock();
            try {
                mRequestQueue.offer(request);
                mRequestCondition.signalAll();
                return !mStopped;
            } finally {
                mRequestLock.unlock();
            }
        }

        public void stop() {
            mStopped = true;
            mCurrentThread.interrupt();
        }

        @Override
        public void run() {
            mCurrentThread = Thread.currentThread();
            mThreadLock.lock();
            RequestThread requestThread = null;
            RespondThread respondThread = null;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (requestThread == null || !requestThread.mRunning) {
                        requestThread = new RequestThread();
                        requestThread.start();
                    }
                    if (respondThread == null || !respondThread.mRunning) {
                        respondThread = new RespondThread();
                        respondThread.start();
                    }
                    try {
                        mThreadCondition.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            } finally {
                mThreadLock.unlock();
                mSocket.close();

                // shutdown
                System.out.println("RespondFlinger Worker interrupted!");
                if (requestThread != null) {
                    requestThread.interrupt();
                }
                if (respondThread != null) {
                    respondThread.interrupt();
                }
                if (requestThread != null) {
                    try {
                        requestThread.join();
                    } catch (InterruptedException ignored) {
                    }
                }
                System.out.println("RespondFlinger requestThread stopped!");
                if (respondThread != null) {
                    try {
                        respondThread.join();
                    } catch (InterruptedException ignored) {
                    }
                }
                System.out.println("RespondFlinger respondThread stopped!");

                System.out.println("RespondFlinger Worker finished!");
            }
        }

        private class RequestThread extends Thread {
            public boolean mRunning = true;

            @Override
            public void run() {
                try {
                    doJob();
                } finally {
                    mThreadLock.lock();
                    try {
                        mRunning = false;
                        mThreadCondition.signalAll();
                    } finally {
                        mThreadLock.unlock();
                    }
                }
            }

            private void doJob() {
                while (true) {
                    DatagramPacket packet = null;
                    mRequestLock.lock();
                    try {
                        if (mRequestQueue.isEmpty()) {
                            try {
                                mRequestCondition.await();
                            } catch (InterruptedException e) {
                                return;
                            }
                        } else {
                            packet = mRequestQueue.poll();
                        }
                    } finally {
                        mRequestLock.unlock();
                    }
                    if (packet != null) {
                        try {
                            mSocket.send(packet);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        releaseDatagramPacket(packet);
                    }

                    if (interrupted()) {
                        return;
                    }
                }
            }
        }

        private class RespondThread extends Thread {
            public boolean mRunning = true;

            @Override
            public void run() {
                try {
                    doJob();
                } finally {
                    mThreadLock.lock();
                    try {
                        mRunning = false;
                        mThreadCondition.signalAll();
                    } finally {
                        mThreadLock.unlock();
                    }
                }
            }

            private void doJob() {
                while (true) {
                    try {
                        // 接收一个响应
                        DatagramPacket packet = obtainDatagramPacket();
                        packet.setData(packet.getData());
                        mSocket.receive(packet);

                        packet.setPort(mPort); // 映射端口

                        queueRespondAndNotify(packet);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    if (interrupted()) {
                        return;
                    }
                }
            }
        }
    }
}
