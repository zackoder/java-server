package src;

public class HandlerResult {
    public HttpResponse response;
    public CGIHandler.CGIResult cgiResult;

    public HandlerResult(HttpResponse response) {
        this.response = response;
    }

    public HandlerResult(CGIHandler.CGIResult cgiResult) {
        this.cgiResult = cgiResult;
    }
    
    public HandlerResult() {}
}
