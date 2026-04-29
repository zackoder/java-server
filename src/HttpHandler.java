package src;

public interface HttpHandler {
    HandlerResult handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception;
}
