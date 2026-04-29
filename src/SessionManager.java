package src;

import java.util.HashMap;
import java.util.Map;

public class SessionManager {
    private static Map<String, Session> sessions = new HashMap<>();

    public static Session getSession(String id) {
        return sessions.get(id);
    }

    public static Session createSession() {
        Session s = new Session();
        sessions.put(s.getId(), s);
        return s;
    }
}
