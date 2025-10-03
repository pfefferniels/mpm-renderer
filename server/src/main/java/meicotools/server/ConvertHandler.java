package meicotools.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import meicotools.core.ConvertService;
import meico.msm.Msm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class ConvertHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ConvertService convertService = new ConvertService();

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeCorsPreflight(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                sendText(exchange, 405, "Method Not Allowed");
                return;
            }

            // 1) Parse request
            Request req = MAPPER.readValue(exchange.getRequestBody(), Request.class);
            if (req.mei == null) {
                sendText(exchange, 400, "Missing required field 'mei'.");
                return;
            }

            // 2) Write MEI to a temp file (ConvertService expects a File)
            File tmpDir = Files.createTempDirectory("meico-convert").toFile();
            File meiFile = new File(tmpDir, "input.mei");
            writeString(meiFile, req.mei);

            // 3) Convert MEI -> MSM
            Msm msm;
            try {
                msm = convertService.meiToMsm(meiFile);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendText(exchange, 500, "Convert failed: " + ex.getMessage());
                return;
            }

            // 4) Build JSON response (plain string MSM)
            Response payload = new Response();
            payload.msm = msm.toXML();

            byte[] json = MAPPER.writeValueAsBytes(payload);

            Headers h = exchange.getResponseHeaders();
            addCorsHeaders(h);
            h.add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            sendText(exchange, 500, "Internal Server Error: " + ex.getMessage());
        }
    }

    // --- DTOs ---

    public static class Request {
        public String mei; // required
    }

    public static class Response {
        public String msm; // MSM as text
    }

    // --- helpers ---

    private void writeCorsPreflight(HttpExchange exchange) throws IOException {
        Headers h = exchange.getResponseHeaders();
        addCorsHeaders(h);
        h.add("Allow", "OPTIONS, POST");
        h.add("Access-Control-Allow-Headers", "*");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private void addCorsHeaders(Headers h) {
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Credentials", "true");
    }

    private void sendText(HttpExchange ex, int status, String msg) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "text/plain; charset=utf-8");
        addCorsHeaders(h);
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private void writeString(File f, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }
}
