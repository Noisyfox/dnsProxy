package org.foxteam.noisyfox.dnsproxy;

import org.json.simple.JSONObject;

/**
 * Created by Noisyfox on 2015/3/2.
 */
public interface Application {
    boolean init(String[] args, JSONObject config);

    boolean start();

    boolean stop();

    void waitToStop();

    boolean destroy();
}
