package org.foxteam.noisyfox.dnsproxy;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.Queue;

public class Main {

    private final static int MAX_PACKET_SIZE = 65507;

    public static void main(String[] args) {
        DatagramSocket serverSocket = null;
        RespondThread respondThread = null;
        try {
            serverSocket = new DatagramSocket(53);
            respondThread = new RespondThread(serverSocket);
            respondThread.start();

            while (true) {
                byte[] buffer = new byte[MAX_PACKET_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                serverSocket.receive(packet);
                new ProxyThread(packet, respondThread).start();
            }
        } catch (SocketException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (respondThread != null) {
                respondThread.interrupt();
                try {
                    respondThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (serverSocket != null) {
                serverSocket.close();
            }
        }

    }

    public static class RespondThread extends Thread {

        private final DatagramSocket mServerSocket;
        private final Queue<DatagramPacket> mPacketsQueue = new LinkedList<DatagramPacket>();

        public RespondThread(DatagramSocket serverSocket) {
            mServerSocket = serverSocket;
        }

        @Override
        public void run() {
            synchronized (mPacketsQueue) {
                while (true) {
                    if (mPacketsQueue.isEmpty()) {
                        try {
                            mPacketsQueue.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                            return;
                        }
                    }
                    while (!mPacketsQueue.isEmpty()) {
                        DatagramPacket packet = mPacketsQueue.poll();
                        try {
                            mServerSocket.send(packet);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                    if (interrupted()) {
                        return;
                    }
                }
            }
        }

        public void postRespond(DatagramPacket packet) {
            synchronized (mPacketsQueue) {
                mPacketsQueue.offer(packet);
                mPacketsQueue.notify();
            }
        }
    }
}
