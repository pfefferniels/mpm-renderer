package meicotools.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import meicotools.core.ConvertService;
import meico.msm.Msm;

import java.io.*;
import java.nio.file.Files;

public class ConvertHandler extends BaseHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        File tmpDir = null;
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
            if (!enforceOrigin(exchange)) return;
            if (!enforceRateLimit(exchange)) return;

            byte[] body = readBodyLimited(exchange);
            if (body == null) {
                sendText(exchange, 413, "Request body too large");
                return;
            }

            Request req = MAPPER.readValue(body, Request.class);
            if (req.mei == null) {
                sendText(exchange, 400, "Missing required field 'mei'.");
                return;
            }

            validateXml(req.mei);

            tmpDir = Files.createTempDirectory("meico-convert").toFile();
            File meiFile = new File(tmpDir, "input.mei");
            writeString(meiFile, req.mei);

            Msm msm;
            try {
                msm = ConvertService.meiToMsm(meiFile, 0);
            } catch (Exception ex) {
                ex.printStackTrace();
                sendText(exchange, 500, "Convert failed");
                return;
            }

            Response payload = new Response();
            payload.msm = msm.toXML();

            byte[] json = MAPPER.writeValueAsBytes(payload);

            Headers h = exchange.getResponseHeaders();
            addCorsHeaders(h, exchange);
            h.add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(json);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            sendText(exchange, 500, "Internal Server Error");
        } finally {
            deleteTempDir(tmpDir);
        }
    }

    public static class Request {
        public String mei;
    }

    public static class Response {
        public String msm;
    }

}
