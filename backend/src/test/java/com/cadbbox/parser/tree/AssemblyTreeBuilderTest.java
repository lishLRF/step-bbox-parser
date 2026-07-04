package com.cadbbox.parser.tree;

import com.cadbbox.parser.bbox.BoundingBox;
import com.cadbbox.parser.bbox.BoundingBoxCalculator;
import com.cadbbox.parser.step.StepParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 2 golden test: a synthetic two-level assembly (ROOT → PART_A, PART_B)
 * where both parts are unit cubes at the origin, but each NAUO places its
 * instance at a different offset. Proves the transform chain lifts each part's
 * geometry into root coordinates correctly.
 */
class AssemblyTreeBuilderTest {

    private final StepParser parser = new StepParser();
    private final AssemblyTreeBuilder builder = new AssemblyTreeBuilder();
    private final BoundingBoxCalculator bboxCalc = new BoundingBoxCalculator();

    @Test
    void buildsMultiLevelTreeWithPerInstanceTransforms() throws Exception {
        StepParser.ParsedStepFile parsed;
        try (InputStream in = new ClassPathResource("samples/multi-asm.stp").getInputStream()) {
            parsed = parser.parse(in);
        }

        List<AssemblyNode> roots = builder.build(parsed);
        assertThat(roots).hasSize(1);
        AssemblyNode root = roots.get(0);
        assertThat(root.isAssembly()).isTrue();
        assertThat(root.children()).hasSize(2);

        // Each child is a leaf part; compute its AABB in root coordinates.
        // Instance names are "instanceA"/"instanceB" (from the NAUO placements);
        // the product label carries the part name "PART_A"/"PART_B".
        AssemblyNode a = findChildByLabel(root, "PART_A");
        AssemblyNode b = findChildByLabel(root, "PART_B");
        assertThat(a).as("PART_A instance").isNotNull();
        assertThat(b).as("PART_B instance").isNotNull();

        BoundingBox bbA = bboxCalc.computeForLeaf(parsed, a);
        BoundingBox bbB = bboxCalc.computeForLeaf(parsed, b);

        // PART_A placed at (+100,0,0); unit cube → AABB min=(100,0,0) max=(101,1,1)
        assertThat(bbA.minX()).isEqualTo(100.0, within(1e-9));
        assertThat(bbA.maxX()).isEqualTo(101.0, within(1e-9));
        // PART_B placed at (0,+200,0); unit cube → AABB min=(0,200,0) max=(1,201,1)
        assertThat(bbB.minY()).isEqualTo(200.0, within(1e-9));
        assertThat(bbB.maxY()).isEqualTo(201.0, within(1e-9));
    }

    private static AssemblyNode findChildByLabel(AssemblyNode parent, String label) {
        for (AssemblyNode c : parent.children()) {
            if (label.equals(c.productLabel())) return c;
        }
        return null;
    }

    private static org.assertj.core.data.Offset<Double> within(double tol) {
        return org.assertj.core.api.Assertions.within(tol);
    }
}
