package meicotools.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import meicotools.core.PerformService;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;

public class PerformHandler implements HttpHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

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
            if (req.mei == null || req.mpm == null) {
                sendText(exchange, 400, "Missing required fields 'mei' and 'mpm'.");
                return;
            }

            // 2) Prepare temp files
            File tmpDir     = Files.createTempDirectory("meico-perform").toFile();
            File meiFile    = new File(tmpDir, "input.mei");
            File mpmFile    = new File(tmpDir, "input.mpm");
            File rangesFile = new File(tmpDir, "ranges.txt");     // OUTPUT (filled by service)
            File outMidi    = new File(tmpDir, "result.mid");     // OUTPUT (filled by service)

            writeString(meiFile, req.mei);
            writeString(mpmFile, req.mpm);

            // 3) Call service
            try {
                PerformService.perform(
                        meiFile,
                        mpmFile,
                        rangesFile,
                        outMidi,
                        req.ids != null ? req.ids.toArray(new String[0]) : new String[0],
                        req.ppq != null ? req.ppq : 720,
                        req.movementIndex != null ? req.movementIndex : 0
                );
            } catch (Exception ex) {
                ex.printStackTrace();
                sendText(exchange, 500, "Perform failed: " + ex.getMessage());
                return;
            }

            if (!outMidi.exists() || !rangesFile.exists()) {
                sendText(exchange, 500, "Missing outputs: expected result.mid and ranges.txt.");
                return;
            }

            // 4) Build JSON response
            byte[] midiBytes = Files.readAllBytes(outMidi.toPath());
            String midiB64   = Base64.getEncoder().encodeToString(midiBytes);
            String rangesTxt = Files.readString(rangesFile.toPath(), StandardCharsets.UTF_8);

            Response payload = new Response();
            payload.midi_b64 = midiB64;
            payload.ranges   = rangesTxt;
            payload.filename = "result.mid";          // optional convenience
            payload.ppq      = req.ppq != null ? req.ppq : 720;
            payload.movementIndex = req.movementIndex != null ? req.movementIndex : 0;

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
        public String mei;              // required
        public String mpm;              // required
        public List<String> ids;        // optional
        public Integer ppq;             // optional (default 720)
        public Integer movementIndex;   // optional (default 0)
    }

    public static class Response {
        public String midi_b64;         // base64-encoded MIDI bytes
        public String ranges;           // text produced by service
        public String filename;         // optional (e.g., "result.mid")
        public Integer ppq;             // echo of effective params
        public Integer movementIndex;   // echo of effective params
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
