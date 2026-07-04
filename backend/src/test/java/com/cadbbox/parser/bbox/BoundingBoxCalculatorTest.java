package com.cadbbox.parser.bbox;

import com.cadbbox.parser.step.StepParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BoundingBoxCalculatorTest {

    private final StepParser parser = new StepParser();
    private final BoundingBoxCalculator calc = new BoundingBoxCalculator();

    /**
     * Golden sample: a single part extracted from the real GMC2550WRS file
     * (PD=#263122, the "1PH8165-XXDXX-XXX1-1049497" cylindrical motor part).
     * Its 10 CARTESIAN_POINTs span the part's bounding box; expected values
     * computed independently from the same file by a reference Python script.
     */
    @Test
    void computesAabbOfSinglePartSample() throws Exception {
        StepParser.ParsedStepFile parsed;
        try (InputStream in = sample("samples/single-part.stp")) {
            parsed = parser.parse(in);
        }

        BoundingBox bbox = calc.computeForSinglePart(parsed);

        assertThat(bbox.isFinite()).isTrue();
        // Expected min/max (in metres, file's native frame):
        assertThat(bbox.minX()).isEqualTo(0.0, within(1e-9));
        assertThat(bbox.minY()).isEqualTo(-0.118454, within(1e-6));
        assertThat(bbox.minZ()).isEqualTo(-0.6075, within(1e-6));
        assertThat(bbox.maxX()).isEqualTo(0.118933, within(1e-6));
        assertThat(bbox.maxY()).isEqualTo(0.0, within(1e-9));
        assertThat(bbox.maxZ()).isEqualTo(0.0, within(1e-9));
        // Derived
        assertThat(bbox.sizeX()).isEqualTo(0.118933, within(1e-6));
        assertThat(bbox.sizeZ()).isEqualTo(0.6075, within(1e-6));
    }

    @Test
    void throwsWhenNoShapeRepresentationFound() {
        StepParser.ParsedStepFile empty =
                new StepParser.ParsedStepFile(new java.util.HashMap<>(), java.util.List.of(), "", "MILLIMETER");
        assertThatThrownBy(() -> calc.computeForSinglePart(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No shape representation");
    }

    private static InputStream sample(String classpath) throws Exception {
        // Tests put samples on the classpath via src/test/resources; for slice 1
        // we ship the file under test resources too so the build is self-contained.
        return new ClassPathResource(classpath).getInputStream();
    }
}
