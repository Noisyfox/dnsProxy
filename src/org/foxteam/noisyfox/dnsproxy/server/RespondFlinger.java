package org.foxteam.noisyfox.dnsproxy.server;

import org.foxteam.noisyfox.dnsproxy.dns.UDPDataFrame;

import java.net.InetAddress;

/**
 * Created by Noisyfox on 2015/2/28.
 * 响应投递者，每个worker使用1个。Worker线程向其提交客户端发来的请求，并获得请求结果返回给客户端
 */
public class RespondFlinger {

    public RespondFlinger(InetAddress dnsAddress){

    }

    public void start() {

    }

    public void stop() {

    }

    public void queueRequestAndNotify(UDPDataFrame request) {

    }

    public void pollRespondOrWait(UDPDataFrame respond) throws InterruptedException {

    }


}
