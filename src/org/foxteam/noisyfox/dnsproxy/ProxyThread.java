package org.foxteam.noisyfox.dnsproxy;

import java.io.IOException;
import java.net.*;
import java.util.Random;

/**
 * Created by Noisyfox on 2015/2/24.
 */
public class ProxyThread extends Thread {

    private DatagramPacket mPacket;
    private final Main.RespondThread mRespond;

    public ProxyThread(DatagramPacket packet, Main.RespondThread respondThread) {
        mPacket = packet;
        mRespond = respondThread;
    }

    @Override
    public void run() {
        Random random = new Random();
        int failCount = 0;
        DatagramSocket proxySocket = null;
        while (failCount < 5) {
            int randomPort = 3000 + random.nextInt(2000);
            try {
                proxySocket = new DatagramSocket(randomPort);
                break;
            } catch (SocketException e) {
                e.printStackTrace();
            }
            failCount++;
        }
        if (proxySocket == null) {
            return;
        }

        int respondPort = mPacket.getPort();
        InetAddress respondAddress = mPacket.getAddress();
        try {
            InetAddress dnsAddress = InetAddress.getByName("8.8.8.8");
            mPacket.setAddress(dnsAddress);
            mPacket.setPort(53);
            proxySocket.send(mPacket);
            proxySocket.receive(mPacket);
            mPacket.setPort(respondPort);
            mPacket.setAddress(respondAddress);
            mRespond.postRespond(mPacket);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            proxySocket.close();
        }
    }
}
