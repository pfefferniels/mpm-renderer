package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import meico.mpm.Mpm;
import meico.mpm.elements.Performance;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.nio.file.Files;

/**
 * Test class for PerformService.
 * Tests the perform() method using the input.mei and input.mpm files from the tests folder.
 */
public class IsolationTest {
    private static Performance performance;

    @BeforeAll
    static void setUp() throws Exception {
        // Load test files from resources
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        Mpm mpm = new Mpm(mpmFile);
        performance = mpm.getPerformance(0);
        
        // Verify test files exist
        assertTrue(performance != null, "Failed to load performance from input.mpm");
    }

    @Test
    void testTempo() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("tempo_720"));
        double[] expected = { 720.0, 3600.0 };
        assertArrayEquals(range, expected);
    }

    @Test
    void testDynamics() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("dynamics_2520"));
        double[] expected = { 2520.0, 3600.0 };
        assertArrayEquals(range, expected);
    }

    @Test
    void testCombination() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("tempo_720", "dynamics_2520"));
        double[] expected = { 720.0, 3600.0 };
        assertArrayEquals(range, expected);
    }
}
