package src;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public class Server {
    private ConfigLoader.AppConfig config;
    private Selector selector;
    private Router router;

    public Server(ConfigLoader.AppConfig config) throws IOException {
        this.config = config;
        this.selector = Selector.open();
        this.router = new Router(config);
        BufferPool.init(config.server.bufferSize);
        setupServers();
    }

    private void setupServers() throws IOException {
        for (int port : config.server.ports) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.bind(new InetSocketAddress("0.0.0.0", port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("Server listening on port " + port);
        }
    }

    public void start() {
        while (true) {
            try {
                if (selector.select(100) == 0) {
                    checkProcesses();
                    continue;
                }

                checkProcesses();

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iter = selectedKeys.iterator();

                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        handleAccept(key);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void checkProcesses() {
        long now = System.currentTimeMillis();
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof ConnectionContext) {
                ConnectionContext context = (ConnectionContext) key.attachment();
                
                // Connection Timeout
                if (now - context.getLastActivityTime() > config.server.keepAliveTimeoutMs) {
                    context.close();
                    key.cancel();
                    continue;
                }

                if (context.getState() == ConnectionContext.State.PROCESSING) {
                    Process p = context.getCgiProcess();
                    if (p != null && !p.isAlive()) {
                        handleCgiFinished(key, context);
                    }
                }
            }
        }
    }

    private void handleCgiFinished(SelectionKey key, ConnectionContext context) {
        try {
            byte[] output = Files.readAllBytes(context.getCgiOutputPath());
            HttpResponse response = new HttpResponse();
            
            // Basic CGI header parsing
            String content = new String(output);
            int headerEnd = content.indexOf("\r\n\r\n");
            if (headerEnd == -1) headerEnd = content.indexOf("\n\n");
            
            if (headerEnd != -1) {
                String headerPart = content.substring(0, headerEnd);
                String[] lines = headerPart.split("\n");
                for (String line : lines) {
                    int colon = line.indexOf(':');
                    if (colon != -1) {
                        response.addHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                    }
                }
                
                int bodyStart = headerEnd + (content.startsWith("\r\n\r\n", headerEnd) ? 4 : 2);
                byte[] body = new byte[output.length - bodyStart];
                System.arraycopy(output, bodyStart, body, 0, body.length);
                response.setBody(body);
            } else {
                response.setBody(output);
            }
            
            context.setResponse(response.getBytes());
            key.interestOps(SelectionKey.OP_WRITE);
            Files.deleteIfExists(context.getCgiOutputPath());
        } catch (IOException e) {
            HttpResponse response = new HttpResponse();
            response.setStatus(500);
            response.setBody("CGI Error");
            context.setResponse(response.getBytes());
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        clientChannel.configureBlocking(false);
        
        ConnectionContext context = new ConnectionContext(clientChannel);
        context.getParser().setBodyLimit(config.server.maxBodySize);
        context.getParser().setHeaderLimit(config.server.maxHeaderSize);
        clientChannel.register(selector, SelectionKey.OP_READ, context);
    }

    private void handleRead(SelectionKey key) throws IOException {
        ConnectionContext context = (ConnectionContext) key.attachment();
        context.updateActivity();
        SocketChannel channel = context.getChannel();
        
        context.getReadBuffer().clear();
        int read = channel.read(context.getReadBuffer());
        
        if (read == -1) {
            context.close();
            key.cancel();
            return;
        }

        context.getReadBuffer().flip();
        HttpParser.State state = context.getParser().parse(context.getReadBuffer());

        if (state == HttpParser.State.DONE) {
            HandlerResult result = router.handle(context.getParser().getRequest());
            if (result.cgiResult != null) {
                context.setCgiProcess(result.cgiResult.process, result.cgiResult.outputPath);
                key.interestOps(0); // Stop listening for events until process finished
            } else {
                context.setResponse(result.response.getBytes());
                key.interestOps(SelectionKey.OP_WRITE);
            }
        } else if (state == HttpParser.State.ERROR) {
            HttpResponse response = new HttpResponse();
            // Check if it was a 413
            if (context.getParser().getRequest().getHeaders().get("content-length") != null) {
                // This is a bit simplified, but if it's an error and has content-length, assume it might be 413
                response.setStatus(413);
            } else {
                response.setStatus(400);
            }
            context.setResponse(response.getBytes());
            key.interestOps(SelectionKey.OP_WRITE);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        ConnectionContext context = (ConnectionContext) key.attachment();
        context.updateActivity();
        SocketChannel channel = context.getChannel();
        
        ByteBuffer writeBuffer = context.getWriteBuffer();
        if (writeBuffer != null && writeBuffer.hasRemaining()) {
            int written = channel.write(writeBuffer);
            context.addBytesWritten(written);
        }
        
        if (writeBuffer == null || !writeBuffer.hasRemaining()) {
            if (context.hasMoreToWrite()) {
                context.prepareNextWriteChunk();
                // Stay in OP_WRITE to handle backpressure and write next chunk
            } else {
                String connection = context.getParser().getRequest().getHeaders().get("connection");
                if (connection != null && connection.equalsIgnoreCase("close")) {
                    context.close();
                    key.cancel();
                } else {
                    // Reuse connection
                    context.releaseBuffers(); // frees old buffers without closing channel
                    ConnectionContext newContext = new ConnectionContext(channel);
                    newContext.getParser().setBodyLimit(config.server.maxBodySize);
                    newContext.getParser().setHeaderLimit(config.server.maxHeaderSize);
                    key.attach(newContext);
                    key.interestOps(SelectionKey.OP_READ);
                }
            }
        }
    }
}
