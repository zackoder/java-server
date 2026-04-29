package src;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class HttpParser {
    public enum State {
        REQUEST_LINE, HEADERS, BODY, CHUNK_SIZE, CHUNK_DATA, DONE, ERROR
    }

    private State state = State.REQUEST_LINE;
    private HttpRequest request = new HttpRequest();
    private StringBuilder buffer = new StringBuilder();
    private int contentLength = -1;
    private int bodyRead = 0;
    private int currentChunkSize = -1;
    private byte[] bodyBuffer;
    private boolean isChunked = false;
    private long bodyLimit = Long.MAX_VALUE;
    private long headerLimit = 8192;
    private long bytesReadForHeaders = 0;

    public void setBodyLimit(long limit) { this.bodyLimit = limit; }
    public void setHeaderLimit(long limit) { this.headerLimit = limit; }

    public State parse(ByteBuffer data) {
        while (data.hasRemaining() && state != State.DONE && state != State.ERROR) {
            if (state == State.REQUEST_LINE || state == State.HEADERS) {
                char c = (char) data.get();
                bytesReadForHeaders++;
                if (bytesReadForHeaders > headerLimit) {
                    state = State.ERROR;
                    return state;
                }
                if (c == '\n') {
                    String line = buffer.toString().trim();
                    buffer.setLength(0);
                    if (state == State.REQUEST_LINE) {
                        if (!line.isEmpty()) {
                            if (!parseRequestLine(line)) return state;
                            state = State.HEADERS;
                        }
                    } else if (state == State.HEADERS) {
                        if (line.isEmpty()) {
                            prepareBody();
                        } else {
                            parseHeader(line);
                        }
                    }
                } else if (c != '\r') {
                    buffer.append(c);
                }
            } else if (state == State.BODY) {
                int toRead = Math.min(data.remaining(), contentLength - bodyRead);
                data.get(bodyBuffer, bodyRead, toRead);
                bodyRead += toRead;
                if (bodyRead == contentLength) {
                    request.setBody(bodyBuffer);
                    state = State.DONE;
                }
            } else if (state == State.CHUNK_SIZE) {
                char c = (char) data.get();
                if (c == '\n') {
                    String line = buffer.toString().trim();
                    buffer.setLength(0);
                    if (!line.isEmpty()) {
                        currentChunkSize = Integer.parseInt(line, 16);
                        if (currentChunkSize == 0) {
                            state = State.DONE;
                            // In real implementation, handle trailers here
                        } else {
                            state = State.CHUNK_DATA;
                        }
                    }
                } else if (c != '\r') {
                    buffer.append(c);
                }
            } else if (state == State.CHUNK_DATA) {
                int toRead = Math.min(data.remaining(), currentChunkSize - bodyRead);
                // For simplicity, we'll append chunks to a growing byte array or list.
                // Reallocating for every chunk is inefficient but simple for now.
                if (bodyBuffer == null) bodyBuffer = new byte[0];
                byte[] temp = new byte[bodyBuffer.length + toRead];
                System.arraycopy(bodyBuffer, 0, temp, 0, bodyBuffer.length);
                data.get(temp, bodyBuffer.length, toRead);
                bodyBuffer = temp;
                bodyRead += toRead;
                
                if (bodyRead == currentChunkSize) {
                    bodyRead = 0;
                    state = State.CHUNK_SIZE;
                    // Skip the CRLF after chunk data
                    if (data.hasRemaining() && data.get(data.position()) == '\r') data.get();
                    if (data.hasRemaining() && data.get(data.position()) == '\n') data.get();
                }
            }
        }
        return state;
    }

    private boolean parseRequestLine(String line) {
        String[] parts = line.split(" ");
        if (parts.length == 3) {
            request.setMethod(parts[0]);
            try {
                request.setPath(parts[1]);
            } catch (IllegalArgumentException e) {
                state = State.ERROR;
                return false;
            }
            request.setVersion(parts[2]);
            return true;
        } else {
            state = State.ERROR;
            return false;
        }
    }

    private void parseHeader(String line) {
        int colon = line.indexOf(':');
        if (colon != -1) {
            request.addHeader(line.substring(0, colon), line.substring(colon + 1));
        }
    }

    private void prepareBody() {
        String cl = request.getHeaders().get("content-length");
        String te = request.getHeaders().get("transfer-encoding");
        
        if (te != null && te.equalsIgnoreCase("chunked")) {
            isChunked = true;
            state = State.CHUNK_SIZE;
        } else if (cl != null) {
            contentLength = Integer.parseInt(cl);
            if (contentLength > bodyLimit) {
                state = State.ERROR;
                return;
            }
            if (contentLength > 0) {
                bodyBuffer = new byte[contentLength];
                state = State.BODY;
            } else {
                state = State.DONE;
            }
        } else {
            state = State.DONE;
        }
    }

    public HttpRequest getRequest() { return request; }
    public State getState() { return state; }
}
