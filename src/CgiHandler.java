package src;

import java.nio.file.Path;
import java.nio.file.Paths;

public class CgiHandler implements HttpHandler {
    @Override
    public HandlerResult handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception {
        String relativePath = request.getPath().substring(route.path.length());
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        Path targetPath = Paths.get(vhost.root, relativePath);
        
        CGIHandler.CGIResult cgiRes = CGIHandler.execute(route, request, targetPath.toString());
        if (cgiRes != null) {
            return new HandlerResult(cgiRes);
        } else {
            HttpResponse res = new HttpResponse();
            res.setStatus(500);
            res.setBody("CGI Execution Error".getBytes());
            return new HandlerResult(res);
        }
    }
}
