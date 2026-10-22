package com.voxel.cinematic;

/**
 * A timed input prompt — the Telltale/MCSM quick-time event.
 *
 * <p>The HUD shows the prompt and a draining timer; a successful press runs the
 * success line, a miss runs the fail line. MCSM never blocks the story on a
 * miss, so the caller's {@code onResult} callback decides what changes (a
 * stumble, a lost item, a different next beat) rather than halting the scene.
 * With {@link com.voxel.game.GameContext#qteAssist} on, a miss counts as a
 * success (accessibility / story mode).</p>
 */
public final class QuickTimeEvent {

    /** Input the prompt expects. */
    public enum Input {
        SPACE,
        CLICK
    }

    private final String prompt;
    private final Input input;
    private final float seconds;
    private final String speaker;
    private String successLine;
    private String failLine;

    public QuickTimeEvent(String prompt, Input input, float seconds) {
        this(prompt, input, seconds, "Narrator");
    }

    public QuickTimeEvent(String prompt, Input input, float seconds, String speaker) {
        this.prompt = prompt == null ? "" : prompt.trim();
        this.input = input == null ? Input.SPACE : input;
        this.seconds = Math.max(0.6f, Math.min(10f, seconds));
        this.speaker = speaker == null || speaker.trim().isEmpty() ? "Narrator" : speaker.trim();
    }

    /** Convenience factory. */
    public static QuickTimeEvent of(String prompt, Input input, float seconds) {
        return new QuickTimeEvent(prompt, input, seconds);
    }

    public QuickTimeEvent success(String line) {
        this.successLine = line;
        return this;
    }

    public QuickTimeEvent fail(String line) {
        this.failLine = line;
        return this;
    }

    public String prompt() { return prompt; }
    public Input input() { return input; }
    public float seconds() { return seconds; }
    public String speaker() { return speaker; }
    public String successLine() { return successLine; }
    public String failLine() { return failLine; }

    /** Key hint drawn under the prompt. */
    public String keyHint() {
        return input == Input.CLICK ? "CLICK" : "SPACE";
    }
}
