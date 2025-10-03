package meicotools.cli;

import meicotools.core.ConvertService;
import meico.msm.Msm;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

@Command(
        name = "convert",
        description = "Convert an MEI file to MSM."
)
public class ConvertCommand implements Runnable {

    @Option(names = "--mei", required = true, description = "Input MEI file")
    File mei;

    @Option(names = "--out", description = "Output MSM file (default: output.msm)")
    File out = new File("output.msm");

    @Override
    public void run() {
        try {
            if (!mei.isFile() || !mei.canRead()) {
                throw new IllegalArgumentException("Input MEI not readable: " + mei);
            }

            // Ensure output directory exists
            File parent = out.getAbsoluteFile().getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create output directory: " + parent);
            }

            // Convert MEI -> MSM
            ConvertService svc = new ConvertService();
            Msm msm = svc.meiToMsm(mei);

            // Try native writer if available, else fall back to text
            boolean wrote = tryWriteWithMsmWriter(msm, out);
            if (!wrote) {
                try (FileWriter fw = new FileWriter(out, false)) {
                    fw.write(msm.toString());
                }
            }

            System.out.println("Wrote " + out.getAbsolutePath());

        } catch (Exception e) {
            System.err.println("convert: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Uses Msm#setFile + writeMsm() if present; returns true on success, false otherwise.
     */
    private boolean tryWriteWithMsmWriter(Msm msm, File out) {
        try {
            // Most meico builds expose these:
            msm.setFile(out.getAbsolutePath());
            // writeMsm() returns boolean in many versions; if void, this will compile but we can't check the return.
            try {
                var method = msm.getClass().getMethod("writeMsm");
                Object result = method.invoke(msm);
                return !(result instanceof Boolean) || (Boolean) result;
            } catch (NoSuchMethodException e) {
                // Fallback if no writeMsm(): use toString in caller
                return false;
            }
        } catch (Exception e) {
            return false;
        }
    }
}
