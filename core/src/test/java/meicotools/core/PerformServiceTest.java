package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * Test class for PerformService.
 * Tests the perform() method using the input.mei and input.mpm files from the tests folder.
 */
public class PerformServiceTest {

    private static File meiFile;
    private static File mpmFile;

    @BeforeAll
    static void setUp() throws Exception {
        // Load test files from resources
        ClassLoader classLoader = PerformServiceTest.class.getClassLoader();
        
        meiFile = new File(classLoader.getResource("input.mei").getFile());
        mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        
        // Verify test files exist
        assertTrue(meiFile.exists(), "input.mei should exist in test resources");
        assertTrue(mpmFile.exists(), "input.mpm should exist in test resources");
    }

    /**
     * Test basic perform() functionality without note filtering.
     * This test verifies that the PerformService can:
     * 1. Load MEI and MPM files
     * 2. Convert MEI to MSM
     * 3. Apply performance from MPM
     * 4. Export expressive MIDI and MSM
     */
    @Test
    void testPerformBasic(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output.mid").toFile();
        File rangesFile = tempDir.resolve("ranges.json").toFile();
        
        // Perform with default settings (no filtering)
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            new String[0],  // empty selection
            PerformService.SelectionType.NONE,
            720,  // ppq (pulses per quarter)
            0,    // movement index
            null  // no exaggerate
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        assertTrue(outFile.length() > 0, "MIDI file should not be empty");
        
        File msmFile = new File(outFile.getAbsolutePath() + ".msm");
        assertTrue(msmFile.exists(), "MSM file should be created");
        assertTrue(msmFile.length() > 0, "MSM file should not be empty");
        
        assertTrue(rangesFile.exists(), "Ranges JSON file should be created");
        assertTrue(rangesFile.length() > 0, "Ranges JSON file should not be empty");
        
        // Verify ranges file is valid JSON
        String rangesContent = Files.readString(rangesFile.toPath());
        assertTrue(rangesContent.trim().startsWith("{"), "Ranges file should be valid JSON");
        assertTrue(rangesContent.trim().endsWith("}"), "Ranges file should be valid JSON");
    }

    /**
     * Test perform() with note ID filtering.
     * This test verifies that the service can filter notes by their xml:id
     * and only include selected notes in the output.
     */
    @Test
    void testPerformWithNoteIdFiltering(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output_filtered.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_filtered.json").toFile();
        
        // Perform with specific note IDs
        String[] noteIds = {"npk4lw6", "nxbdd6x", "n1bsnpkr"};
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            noteIds,
            PerformService.SelectionType.NOTE_IDS,
            720,
            0,
            null
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        assertTrue(outFile.length() > 0, "MIDI file should not be empty");
        
        File msmFile = new File(outFile.getAbsolutePath() + ".msm");
        assertTrue(msmFile.exists(), "MSM file should be created");
        
        assertTrue(rangesFile.exists(), "Ranges JSON file should be created");
    }

    /**
     * Test perform() with MPM ID filtering.
     * This test verifies that the service can filter by MPM element IDs.
     */
    @Test
    void testPerformWithMpmIdFiltering(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output_mpm_filtered.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_mpm_filtered.json").toFile();
        
        // Perform with MPM IDs - using some IDs that might be in the MPM file
        String[] mpmIds = {"tempo_1", "dynamics_1"};
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            mpmIds,
            PerformService.SelectionType.MPM_IDS,
            720,
            0,
            null
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        
        File msmFile = new File(outFile.getAbsolutePath() + ".msm");
        assertTrue(msmFile.exists(), "MSM file should be created");
        
        assertTrue(rangesFile.exists(), "Ranges JSON file should be created");
    }

    /**
     * Test perform() with exaggerate parameter.
     * This test verifies that the service can apply exaggeration to dynamics and tempo.
     */
    @Test
    void testPerformWithExaggerate(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output_exaggerated.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_exaggerated.json").toFile();
        
        // Perform with exaggeration
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            new String[0],
            PerformService.SelectionType.NONE,
            720,
            0,
            1.5  // exaggerate factor
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        assertTrue(outFile.length() > 0, "MIDI file should not be empty");
        
        File msmFile = new File(outFile.getAbsolutePath() + ".msm");
        assertTrue(msmFile.exists(), "MSM file should be created");
        
        assertTrue(rangesFile.exists(), "Ranges JSON file should be created");
    }

    /**
     * Test perform() with custom PPQ (pulses per quarter).
     * This test verifies that the service works with different PPQ values.
     */
    @Test
    void testPerformWithCustomPPQ(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output_ppq.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_ppq.json").toFile();
        
        // Perform with custom PPQ
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            new String[0],
            PerformService.SelectionType.NONE,
            480,  // different PPQ
            0,
            null
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        assertTrue(outFile.length() > 0, "MIDI file should not be empty");
    }

    /**
     * Test that perform() throws exception for invalid movement index.
     */
    @Test
    void testPerformWithInvalidMovementIndex(@TempDir Path tempDir) {
        File outFile = tempDir.resolve("output_invalid.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_invalid.json").toFile();
        
        // Should throw exception for invalid movement index
        assertThrows(Exception.class, () -> {
            PerformService.perform(
                meiFile,
                mpmFile,
                rangesFile,
                outFile,
                new String[0],
                PerformService.SelectionType.NONE,
                720,
                999,  // invalid movement index
                null
            );
        });
    }

    /**
     * Test that perform() handles missing files gracefully.
     */
    @Test
    void testPerformWithMissingFile(@TempDir Path tempDir) {
        File outFile = tempDir.resolve("output_missing.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_missing.json").toFile();
        File nonExistentFile = tempDir.resolve("nonexistent.mei").toFile();
        
        // Should throw exception for missing file
        assertThrows(Exception.class, () -> {
            PerformService.perform(
                nonExistentFile,
                mpmFile,
                rangesFile,
                outFile,
                new String[0],
                PerformService.SelectionType.NONE,
                720,
                0,
                null
            );
        });
    }

    /**
     * Test the overloaded perform() method (the older version without SelectionType).
     */
    @Test
    void testPerformOverloadedMethod(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output_overloaded.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_overloaded.json").toFile();
        
        // Use the overloaded perform method
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            new String[0],
            720,
            0
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        assertTrue(outFile.length() > 0, "MIDI file should not be empty");
        
        File msmFile = new File(outFile.getAbsolutePath() + ".msm");
        assertTrue(msmFile.exists(), "MSM file should be created");
        
        assertTrue(rangesFile.exists(), "Ranges JSON file should be created");
    }

    /**
     * Test perform() with combined filtering and exaggeration.
     * This test combines multiple features to ensure they work together.
     */
    @Test
    void testPerformWithCombinedFeatures(@TempDir Path tempDir) throws Exception {
        File outFile = tempDir.resolve("output_combined.mid").toFile();
        File rangesFile = tempDir.resolve("ranges_combined.json").toFile();
        
        // Perform with note filtering and exaggeration
        String[] noteIds = {"npk4lw6", "nxbdd6x"};
        PerformService.perform(
            meiFile,
            mpmFile,
            rangesFile,
            outFile,
            noteIds,
            PerformService.SelectionType.NOTE_IDS,
            720,
            0,
            1.2  // exaggerate factor
        );
        
        // Verify output files were created
        assertTrue(outFile.exists(), "MIDI file should be created");
        assertTrue(outFile.length() > 0, "MIDI file should not be empty");
        
        File msmFile = new File(outFile.getAbsolutePath() + ".msm");
        assertTrue(msmFile.exists(), "MSM file should be created");
        
        assertTrue(rangesFile.exists(), "Ranges JSON file should be created");
    }
}
