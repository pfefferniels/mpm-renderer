package meicotools.cli;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import meicotools.core.ModifyService;
import meicotools.core.ModifyService.ModifyParams;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Command(
        name = "modify",
        description = "Modify an MPM file according to the given parameters."
)
public class ModifyCommand implements Runnable {

    @Option(names = "--in", required = true, description = "Input MPM file")
    File in;

    @Option(names = "--out", required = false, description = "Output MPM file (default: modified.mpm)")
    File out = new File("modified.mpm");

    @Option(names = "--params", required = false, description = "Inline JSON for ModifyParams")
    String paramsJson;

    @Option(names = "--params-file", required = false, description = "Path to JSON file with ModifyParams")
    File paramsFile;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public void run() {
        try {
            if (!in.isFile() || !in.canRead()) {
                throw new IllegalArgumentException("Input MPM not readable: " + in);
            }

            if (paramsJson != null && paramsFile != null) {
                throw new IllegalArgumentException("Provide either --params or --params-file, not both.");
            }

            ModifyParams params;
            if (paramsJson != null) {
                params = parseParamsJson(paramsJson);
            } else if (paramsFile != null) {
                params = parseParamsJson(readString(paramsFile));
            } else {
                // No params provided: use empty object (all fields null)
                params = new ModifyParams();
            }

            // Ensure output parent exists
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create output directory: " + parent);
            }

            ModifyService.modify(in, out, params);
            System.out.println("Wrote " + out.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("modify: " + e.getMessage());
            // picocli's top-level main should System.exit(nonzero). Here we just signal failure.
            // If you prefer, rethrow a runtime to let CliMain handle the exit code.
            System.exit(2);
        }
    }

    private static ModifyParams parseParamsJson(String json) throws IOException {
        // Nulls are allowed everywhere; unknown fields are ignored.
        return MAPPER.readValue(json, ModifyParams.class);
    }

    private static String readString(File f) throws IOException {
        return Files.readString(f.toPath(), StandardCharsets.UTF_8);
    }
}
