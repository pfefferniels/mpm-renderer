package meicotools.cli;

import java.io.File;

import meicotools.core.PerformService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "perform", description = "Perform MPM with MEI or MSM to MIDI")
class PerformCommand implements Runnable {
    @Option(names = "--mpm", required = true) File mpm;
    @Option(names = "--mei") File mei;
    @Option(names = "--ids") String ids;
    @Option(names = "--midi", defaultValue = "result.mid") File midi;
    @Option(names = "--ranges", defaultValue = "ranges.json") File ranges;

    public void run() {
        try {
            PerformService.perform(mei, mpm, ranges, midi, ids.split(","), 720, 0);
            System.out.println("Wrote " + midi.getAbsolutePath() + " and " + ranges.getAbsolutePath());
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        }
    }
}

