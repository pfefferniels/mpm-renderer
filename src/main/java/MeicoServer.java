import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import meico.mpm.Mpm;
import meico.msm.Msm;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MeicoServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static void main(String[] args) throws Exception {
        int port = getPort();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/convert", new ConvertHandler());
        server.setExecutor(null);
        System.out.println("MeicoServer listening on http://localhost:" + port + "/convert");
        server.start();
    }

    private static int getPort() {
        String p = System.getenv("PORT");
        if (p != null) {
            try { return Integer.parseInt(p); } catch (NumberFormatException ignored) {}
        }
        return 8080;
    }

    static class ConvertHandler implements HttpHandler {
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

                // Parse JSON with Jackson
                Request req = MAPPER.readValue(exchange.getRequestBody(), Request.class);

                if (req.msm == null || req.mpm == null) {
                    sendText(exchange, 400, "JSON must include fields 'msm' and 'mpm'");
                    return;
                }

                File tmpDir = Files.createTempDirectory("meico").toFile();
                File msmFile = new File(tmpDir, "input.msm");
                File mpmFile = new File(tmpDir, "input.mpm");
                writeString(msmFile, req.msm);
                writeString(mpmFile, req.mpm);

                File out = new File(tmpDir, "result.mid");
                int rc = generateMidi(null, mpmFile, msmFile, out);
                if (rc != 0 || !out.exists()) {
                    sendText(exchange, 500, "Failed to generate MIDI (code " + rc + ")");
                    return;
                }

                Headers h = exchange.getResponseHeaders();
                addCorsHeaders(h);
                h.add("Content-Type", "application/octet-stream");
                h.add("Content-Disposition", "attachment; filename=\"result.mid\"");
                byte[] midiBytes = Files.readAllBytes(out.toPath());
                exchange.sendResponseHeaders(200, midiBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(midiBytes);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
                sendText(exchange, 500, "Internal Server Error: " + ex.getMessage());
            }
        }

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
    }

    // --- Core conversion logic (unchanged) ---
    private static int generateMidi(File meiFile, File mpmFile, File msmFile, File outFile) {
        try {
            Msm msm;
            try {
                msm = new Msm(msmFile);
            } catch (RuntimeException e) {
                System.err.println("MSM file is not valid.");
                e.printStackTrace();
                return 65;
            }

            if (msm.isEmpty()) {
                System.err.println("No MSM data created.");
                return 1;
            }

            Mpm mpm;
            try {
                mpm = new Mpm(mpmFile);
            } catch (RuntimeException e) {
                System.err.println("MPM file is not valid.");
                e.printStackTrace();
                return 65;
            }

            msm.removeRests();
            msm.resolveSequencingMaps();

            var midi = msm.exportExpressiveMidi(mpm.getPerformance(0), true);
            midi.setFile(outFile.getAbsolutePath());
            System.out.println("Writing MIDI to file system");
            boolean ok = midi.writeMidi();
            return ok ? 0 : 1;

        } catch (Throwable t) {
            t.printStackTrace();
            return 1;
        }
    }

    // --- Request DTO ---
    public static class Request {
        public String msm;
        public String mpm;
    }

    // --- Helpers ---
    private static void writeString(File f, String s) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            w.write(s);
        }
    }

    private static void sendText(HttpExchange ex, int status, String msg) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.add("Content-Type", "text/plain; charset=utf-8");
        addCorsHeaders(h);
        byte[] data = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(data); }
    }

    private static void addCorsHeaders(Headers h) {
        h.add("Access-Control-Allow-Origin", "*");
        h.add("Access-Control-Allow-Credentials", "true");
    }
}
