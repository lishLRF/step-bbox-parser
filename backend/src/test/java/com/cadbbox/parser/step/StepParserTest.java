package com.cadbbox.parser.step;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StepParserTest {

    private final StepParser parser = new StepParser();

    @Test
    void parsesSingleLineEntities() throws Exception {
        StepParser.ParsedStepFile f = parser.parse(stream("""
                ISO-10303-21;
                HEADER;
                FILE_NAME('x','2026',('a'),('o'),'PROG','PROG','');
                FILE_SCHEMA(('CONFIG_CONTROL_DESIGN'));
                ENDSEC;
                DATA;
                #1 = CARTESIAN_POINT('', (0.0, 0.0, 0.0));
                #2 = DIRECTION('', (0.0, 0.0, 1.0));
                #3 = AXIS2_PLACEMENT_3D('origin', #1, #2, #2);
                ENDSEC;
                END-ISO-10303-21;
                """));

        assertThat(f.schemas()).containsExactly("CONFIG_CONTROL_DESIGN");
        assertThat(f.sourceCadSystem()).isEqualTo("PROG");
        Map<Integer, StepEntity> e = f.entities();
        assertThat(e).hasSize(3);

        StepEntity cp = e.get(1);
        assertThat(cp.type()).isEqualTo("CARTESIAN_POINT");
        assertThat(cp.listAt(1).items()).containsExactly("0.0", "0.0", "0.0");

        StepEntity ax = e.get(3);
        assertThat(ax.type()).isEqualTo("AXIS2_PLACEMENT_3D");
        assertThat(ax.stringAt(0)).isEqualTo("origin");
        assertThat(ax.refAt(1)).isEqualTo(new StepEntity.Ref(1));
    }

    @Test
    void parsesCreoMultiLineEntitiesAndTrailingParens() throws Exception {
        // Mimics Creo: parameters on separate lines, doubled trailing ')'.
        StepParser.ParsedStepFile f = parser.parse(stream("""
                ISO-10303-21;
                HEADER;
                FILE_SCHEMA(('CONFIG_CONTROL_DESIGN'));
                ENDSEC;
                DATA;
                #44=CARTESIAN_POINT('',(0.E0,0.E0,0.E0)));
                #45=DIRECTION('',(0.E0,0.E0,1.E0)));
                #46=DIRECTION('',(1.E0,0.E0,0.E0)));
                #47=AXIS2_PLACEMENT_3D('GMC2550WRS\\X2\\4E3B673A\\X0\\',#44,
                #45,#46);
                ENDSEC;
                END-ISO-10303-21;
                """));
        StepEntity ax = f.entities().get(47);
        assertThat(ax.type()).isEqualTo("AXIS2_PLACEMENT_3D");
        assertThat(ax.stringAt(0)).isEqualTo("GMC2550WRS\\X2\\4E3B673A\\X0\\");
        assertThat(ax.refAt(1)).isEqualTo(new StepEntity.Ref(44));
        assertThat(ax.refAt(2)).isEqualTo(new StepEntity.Ref(45));
        assertThat(ax.refAt(3)).isEqualTo(new StepEntity.Ref(46));
    }

    @Test
    void handlesStarAndDollarPlaceholders() throws Exception {
        StepParser.ParsedStepFile f = parser.parse(stream("""
                ISO-10303-21;
                HEADER; ENDSEC;
                DATA;
                #10=ORIENTED_EDGE('',*,*,#11,.T.);
                ENDSEC;
                END-ISO-10303-21;
                """));
        StepEntity e = f.entities().get(10);
        List<Object> a = e.args();
        assertThat(a.get(0)).isEqualTo("''");
        assertThat(a.get(1)).isEqualTo("*");
        assertThat(a.get(2)).isEqualTo("*");
        assertThat(a.get(3)).isEqualTo(new StepEntity.Ref(11));
        assertThat(a.get(4)).isEqualTo(".T.");
    }

    private static ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
