package src;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class FileHandler implements HttpHandler {
    @Override
    public HandlerResult handle(HttpRequest request, ConfigLoader.VHostConfig vhost, ConfigLoader.RouteConfig route) throws Exception {
        String relativePath = request.getPath().substring(route.path.length());
        if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
        Path targetPath = Paths.get(vhost.root, relativePath);
        File file = targetPath.toFile();

        HttpResponse res = new HttpResponse();
        
        if (file.isDirectory()) {
            if (vhost.allowDirectoryListing) {
                res = listDirectory(file, request.getPath());
            } else {
                res.setStatus(403);
                res.setBody("Forbidden".getBytes());
            }
        } else if (request.getMethod().equals("GET")) {
            if (!file.exists()) {
                res.setStatus(404);
                res.setBody("Not Found".getBytes());
            } else {
                res.setBody(Files.readAllBytes(file.toPath()));
                String name = file.getName();
                if (name.endsWith(".html")) res.addHeader("Content-Type", "text/html");
                else if (name.endsWith(".css")) res.addHeader("Content-Type", "text/css");
                else if (name.endsWith(".js")) res.addHeader("Content-Type", "application/javascript");
                else res.addHeader("Content-Type", "application/octet-stream");
                res.setStatus(200);
            }
        } else if (request.getMethod().equals("POST")) {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, request.getBody() != null ? request.getBody() : new byte[0]);
            res.setStatus(201);
            res.setBody(("File uploaded successfully to " + targetPath).getBytes());
        } else if (request.getMethod().equals("DELETE")) {
            if (!file.exists()) {
                res.setStatus(404);
                res.setBody("Not Found".getBytes());
            } else if (file.delete()) {
                res.setStatus(204);
            } else {
                res.setStatus(500);
                res.setBody("Error deleting file".getBytes());
            }
        } else {
            res.setStatus(405);
            res.setBody("Method Not Allowed".getBytes());
        }

        return new HandlerResult(res);
    }

    private HttpResponse listDirectory(File dir, String path) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><body><h1>Index of ").append(path).append("</h1><ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                sb.append("<li><a href=\"").append(path).append(path.endsWith("/") ? "" : "/").append(f.getName()).append("\">")
                  .append(f.getName()).append(f.isDirectory() ? "/" : "").append("</a></li>");
            }
        }
        sb.append("</ul></body></html>");
        HttpResponse res = new HttpResponse();
        res.setStatus(200);
        res.setBody(sb.toString().getBytes());
        res.addHeader("Content-Type", "text/html");
        return res;
    }
}
