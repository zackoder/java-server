package src;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

public class BufferPool {
    private static Queue<ByteBuffer> pool = new ArrayDeque<>();
    private static int bufferSize = 8192;

    public static void init(int size) {
        bufferSize = size;
    }

    public static ByteBuffer get() {
        ByteBuffer buf = pool.poll();
        if (buf == null) {
            return ByteBuffer.allocateDirect(bufferSize);
        }
        buf.clear();
        return buf;
    }

    public static void release(ByteBuffer buf) {
        if (buf != null && buf.isDirect() && buf.capacity() == bufferSize) {
            buf.clear();
            pool.offer(buf);
        }
    }
}
