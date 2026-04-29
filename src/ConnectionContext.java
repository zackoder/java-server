package src;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

public class ConnectionContext {
    public enum State {
        READING, PROCESSING, WRITING, CLOSED
    }

    private State state = State.READING;
    private HttpParser parser = new HttpParser();
    private ByteBuffer readBuffer;
    private ByteBuffer writeBuffer;
    private SocketChannel channel;
    private Process cgiProcess;
    private Path cgiOutputPath;
    private long lastActivityTime = System.currentTimeMillis();
    
    // Support for handling large writes and backpressure without allocating giant ByteBuffers
    private byte[] fullResponseBytes;
    private int bytesWritten = 0;

    public ConnectionContext(SocketChannel channel) {
        this.channel = channel;
        this.readBuffer = BufferPool.get();
    }

    public State getState() { return state; }
    public void setState(State state) { this.state = state; }

    public HttpParser getParser() { return parser; }
    public ByteBuffer getReadBuffer() { return readBuffer; }

    public void setResponse(byte[] response) {
        this.fullResponseBytes = response;
        this.bytesWritten = 0;
        this.state = State.WRITING;
        prepareNextWriteChunk();
    }

    public boolean prepareNextWriteChunk() {
        if (fullResponseBytes == null || bytesWritten >= fullResponseBytes.length) {
            return false;
        }
        
        if (writeBuffer == null) {
            writeBuffer = BufferPool.get();
        } else {
            writeBuffer.clear();
        }
        
        int toWrite = Math.min(writeBuffer.capacity(), fullResponseBytes.length - bytesWritten);
        writeBuffer.put(fullResponseBytes, bytesWritten, toWrite);
        writeBuffer.flip();
        return true;
    }
    
    public void addBytesWritten(int bytes) {
        this.bytesWritten += bytes;
    }
    
    public boolean hasMoreToWrite() {
        return fullResponseBytes != null && bytesWritten < fullResponseBytes.length;
    }

    public ByteBuffer getWriteBuffer() { return writeBuffer; }
    public SocketChannel getChannel() { return channel; }

    public void setCgiProcess(Process p, Path outputPath) {
        this.cgiProcess = p;
        this.cgiOutputPath = outputPath;
        this.state = State.PROCESSING;
    }

    public Process getCgiProcess() { return cgiProcess; }
    public Path getCgiOutputPath() { return cgiOutputPath; }
    public void updateActivity() { this.lastActivityTime = System.currentTimeMillis(); }
    public long getLastActivityTime() { return lastActivityTime; }
    
    public void releaseBuffers() {
        if (readBuffer != null) BufferPool.release(readBuffer);
        if (writeBuffer != null) BufferPool.release(writeBuffer);
        readBuffer = null;
        writeBuffer = null;
    }
    
    public void close() {
        this.state = State.CLOSED;
        releaseBuffers();
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (Exception e) {}
    }
}
