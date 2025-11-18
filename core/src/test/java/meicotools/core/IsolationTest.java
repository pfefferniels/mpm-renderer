package meicotools.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

import meico.mei.Mei;
import meico.mpm.Mpm;
import meico.mpm.elements.Performance;
import meico.msm.Msm;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.nio.file.Files;

/**
 * Test class for Isolation.
 */
public class IsolationTest {
    private static Performance performance;
    private static Mei mei;
    private static Msm msm;

    @BeforeAll
    static void loadMEI() throws Exception{
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        
        File meiFile = new File(classLoader.getResource("input.mei").getFile());
        mei = new Mei(meiFile);
        msm = ConvertService.meiToMsm(meiFile, 0);
    }

    @BeforeEach
    void loadPerformance() throws Exception {
        ClassLoader classLoader = IsolationTest.class.getClassLoader();
        
        File mpmFile = new File(classLoader.getResource("input.mpm").getFile());
        Mpm mpm = new Mpm(mpmFile);
        performance = mpm.getPerformance(0);
    }

    @Test
    void testTempo() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("tempo_720"));
        double[] expected = { 720.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testDynamics() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("dynamics_2520"));
        double[] expected = { 2520.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testRubato() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("rubato_3600"));
        double[] expected = { 3600.0, 4320.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testAccentuation() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("accentuationPattern_2880"));
        double[] expected = { 2880.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test 
    void testMovement() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("sustain-1440_start", "sustain-1440_moveDown", "sustain-1440_moveUp"));
        double[] expected = { 764.0, 3112.0 };
        assertArrayEquals(expected, range);
    }

    @Test
    void testCombination() throws Exception {
        double[] range = Isolation.isolateInstructions(performance, Set.of("tempo_720", "dynamics_2520"));
        double[] expected = { 720.0, 3600.0 };
        assertArrayEquals(expected, range);
    }

    @Test 
    void testMeasureIsolation() throws Exception {
        double[] range = Isolation.isolateMeasures(mei, msm, Set.of("1", "2"));
        double[] expected = { 720.0, 6480.0 };
        assertArrayEquals(expected, range);
    }
}
