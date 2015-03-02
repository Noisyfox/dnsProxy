package org.foxteam.noisyfox.dnsproxy.client;

import org.foxteam.noisyfox.dnsproxy.Utils;
import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/27.
 * 请求投递者，程序运行期间公用。Worker线程从此处获得数据进行操作，并导入数据进行回复
 * 负责从本地端口获得dns请求并递交给worker线程，同时接收worker线程得到的返回值并返回给请求者
 */
public class RequestFlinger {
    private final static int MAX_PACKET_SIZE = 65507;
    private final static int MAX_CACHED_PACKET = 10;

    private final ReentrantLock mThreadLock = new ReentrantLock();
    private final ReentrantLock mPacketCacheLock = new ReentrantLock();

    private final ReentrantLock mRequestLock = new ReentrantLock();
    private final Condition mRequestCondition = mRequestLock.newCondition();
    private final ReentrantLock mRespondLock = new ReentrantLock();
    private final Condition mRespondCondition = mRespondLock.newCondition();

    private final Queue<DatagramPacket> mDatagramPackets = new LinkedList<DatagramPacket>();

    private final Queue<DatagramPacket> mRequestQueue = new LinkedList<DatagramPacket>();
    private final Queue<DatagramPacket> mRespondQueue = new LinkedList<DatagramPacket>();

    private final InetAddress mLocalHostAddress;
    private final DatagramChannel mLocalChannel;
    private final DatagramSocket mLocalSocket;

    private LocalListener mListenerThread;
    private LocalResponder mResponderThread;

    public RequestFlinger(DatagramChannel localChannel) throws UnknownHostException {
        mLocalChannel = localChannel;
        mLocalSocket = localChannel.socket();
        mLocalHostAddress = InetAddress.getByName("127.0.0.1");
    }

    public void start() {
        checkThread();
    }

    public void stop() {
        mThreadLock.lock();
        try {
            if (mListenerThread != null) {
                mListenerThread.interrupt();
            }
            if (mResponderThread != null) {
                mResponderThread.interrupt();
            }
            if (mListenerThread != null) {
                try {
                    mListenerThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (mResponderThread != null) {
                try {
                    mResponderThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            mListenerThread = null;
            mResponderThread = null;
        } finally {
            mThreadLock.lock();
        }
        try {
            mLocalChannel.disconnect();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void queueRespondAndNotify(UDPDataFrame respond) {
        DatagramPacket packet = obtainDatagramPacket();
        byte data[] = packet.getData();
        respond.readData(data);

        packet.setData(data, 0, respond.getDataLength());
        packet.setPort(respond.getPort());
        packet.setAddress(mLocalHostAddress);

        mRespondLock.lock();
        try {
            mRespondQueue.offer(packet);
            mRespondCondition.signalAll();
        } finally {
            mRespondLock.unlock();
        }
    }

    public void pollRequestOrWait(UDPDataFrame request) throws InterruptedException {
        mRequestLock.lock();
        try {
            while (mRequestQueue.isEmpty()) {
                mRequestCondition.await();
            }
            DatagramPacket packet = mRequestQueue.poll();
            request.fillData(packet.getData(), packet.getOffset(), packet.getLength());
            request.setPort(packet.getPort());

            releaseDatagramPacket(packet);
        } finally {
            mRequestLock.unlock();
        }
    }

    /**
     * 当本机没有请求的时候挂起
     */
    public void waitWhileRequestEmpty() throws InterruptedException {
        mRequestLock.lock();
        try {
            if (mRequestQueue.isEmpty()) {
                mRequestCondition.await();
            }
        } finally {
            mRequestLock.unlock();
        }
    }

    private void checkThread() {
        mThreadLock.lock();
        try {
            if (mListenerThread == null || !mListenerThread.isAlive()) {
                mListenerThread = new LocalListener();
                mListenerThread.start();
            }
            if (mResponderThread == null || !mResponderThread.isAlive()) {
                mResponderThread = new LocalResponder();
                mResponderThread.start();
            }
        } finally {
            mThreadLock.lock();
        }
    }

    private void queueRequestAndNotify(DatagramPacket packet) {
        mRequestLock.lock();
        try {
            mRequestQueue.offer(packet);
            mRequestCondition.signalAll();
        } finally {
            mRequestLock.unlock();
        }
    }

    /**
     * 监听线程，负责监听本机发出的dns请求
     */
    private class LocalListener extends Thread {
        @Override
        public void run() {
            while (true) {
                try {
                    // 接收一个请求
                    DatagramPacket packet = obtainDatagramPacket();
                    packet.setData(packet.getData());
                    mLocalSocket.receive(packet);

                    queueRequestAndNotify(packet);
                } catch (ClosedByInterruptException e) {
                    return;
                } catch (IOException e) {
                    e.printStackTrace();
                }

                if (interrupted()) {
                    return;
                }
            }
        }
    }

    /**
     * 响应线程，负责根据服务器响应本机请求
     */
    private class LocalResponder extends Thread {
        @Override
        public void run() {
            while (true) {
                DatagramPacket packet = null;
                mRespondLock.lock();
                try {
                    if (mRespondQueue.isEmpty()) {
                        try {
                            mRespondCondition.await();
                        } catch (InterruptedException e) {
                            return;
                        }
                    } else {
                        packet = mRespondQueue.poll();
                    }
                } finally {
                    mRespondLock.unlock();
                }
                if (packet != null) {
                    try {
                        mLocalSocket.send(packet);
                        Utils.showVerbose("client respond!");
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
}
