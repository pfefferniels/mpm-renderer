package meicotools.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

public abstract class BaseHandler implements HttpHandler {

    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "https://listen.welte225.org",
            "https://play.welte225.org"
    );

    protected static final int MAX_BODY_BYTES = 1_048_576; // 1 MB
    protected static final RateLimiter RATE_LIMITER = new RateLimiter();

    protected void writeCorsPreflight(HttpExchange exchange) throws IOException {
        Headers h = exchange.getResponseHeaders();
        if (!addCorsHeaders(h, exchange)) {
            sendText(exchange, 403, "Forbidden");
            return;
        }
        h.add("Allow", "OPTIONS, POST");
        h.add("Access-Control-Allow-Headers", "Content-Type");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    protected boolean addCorsHeaders(Headers h, HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && ALLOWED_ORIGINS.contains(origin)) {
            h.add("Access-Control-Allow-Origin", origin);
            h.add("Vary", "Origin");
            return true;
        }
        return false;
    }

    protected boolean enforceOrigin(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !ALLOWED_ORIGINS.contains(origin)) {
            sendText(exchange, 403, "Forbidden");
            return false;
        }
        return true;
    }

    protected boolean enforceRateLimit(HttpExchange exchange) throws IOException {
        String clientIp = getClientIp(exchange);
        if (!RATE_LIMITER.isAllowed(clientIp)) {
            sendText(exchange, 429, "Too Many Requests");
            return false;
        }
        return true;
    }

    protected String getClientIp(HttpExchange exchange) {
        String cfIp = exchange.getRequestHeaders().getFirst("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank()) return cfIp.strip();

        String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].strip();

        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    protected void validateXml(String xml) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.newSAXParser().parse(new InputSource(new StringReader(xml)), new DefaultHandler());
        } catch (Exception e) {
            throw new IOException("Invalid XML");
        }
    }

    protected void sendText(HttpExchange ex, int status, String msg) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "text/plain; charset=utf-8");
        addCorsHeaders(h, ex);
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    protected byte[] readBodyLimited(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (InputStream is = exchange.getRequestBody()) {
            byte[] chunk = new byte[8192];
            int total = 0;
            int n;
            while ((n = is.read(chunk)) != -1) {
                total += n;
                if (total > MAX_BODY_BYTES) {
                    return null;
                }
                buf.write(chunk, 0, n);
            }
        }
        return buf.toByteArray();
    }

    protected void writeString(File f, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }

    protected void deleteTempDir(File dir) {
        if (dir == null || !dir.exists()) return;
        try {
            Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Failed to clean up temp dir: " + dir);
        }
    }
}
