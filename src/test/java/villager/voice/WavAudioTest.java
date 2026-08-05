package villager.voice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WavAudioTest {
    @Test
    public void resamplingLongClipDoesNotOverflowIntegerProduct() {
        int sourceRate = 22050;
        int destinationRate = 40000;
        float[] samples = new float[67072];

        WavAudio converted = new WavAudio(sourceRate, samples).resampled(destinationRate);

        assertEquals(destinationRate, converted.sampleRate);
        assertEquals(Math.round(samples.length * (double) destinationRate / sourceRate),
                converted.samples.length);
        assertTrue(converted.samples.length > samples.length);
    }
}
