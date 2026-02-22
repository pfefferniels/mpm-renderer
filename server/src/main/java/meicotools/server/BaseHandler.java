package meicotools.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;

public abstract class BaseHandler implements HttpHandler {

    protected void writeCorsPreflight(HttpExchange exchange) throws IOException {
        Headers h = exchange.getResponseHeaders();
        addCorsHeaders(h);
        h.add("Allow", "OPTIONS, POST");
        h.add("Access-Control-Allow-Headers", "*");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    protected void addCorsHeaders(Headers h) {
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Credentials", "true");
    }

    protected void sendText(HttpExchange ex, int status, String msg) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "text/plain; charset=utf-8");
        addCorsHeaders(h);
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    protected void writeString(File f, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }
}
