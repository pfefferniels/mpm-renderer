package meicotools.server;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import meico.midi.Midi;
import meicotools.core.PerformService;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class PerformHandler extends BaseHandler {

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

            System.out.println("Received request: " + 
                "mei length=" + req.mei.length() +
                ", mpm length=" + req.mpm.length() +
                ", ids=" + (req.ids != null ? req.ids : "null") +
                ", mpmIds=" + (req.mpmIds != null ? req.mpmIds : "null") +
                ", measures=" + (req.measures != null ? req.measures : "null") +
                ", exaggerate=" + req.exaggerate +
                ", sketchiness=" + req.sketchiness +
                ", exemplify=" + req.exemplify +
                ", context=" + req.context +
                ", ppq=" + req.ppq +
                ", movementIndex=" + req.movementIndex
            );

            // 2) Prepare temp files
            File tmpDir     = Files.createTempDirectory("meico-perform").toFile();
            File meiFile    = new File(tmpDir, "input.mei");
            File mpmFile    = new File(tmpDir, "input.mpm");
            File outMidi    = new File(tmpDir, "result.mid");

            writeString(meiFile, req.mei);
            writeString(mpmFile, req.mpm);

            PerformService.SelectionType selectionType = PerformService.SelectionType.NONE;
            ArrayList<String> selection = new ArrayList<>();
            if (req.ids != null && !req.ids.isEmpty()) {
                selectionType = PerformService.SelectionType.NOTE_IDS;
                selection = new ArrayList<>(req.ids);
            }
            if (req.mpmIds != null && !req.mpmIds.isEmpty()) {
                selectionType = PerformService.SelectionType.MPM_IDS;
                selection = new ArrayList<>(req.mpmIds);
            }
            if (req.measures != null && !req.measures.isEmpty()) {
                selectionType = PerformService.SelectionType.MEASURES;
                selection = new ArrayList<>(req.measures);
            }
            if (req.from != null && req.to != null) {
                selectionType = PerformService.SelectionType.RANGE;
                selection = new ArrayList<>(
                    Arrays.asList(req.from.toString(), req.to.toString())
                );
            }

            Set<String> selectionSet = selection.stream()
                .filter(id -> id != null)
                .collect(Collectors.toCollection(() -> new LinkedHashSet<String>()));
            
            // 3) Call service
            PerformService performService = new PerformService();
            try {
                Midi midi = performService.perform(
                        meiFile,
                        mpmFile,
                        selectionType,
                        selectionSet,
                        req.ppq != null ? req.ppq : 720,
                        req.movementIndex != null ? req.movementIndex : 0,
                        req.exaggerate,
                        req.sketchiness,
                        req.exemplify,
                        req.context,
                        req.isolate
                );
                midi.writeMidi(outMidi.getAbsolutePath());
            } catch (Exception ex) {
                ex.printStackTrace();
                sendText(exchange, 500, "Perform failed: " + ex.getMessage());
                return;
            }

            if (!outMidi.exists()) {
                sendText(exchange, 500, "Missing outputs: expected result.mid.");
                return;
            }

            // 4) Build JSON response
            byte[] midiBytes = Files.readAllBytes(outMidi.toPath());
            String midiB64   = Base64.getEncoder().encodeToString(midiBytes);

            Response payload = new Response();
            payload.midi_b64 = midiB64;
            payload.filename = "result.mid";
            payload.ppq      = req.ppq != null ? req.ppq : 720;
            payload.movementIndex = req.movementIndex != null ? req.movementIndex : 0;
            payload.noteIDs = performService.noteIDs;

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
        public String mei;
        public String mpm;

        // possible selection types
        public List<String> ids; 
        public List<String> mpmIds;
        public List<String> measures;
        public Double from; 
        public Double to;

        public Double exaggerate;
        public Double sketchiness;
        public Boolean exemplify;
        public Boolean context;
        public Boolean isolate;
        public Integer ppq;             // optional (default 720)
        public Integer movementIndex;   // optional (default 0)
    }

    public static class Response {
        public String midi_b64;         // base64-encoded MIDI bytes
        public String filename;         // optional (e.g., "result.mid")
        public Integer ppq;             // echo of effective params
        public Integer movementIndex;   // echo of effective params
        public List<String> noteIDs;    // IDs of notes that were performed
    }

}
