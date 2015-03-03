package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.Utils;
import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
                int failCount;
                DatagramChannel proxyChannel;

                try {
                    proxyChannel = DatagramChannel.open();
                    for (failCount = 0; failCount < 5; failCount++) {
                        int randomPort = 3000 + random.nextInt(2000);
                        try {
                            proxyChannel.socket().bind(new InetSocketAddress(randomPort));
                            break;
                        } catch (IOException ignored) {
                        }
                    }
                    if (failCount >= 5) {
                        proxyChannel.close();
                        return;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }

                worker = new FlingerWorker(proxyChannel, request.getPort());
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
        private final DatagramChannel mChannel;
        private final DatagramSocket mSocket;
        private final int mPort;

        private volatile boolean mStopped = false;
        private Thread mCurrentThread;

        private static final long IDLE_TIMEOUT = 5000L;
        private static final long BLOCK_TIMEOUT = 2000L;
        private volatile AtomicLong mLastActiveTime = new AtomicLong(0L);

        public FlingerWorker(DatagramChannel remoteChannel, int port) {
            mChannel = remoteChannel;
            mSocket = mChannel.socket();
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

        private void stop() {
            mWorkerLock.lock();
            try {
                if (mPortMapper.get(mPort) == this) {
                    mPortMapper.remove(mPort);
                }
            } finally {
                mWorkerLock.unlock();
            }
            mStopped = true;
            if (mCurrentThread != null) {
                mCurrentThread.interrupt();
            }
        }

        private void testTimeout(boolean work) {
            long currentTime = System.currentTimeMillis();
            if (work) {
                mLastActiveTime.set(currentTime);
            } else {
                long lastTime = mLastActiveTime.get();
                if (currentTime > lastTime + IDLE_TIMEOUT) {
                    stop();
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void run() {
            mLastActiveTime.set(System.currentTimeMillis()); //记录时间

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
                mCurrentThread = null;
                mStopped = true;
                mThreadLock.unlock();

                mWorkerLock.lock();
                try {
                    if (mPortMapper.get(mPort) == this) {
                        mPortMapper.remove(mPort);
                    }
                } finally {
                    mWorkerLock.unlock();
                }

                // shutdown
                Utils.showVerbose("RespondFlinger Worker interrupted!");
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
                Utils.showVerbose("RespondFlinger requestThread stopped!");
                if (respondThread != null) {
                    try {
                        respondThread.join();
                    } catch (InterruptedException ignored) {
                    }
                }
                Utils.showVerbose("RespondFlinger respondThread stopped!");

                try {
                    mChannel.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                Utils.showVerbose("RespondFlinger Worker finished!");
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
                                mRequestCondition.await(BLOCK_TIMEOUT, TimeUnit.MILLISECONDS);
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
                        } catch (ClosedByInterruptException e) {
                            return;
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        releaseDatagramPacket(packet);
                        testTimeout(true);
                    } else {
                        testTimeout(false);
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
                    boolean work = true;
                    try {
                        // 接收一个响应
                        DatagramPacket packet = obtainDatagramPacket();
                        packet.setData(packet.getData());
                        mSocket.setSoTimeout((int) BLOCK_TIMEOUT);
                        mSocket.receive(packet);

                        packet.setPort(mPort); // 映射端口

                        queueRespondAndNotify(packet);
                    } catch (ClosedByInterruptException e) {
                        return;
                    } catch (SocketTimeoutException ignored) {
                        work = false;
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    testTimeout(work);

                    if (interrupted()) {
                        return;
                    }
                }
            }
        }
    }
}
