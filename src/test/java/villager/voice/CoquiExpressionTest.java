package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The base voice must be controllable: pitch, volume, tone and mood all shape
 * the rendered PCM. These checks use a synthetic tone so they need no model.
 */
public class CoquiExpressionTest {

    private static final int RATE = CoquiVitsTts.OUTPUT_RATE;

    private static float[] tone() {
        float[] samples = new float[RATE];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = 0.4f * (float) Math.sin(2.0 * Math.PI * 220.0 * i / RATE);
        }
        return samples;
    }

    private static double rms(float[] samples) {
        double sum = 0.0;
        for (float sample : samples) sum += sample * (double) sample;
        return Math.sqrt(sum / samples.length);
    }

    private static double brightness(float[] samples) {
        // Zero-crossing rate: a cheap, deterministic proxy for pitch/brightness.
        int crossings = 0;
        for (int i = 1; i < samples.length; i++) {
            if ((samples[i - 1] < 0f) != (samples[i] < 0f)) crossings++;
        }
        return crossings / (double) samples.length;
    }

    @Test
    public void volumeIsHonored() {
        SpeechOptions loud = new SpeechOptions(1.0, 0.0, 1.0, 0.0);
        SpeechOptions quiet = new SpeechOptions(1.0, 0.0, 0.5, 0.0);
        float[] a = CoquiVitsTts.shape(tone(), loud);
        float[] b = CoquiVitsTts.shape(tone(), quiet);
        assertTrue("a quieter profile must render quieter: " + rms(a) + " vs " + rms(b),
                rms(a) > rms(b) * 1.5);
    }

    @Test
    public void pitchRaisesBrightnessAndShortens() {
        float[] low = CoquiVitsTts.shape(tone(), new SpeechOptions(1.0, -4.0, 1.0, 0.0));
        float[] high = CoquiVitsTts.shape(tone(), new SpeechOptions(1.0, 4.0, 1.0, 0.0));
        assertTrue("a higher pitch must be brighter: "
                        + brightness(low) + " vs " + brightness(high),
                brightness(high) > brightness(low) * 1.2);
        assertTrue("a higher pitch resamples shorter", high.length < low.length);
    }

    @Test
    public void emotionChangesPitchAndScales() {
        SpeechOptions neutral = new SpeechOptions(1.0, 0.0, 1.0, 0.0);
        SpeechOptions scared = new SpeechOptions(1.0, 0.0, 1.0, 0.0, "scared", 0.0, false);
        float[] fear = CoquiVitsTts.shape(tone(), scared);
        float[] flat = CoquiVitsTts.shape(tone(), neutral);
        assertTrue("scared delivery is higher and faster",
                brightness(fear) > brightness(flat));
        float[] scales = CoquiVitsTts.scales(scared);
        assertEquals(3, scales.length);
        assertTrue("scared uses a breathier noise scale", scales[0] > 0.7f);
        assertEquals("the duration scale is 1/speed", 1.0f,
                CoquiVitsTts.scales(neutral)[1], 0.05f);
    }

    @Test
    public void questionDeliveryLiftsTheEnding() {
        SpeechOptions question = new SpeechOptions(1.0, 0.0, 1.0, 0.0,
                "neutral", 0.0, true);
        float[] asked = CoquiVitsTts.shape(tone(), question);
        float[] said = CoquiVitsTts.shape(tone(), new SpeechOptions(1.0, 0.0, 1.0, 0.0));
        int tail = asked.length - asked.length / 5;
        double askedTail = 0.0;
        double saidTail = 0.0;
        for (int i = tail; i < asked.length; i++) {
            askedTail += Math.abs(asked[i]);
            saidTail += Math.abs(said[i]);
        }
        assertTrue("a question must lift its ending: " + askedTail + " vs " + saidTail,
                askedTail > saidTail);
    }
}
