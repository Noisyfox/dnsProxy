package org.foxteam.noisyfox.dnsproxy.cpm;

import java.util.Stack;

/**
 * Created by Noisyfox on 2015/2/25.
 * 基于检查点的状态机
 */
public abstract class CheckPointMachine {
    protected abstract void run(int checkPoint) throws Exception;

    private boolean mIsFinished = false;
    private boolean mSuccess = false;
    private final Stack<Integer> mCheckPoints = new Stack<Integer>();
    //private final int mInitCheckPoint;

    public CheckPointMachine(int initCheckPoint) {
        //mInitCheckPoint = initCheckPoint;
        mCheckPoints.push(initCheckPoint);
    }

    public void setCheckPoint(int checkPoint) {
        mCheckPoints.push(checkPoint);
    }

    public int saveCheckPoint() {
        return mCheckPoints.size();
    }

    public void revertCheckPoint(int count) {
        if (count < 0) {
            count = 0;
        }
        while (mCheckPoints.size() > count) {
            mCheckPoints.pop();
        }
    }

    public void finish() {
        mIsFinished = true;
        mSuccess = true;
    }

    public void fail() {
        mIsFinished = true;
        mSuccess = false;
    }

    public void failImmediately() {
        mIsFinished = true;
        mSuccess = false;

        throw new FailException();
    }

    public void failImmediately(String reason) {
        mIsFinished = true;
        mSuccess = false;

        throw new FailException(reason);
    }

    public boolean start() {
        while (!mIsFinished) {
            int chkpoint = mCheckPoints.peek();
            try {
                run(chkpoint);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return mSuccess;
    }

    public static class FailException extends RuntimeException {
        public FailException() {
        }

        public FailException(String message) {
            super(message);
        }
    }
}
