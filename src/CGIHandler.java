package src;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class CGIHandler {
    public static class CGIResult {
        public Process process;
        public Path outputPath;
    }

    public static CGIResult execute(ConfigLoader.RouteConfig rc, HttpRequest request, String scriptPath) {
        try {
            Path outputPath = Files.createTempFile("cgi-out-", ".tmp");
            ProcessBuilder pb = new ProcessBuilder("python3", scriptPath); // Hardcoded python3 for now
            
            Map<String, String> env = pb.environment();
            env.put("REQUEST_METHOD", request.getMethod());
            env.put("PATH_INFO", request.getPath());
            env.put("QUERY_STRING", request.getQueryParams().toString()); // Simple representation
            env.put("CONTENT_LENGTH", request.getHeaders().getOrDefault("content-length", "0"));
            env.put("CONTENT_TYPE", request.getHeaders().getOrDefault("content-type", ""));
            
            // Pass all HTTP headers as HTTP_*
            for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
                env.put("HTTP_" + header.getKey().toUpperCase().replace("-", "_"), header.getValue());
            }

            pb.redirectOutput(outputPath.toFile());
            pb.redirectError(ProcessBuilder.Redirect.INHERIT);

            if (request.getBody() != null) {
                Path inputPath = Files.createTempFile("cgi-in-", ".tmp");
                Files.write(inputPath, request.getBody());
                pb.redirectInput(inputPath.toFile());
            }

            CGIResult result = new CGIResult();
            result.process = pb.start();
            result.outputPath = outputPath;
            return result;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
