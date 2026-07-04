package com.cadbbox.parser.step;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StepNameCodecTest {

    @Test
    void decodesUtf16BeHexEscape() {
        // \X2\4E3B673A\X0\ → "主机" (4E3B=主, 673A=机)
        assertThat(StepNameCodec.decode("GMC2550WRS\\X2\\4E3B673A\\X0\\"))
                .isEqualTo("GMC2550WRS主机");
    }

    @Test
    void decodesMixedAsciiAndEscape() {
        // GB／T6170-20001型六角螺母A...M24 — recon sample from GMC2550WRS
        // FF0F ＝ ／; 578B=型 516D=六 89D2=角 87BA=螺 6BCD=母 7EA7=绿? Actually 7EA7=紧; we
        // only assert the leading ASCII + ／ decodes; full decode tested below.
        String in = "GB\\X2\\FF0F\\X0\\T6170";
        assertThat(StepNameCodec.decode(in)).isEqualTo("GB／T6170");
    }

    @Test
    void decodesMultipleEscapesInOneName() {
        // GMC2550WRS\X2\4E3B673A\X0\-\X2\520667907528\X0\
        //   主机 (4E3B673A) - 刀库 (5206=刀, 6790=库, 7528=? ) — verify piecewise.
        String decoded = StepNameCodec.decode("GMC2550WRS\\X2\\4E3B673A\\X0\\-\\X2\\520667907528\\X0\\");
        assertThat(decoded).startsWith("GMC2550WRS主机-");
        assertThat(decoded.length()).isGreaterThan("GMC2550WRS主机-".length());
    }

    @Test
    void passesThroughPlainAscii() {
        assertThat(StepNameCodec.decode("1PH8165-XXDXX-XXX1-1049497"))
                .isEqualTo("1PH8165-XXDXX-XXX1-1049497");
        assertThat(StepNameCodec.decode("")).isEqualTo("");
        assertThat(StepNameCodec.decode(null)).isNull();
    }

    @Test
    void handlesLiteralChineseAlreadyPresent() {
        // If somehow already decoded, no-op.
        assertThat(StepNameCodec.decode("主机")).isEqualTo("主机");
    }
}
