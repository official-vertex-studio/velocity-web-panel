package fr.vertexvelocity.webpanel.spring;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Compatibility adapter while HTTP transport is owned by the embedded Spring Boot/Tomcat server. */
final class ServletHttpExchangeAdapter extends HttpExchange {
    private final HttpServletRequest request;
    private final Headers requestHeaders = new Headers();
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private InputStream requestBody;
    private OutputStream outputBody = responseBody;
    private int responseCode = -1;
    private long responseLength = -1;

    ServletHttpExchangeAdapter(HttpServletRequest request) throws IOException {
        this.request = request;
        this.requestBody = request.getInputStream();
        Enumeration<String> names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            Enumeration<String> values = request.getHeaders(name);
            while (values != null && values.hasMoreElements()) requestHeaders.add(name, values.nextElement());
        }
    }

    @Override public Headers getRequestHeaders() { return requestHeaders; }
    @Override public Headers getResponseHeaders() { return responseHeaders; }
    @Override public URI getRequestURI() {
        String query = request.getQueryString();
        return URI.create(request.getRequestURI() + (query == null || query.isEmpty() ? "" : "?" + query));
    }
    @Override public String getRequestMethod() { return request.getMethod(); }
    @Override public com.sun.net.httpserver.HttpContext getHttpContext() { return null; }
    @Override public void close() { /* Spring owns the servlet request/response lifecycle. */ }
    @Override public InputStream getRequestBody() { return requestBody; }
    @Override public OutputStream getResponseBody() { return outputBody; }
    @Override public void sendResponseHeaders(int code, long length) { this.responseCode = code; this.responseLength = length; }
    @Override public InetSocketAddress getRemoteAddress() { return socketAddress(request.getRemoteAddr(), request.getRemotePort()); }
    @Override public InetSocketAddress getLocalAddress() { return socketAddress(request.getLocalAddr(), request.getLocalPort()); }
    @Override public int getResponseCode() { return responseCode; }
    @Override public String getProtocol() { return request.getProtocol(); }
    @Override public Object getAttribute(String name) { return attributes.containsKey(name) ? attributes.get(name) : request.getAttribute(name); }
    @Override public void setAttribute(String name, Object value) { attributes.put(name, value); request.setAttribute(name, value); }
    @Override public void setStreams(InputStream input, OutputStream output) {
        if (input != null) requestBody = input;
        if (output != null) outputBody = output;
    }
    @Override public HttpPrincipal getPrincipal() {
        Principal principal = request.getUserPrincipal();
        return principal == null ? null : new HttpPrincipal(principal.getName(), "velocity-web-panel");
    }

    void writeResponseTo(HttpServletResponse response) throws IOException {
        response.setStatus(responseCode < 0 ? HttpServletResponse.SC_OK : responseCode);
        for (Map.Entry<String, java.util.List<String>> header : responseHeaders.entrySet()) {
            boolean first = true;
            for (String value : header.getValue()) {
                if (first) { response.setHeader(header.getKey(), value); first = false; }
                else response.addHeader(header.getKey(), value);
            }
        }
        byte[] bytes = responseBody.toByteArray();
        if (!responseHeaders.containsKey("Content-Length") && responseLength >= 0) {
            response.setContentLengthLong(responseLength);
        } else if (!responseHeaders.containsKey("Content-Length") && responseCode != 204 && responseCode != 304) {
            response.setContentLength(bytes.length);
        }
        if (!"HEAD".equalsIgnoreCase(request.getMethod()) && responseCode != 204 && responseCode != 304) {
            response.getOutputStream().write(bytes);
        }
    }

    private static InetSocketAddress socketAddress(String host, int port) {
        try { return new InetSocketAddress(InetAddress.getByName(host), port); }
        catch (Exception ignored) { return InetSocketAddress.createUnresolved(host == null ? "unknown" : host, port); }
    }
}
