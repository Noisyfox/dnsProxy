package org.foxteam.noisyfox.dnsproxy;

/**
 * Created by Noisyfox on 2015/3/2.
 */
public interface Application {
    boolean init(String[] args);

    boolean start();

    boolean stop();

    void waitToStop();

    boolean destroy();
}
