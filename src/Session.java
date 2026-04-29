package src;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Session {
    private String id;
    private Map<String, Object> attributes = new HashMap<>();

    public Session() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setAttribute(String key, Object value) { attributes.put(key, value); }
    public Object getAttribute(String key) { return attributes.get(key); }
}
