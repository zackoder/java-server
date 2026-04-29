package src;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Router {
    private ConfigLoader.AppConfig config;
    private Map<String, HttpHandler> handlers = new HashMap<>();

    public Router(ConfigLoader.AppConfig config) {
        this.config = config;
        handlers.put("ApiHandler", new ApiHandler());
        handlers.put("FileHandler", new FileHandler());
        handlers.put("CgiHandler", new CgiHandler());
    }

    public HandlerResult handle(HttpRequest request) {
        try {
            // Handle Session
            String sessionId = null;
            String cookieHeader = request.getHeaders().get("cookie");
            if (cookieHeader != null) {
                for (String part : cookieHeader.split(";")) {
                    part = part.trim();
                    if (part.startsWith("JSESSIONID=")) {
                        sessionId = part.substring("JSESSIONID=".length());
                        break;
                    }
                }
            }

            Session session;
            boolean newSession = false;
            if (sessionId == null || (session = SessionManager.getSession(sessionId)) == null) {
                session = SessionManager.createSession();
                newSession = true;
            }

            // Find best vhost
            String hostHeader = request.getHeaders().get("host");
            if (hostHeader != null && hostHeader.contains(":")) {
                hostHeader = hostHeader.split(":")[0];
            }
            
            ConfigLoader.VHostConfig vhost = findVHost(hostHeader);
            if (vhost == null) {
                return new HandlerResult(errorResponse(404));
            }
            
            // Find best route
            ConfigLoader.RouteConfig route = findRoute(vhost, request.getPath());
            if (route == null) {
                return new HandlerResult(errorResponse(404));
            }

            // Invoke Handler
            HttpHandler handler = handlers.get(route.handler);
            if (handler == null) {
                return new HandlerResult(errorResponse(500)); // Unknown handler
            }

            HandlerResult result = handler.handle(request, vhost, route);

            if (result.response != null && newSession) {
                result.response.addHeader("Set-Cookie", "JSESSIONID=" + session.getId() + "; Path=/; HttpOnly; SameSite=Strict");
            }

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new HandlerResult(errorResponse(500));
        }
    }

    private ConfigLoader.VHostConfig findVHost(String host) {
        if (config.vhosts.isEmpty()) return null;
        if (host == null) return config.vhosts.get(0); // Default fallback

        for (ConfigLoader.VHostConfig vhost : config.vhosts) {
            if (host.equalsIgnoreCase(vhost.domain)) {
                return vhost;
            }
        }
        return config.vhosts.get(0); // Fallback to first vhost
    }

    private ConfigLoader.RouteConfig findRoute(ConfigLoader.VHostConfig vhost, String path) {
        ConfigLoader.RouteConfig bestMatch = null;
        for (ConfigLoader.RouteConfig rc : vhost.routes) {
            if (path.startsWith(rc.path)) {
                if (bestMatch == null || rc.path.length() > bestMatch.path.length()) {
                    bestMatch = rc;
                }
            }
        }
        return bestMatch;
    }

    public HttpResponse errorResponse(int code) {
        HttpResponse res = new HttpResponse();
        res.setStatus(code);
        res.setBody(("Error " + code).getBytes());
        res.addHeader("Content-Type", "text/plain");
        return res;
    }
}
