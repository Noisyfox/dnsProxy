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
    private int mMaxFailCount;

    public CheckPointMachine(int initCheckPoint) {
        this(initCheckPoint, 5);
    }

    public CheckPointMachine(int initCheckPoint, int maxFailCount) {
        mCheckPoints.push(initCheckPoint);
        mMaxFailCount = maxFailCount;
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

    public void failImmediately(Throwable cause) {
        mIsFinished = true;
        mSuccess = false;

        throw new FailException(cause);
    }

    public boolean start() {
        mIsFinished = false;
        mSuccess = false;
        int failCount = 0;
        while (!mIsFinished) {
            if (failCount >= mMaxFailCount) {
                mSuccess = false;
                break;
            }
            int chkpoint = mCheckPoints.peek();
            try {
                run(chkpoint);
            } catch (Exception e) {
                e.printStackTrace();
            }
            failCount++;
        }

        return mSuccess;
    }

    public static class FailException extends RuntimeException {
        public FailException() {
        }

        public FailException(String message) {
            super(message);
        }

        public FailException(Throwable cause) {
            super(cause);
        }
    }
}
