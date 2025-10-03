package meicotools.core;

import java.io.File;
import java.util.List;

import meico.mei.Mei;
import meico.msm.Msm;

public class ConvertService {
    /** Returns the first MSM from the export. */
    public Msm meiToMsm(File meiFile) throws Exception {
        try {
            Mei mei = new Mei(meiFile);
            if (mei.isEmpty()) throw new Exception("MEI file could not be loaded.");
            List<Msm> list = mei.exportMsm(720);
            if (list == null || list.isEmpty()) throw new Exception("No MSM generated.");
            Msm result = list.get(0);
            result.removeRests();
            // result.resolveSequencingMaps();
            return result;
        } catch (RuntimeException e) {
            throw new Exception("Failed to convert MEI to MSM.", e);
        }
    }
}
