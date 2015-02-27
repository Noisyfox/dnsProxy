package org.foxteam.noisyfox.dnsproxy.crypto.aes;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by Noisyfox on 2015/2/25.
 */
public class AESOutputStream extends FilterOutputStream {

    private static final int BUFFER_SIZE = 48 * 1024; // 48KB输出缓冲

    private final ReentrantLock mIOLock = new ReentrantLock();
    private final AESFrame mFrame;
    private final byte mBuffer[] = new byte[BUFFER_SIZE];

    private int mBufferIndex = 0;

    public AESOutputStream(OutputStream out, byte key[], byte iv[]) {
        super(out);
        mFrame = new AESFrame(key, iv);
    }

    @Override
    public void write(int b) throws IOException {
        mIOLock.lock();
        try {
            int byteAvailable = BUFFER_SIZE - mBufferIndex;
            if (byteAvailable <= 0) {
                flushBuffer();
            }
            mBuffer[mBufferIndex++] = (byte) b;
        } finally {
            mIOLock.unlock();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if ((off < 0) || (off > b.length) || (len < 0) ||
                ((off + len) > b.length) || ((off + len) < 0)) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return;
        }


        mIOLock.lock();
        try {
            while (len > 0) {
                int byteAvailable = BUFFER_SIZE - mBufferIndex;
                if (byteAvailable <= 0) { // buffer空或者读完
                    flushBuffer();
                } else {
                    int byteNeedWriteCount = Math.min(byteAvailable, len);
                    System.arraycopy(b, off, mBuffer, mBufferIndex, byteNeedWriteCount);
                    off += byteNeedWriteCount;
                    mBufferIndex += byteNeedWriteCount;
                    len -= byteNeedWriteCount;
                }
            }
        } finally {
            mIOLock.unlock();
        }

    }

    @Override
    public void flush() throws IOException {
        flushBuffer();
        out.flush();
    }

    /**
     * Flush the internal buffer
     */
    private void flushBuffer() throws IOException {
        if (mBufferIndex > 0) {
            mFrame.fillData(mBuffer, 0, mBufferIndex);
            byte dataEnc[] = mFrame.getEncryptBytes();

            out.write(dataEnc);
            mBufferIndex = 0;
        }
    }
}
