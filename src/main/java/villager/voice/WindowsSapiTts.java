package villager.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local text-to-speech through the voices Windows already has.
 *
 * <p>Two engines are supported, both fully local (no model bundled, no service
 * called):</p>
 *
 * <ul>
 *   <li><b>WinRT</b> ({@link WinRtVoiceEngine}) — preferred. It sees the modern
 *       OneCore voices (George, Mark, Richard, Susan, Linda…) that the legacy
 *       SAPI stack hides, and accepts richer SSML.</li>
 *   <li><b>SAPI</b> ({@code System.Speech}) — fallback for older Windows
 *       builds and for machines without the WinRT projection.</li>
 * </ul>
 *
 * <p>The line is normalized into SSML by {@link #buildSsml(String, int, int)}:
 * sentence pauses, a breath before the first word, a longer beat after a
 * vocative ("Jesse, …") and emphasis on shouted words. That same SSML is
 * spoken by whichever engine is active, so quality improves without changing
 * the {@link TextToSpeech} contract.</p>
 *
 * <p>Every failure is non-fatal: {@link #isAvailable()} turns false and callers
 * fall back to the recorded clip corpus or caption-only speech.</p>
 *
 * <p>System properties:</p>
 * <ul>
 *   <li>{@code -Dvoxel.voice.tts=off} — disable local synthesis entirely.</li>
 *   <li>{@code -Dvoxel.voice.engine=auto|winrt|sapi} — choose the engine
 *       (default {@code auto}: WinRT when present, else SAPI).</li>
 *   <li>{@code -Dvoxel.voice.voice=<name or substring>} — force one installed
 *       voice for every character (useful for casting experiments).</li>
 * </ul>
 */
public final class WindowsSapiTts implements TextToSpeech {

    /** System property that disables local synthesis when set to {@code off}. */
    public static final String ENABLED_PROPERTY = "voxel.voice.tts";

    /** {@code auto} (default), {@code winrt} or {@code sapi}. */
    public static final String ENGINE_PROPERTY = "voxel.voice.engine";

    /** Exact or partial installed-voice name that overrides every profile. */
    public static final String VOICE_PROPERTY = "voxel.voice.voice";

    /** Longer lines would take seconds to render; dialogue is never this long. */
    private static final int MAX_TEXT_CHARS = 600;
    private static final long RENDER_TIMEOUT_MILLIS = 25000L;
    private static final long PROBE_TIMEOUT_MILLIS = 15000L;

    /**
     * SSML the SAPI fallback speaks. The prologue is small: the PowerShell
     * {@code -File} argument parser treats a leading minus as a parameter name,
     * so signed values used to need codes; the SSML path no longer passes them
     * as arguments, but {@link #code(int)} stays for compatibility.
     */
    private static final String SAPI_SPEAK_SCRIPT =
            "param(\n"
            + "    [Parameter(Mandatory=$true)][string]$TextFile,\n"
            + "    [Parameter(Mandatory=$true)][string]$OutFile,\n"
            + "    [string]$SsmlFile = \"\",\n"
            + "    [string]$Voice = \"\"\n"
            + ")\n"
            + "Add-Type -AssemblyName System.Speech\n"
            + "$text = [System.IO.File]::ReadAllText($TextFile, [System.Text.Encoding]::UTF8)\n"
            + "$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
            + "if ($Voice -ne \"\") { try { $synth.SelectVoice($Voice) } catch { } }\n"
            + "$synth.SetOutputToWaveFile($OutFile)\n"
            + "$spoke = $false\n"
            + "if ($SsmlFile -ne \"\" -and (Test-Path $SsmlFile)) {\n"
            + "    try {\n"
            + "        $ssml = [System.IO.File]::ReadAllText($SsmlFile, [System.Text.Encoding]::UTF8)\n"
            + "        $synth.SpeakSsml($ssml)\n"
            + "        $spoke = $true\n"
            + "    } catch { }\n"
            + "}\n"
            + "if (-not $spoke) { $synth.Speak($text) }\n"
            + "$synth.Dispose()\n";

    private static final String VOICES_SCRIPT =
            "Add-Type -AssemblyName System.Speech\n"
            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
            + "$s.GetInstalledVoices() | ForEach-Object { if ($_.Enabled) { Write-Output $_.VoiceInfo.Name } }\n"
            + "$s.Dispose()\n";

    // ── SSML shaping ─────────────────────────────────────────────────────────
    /** Shouted words (two or more capitals) get emphasis. */
    private static final Pattern ALL_CAPS = Pattern.compile("\\b[A-Z]{2,}\\b");
    /** "Jesse, ..." — a named address earns a beat before the rest. */
    private static final Pattern VOCATIVE = Pattern.compile("^([A-Z][a-zA-Z]{1,20}),\\s+");
    private static final Pattern COMMA = Pattern.compile(",(?!\\s*<break)");
    private static final Pattern SENTENCE_END = Pattern.compile("([.!?])(?=\\s)");

    private final Path workDir;
    private final WinRtVoiceEngine winRt;

    private volatile Boolean available;
    private volatile List<String> installedVoices;
    private volatile List<String> sapiVoices;
    private volatile String selectedEngine;
    private volatile boolean selectionLogged;

    public WindowsSapiTts(Path workDir) {
        this.workDir = workDir;
        this.winRt = new WinRtVoiceEngine(workDir);
    }

    /** True when local synthesis is enabled and the machine has a usable voice. */
    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            String property = System.getProperty(ENABLED_PROPERTY, "auto");
            boolean enabled = !(property != null && property.trim().equalsIgnoreCase("off"));
            boolean winRtUsable = enabled && isWindows() && !"sapi".equalsIgnoreCase(requestedEngine())
                    && winRt.isAvailable();
            boolean ok = enabled && isWindows() && (winRtUsable || !sapiVoiceList().isEmpty());
            available = ok;
            if (ok) logSelection();
            return ok;
        }
    }

    /** Installed voices of the active engine (empty when the probe failed). */
    public List<String> installedVoices() {
        List<String> cached = installedVoices;
        if (cached != null) return cached;
        synchronized (this) {
            if (installedVoices == null) {
                installedVoices = "winrt".equals(engine()) ? winRt.voices() : sapiVoiceList();
            }
            return installedVoices;
        }
    }

    @Override
    public WavAudio render(String text, VoiceProfile profile) throws IOException {
        if (text == null || text.trim().isEmpty()) {
            throw new IOException("cannot synthesize an empty line");
        }
        if (!isAvailable()) {
            throw new IOException("no local speech voice is available");
        }
        String line = text.trim();
        if (line.length() > MAX_TEXT_CHARS) {
            line = line.substring(0, MAX_TEXT_CHARS);
        }
        VoiceProfile voice = profile == null ? VoiceProfile.NARRATOR : profile;
        String voiceName = resolveVoice(voice);
        String ssml = buildSsml(line, voice.ratePercent(), voice.pitchPercent());

        if ("winrt".equals(engine())) {
            try {
                return winRt.renderSsml(ssml, voiceName);
            } catch (IOException winRtFailure) {
                // Transparent downgrade: the same SSML is valid SAPI input, but
                // the WinRT voice name does not exist on the SAPI side, so cast
                // the role again against the SAPI voice list.
                System.err.println("WindowsSapiTts: WinRT render failed, using SAPI ("
                        + winRtFailure.getMessage() + ")");
                voiceName = resolveFrom(sapiVoiceList(), voice);
            }
        }
        return renderSapi(line, ssml, voiceName);
    }

    /**
     * The installed voice that best matches a profile, or {@code ""} to let the
     * engine use its default when none of the candidates exist. A global
     * {@code -Dvoxel.voice.voice} override wins over every profile.
     */
    public String resolveVoice(VoiceProfile profile) {
        return resolveFrom(installedVoices(), profile);
    }

    /**
     * Stable cache tag for a profile: engine + the exact installed voice it
     * resolves to. Including it invalidates old cached WAVs when the voice
     * stack is upgraded (otherwise an old SAPI render would keep playing even
     * though WinRT has a better voice for the role).
     */
    public String cacheTag(VoiceProfile profile) {
        String voiceName = resolveVoice(profile);
        if (voiceName == null || voiceName.isEmpty()) voiceName = "default";
        return engine() + ":" + voiceName;
    }

    /** Cast a role against one engine's voice list (shared by the fallback). */
    private static String resolveFrom(List<String> voices, VoiceProfile profile) {
        String override = System.getProperty(VOICE_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            String forced = matchVoice(voices, override.trim());
            if (forced != null) return forced;
        }
        if (profile == null) return "";
        for (String candidate : profile.voiceCandidates()) {
            String found = matchVoice(voices, candidate);
            if (found != null) return found;
        }
        return "";
    }

    /** Exact name first, then a case-insensitive substring match. */
    private static String matchVoice(List<String> voices, String wanted) {
        if (wanted == null || wanted.isEmpty()) return null;
        for (String installed : voices) {
            if (installed.equalsIgnoreCase(wanted)) return installed;
        }
        String needle = wanted.toLowerCase(Locale.ROOT);
        for (String installed : voices) {
            if (installed.toLowerCase(Locale.ROOT).contains(needle)) return installed;
        }
        return null;
    }

    // ── Engine selection ─────────────────────────────────────────────────────

    private String requestedEngine() {
        String requested = System.getProperty(ENGINE_PROPERTY, "auto");
        return requested == null ? "auto" : requested.trim().toLowerCase(Locale.ROOT);
    }

    private String engine() {
        String cached = selectedEngine;
        if (cached != null) return cached;
        synchronized (this) {
            if (selectedEngine != null) return selectedEngine;
            String requested = requestedEngine();
            boolean winRtUsable = !"sapi".equals(requested) && winRt.isAvailable();
            selectedEngine = winRtUsable ? "winrt" : "sapi";
            return selectedEngine;
        }
    }

    private void logSelection() {
        if (selectionLogged) return;
        synchronized (this) {
            if (selectionLogged) return;
            selectionLogged = true;
            System.out.println("WindowsSapiTts: engine=" + engine()
                    + ", " + installedVoices().size() + " voice(s), narrator="
                    + resolveVoice(VoiceProfile.NARRATOR)
                    + (System.getProperty(VOICE_PROPERTY) != null
                        ? ", override=" + System.getProperty(VOICE_PROPERTY) : ""));
        }
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private WavAudio renderSapi(String text, String ssml, String voiceName) throws IOException {
        Files.createDirectories(workDir);
        Path textFile = Files.createTempFile(workDir, "line", ".txt");
        Path ssmlFile = Files.createTempFile(workDir, "line", ".xml");
        Path outFile = Files.createTempFile(workDir, "line", ".wav");
        Path logFile = Files.createTempFile(workDir, "line", ".log");
        try {
            Files.write(textFile, text.getBytes(StandardCharsets.UTF_8));
            Files.write(ssmlFile, ssml.getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(outFile);
            List<String> command = new ArrayList<String>();
            command.add(powershell());
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(scriptFile(workDir, "speak.ps1", SAPI_SPEAK_SCRIPT).toString());
            command.add(textFile.toAbsolutePath().toString());
            command.add(outFile.toAbsolutePath().toString());
            command.add(ssmlFile.toAbsolutePath().toString());
            command.add(voiceName);
            int exit = runProcess(command, logFile, RENDER_TIMEOUT_MILLIS);
            if (exit != 0 || !Files.isRegularFile(outFile) || Files.size(outFile) <= 44) {
                throw new IOException("local speech render failed (exit " + exit + "): "
                        + tail(logFile));
            }
            return WavAudio.read(outFile);
        } finally {
            deleteQuietly(textFile);
            deleteQuietly(ssmlFile);
            deleteQuietly(outFile);
            deleteQuietly(logFile);
        }
    }

    // ── SSML builder (pure, unit-tested) ─────────────────────────────────────

    /**
     * Turns plain authored text into the SSML both engines speak.
     *
     * <p>Punctuation carries the performance: a 120 ms breath before the first
     * word, 220 ms between sentences, 90 ms after commas, a longer 160 ms beat
     * after a vocative ("Jesse, …"), and {@code <emphasis>} on ALL-CAPS words.
     * The text is XML-escaped first, so author-written {@code &} or {@code <}
     * can never break the document.</p>
     */
    static String buildSsml(String text, int ratePercent, int pitchPercent) {
        String source = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        String body = escapeXml(source);

        Matcher caps = ALL_CAPS.matcher(body);
        StringBuffer emphasized = new StringBuffer();
        while (caps.find()) {
            caps.appendReplacement(emphasized, Matcher.quoteReplacement(
                    "<emphasis level=\"strong\">" + caps.group() + "</emphasis>"));
        }
        caps.appendTail(emphasized);
        body = emphasized.toString();

        Matcher vocative = VOCATIVE.matcher(body);
        if (vocative.find()) {
            body = vocative.replaceFirst("$1, <break time=\"160ms\"/>");
        }
        // The whitespace after the comma is still in the input, so the
        // replacement must not add its own space.
        body = COMMA.matcher(body).replaceAll(",<break time=\"90ms\"/>");
        body = SENTENCE_END.matcher(body).replaceAll("$1<break time=\"220ms\"/>");

        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis'"
                + " xml:lang='en-US'><prosody rate='" + percent(ratePercent)
                + "' pitch='" + percent(pitchPercent) + "'>"
                + "<break time='120ms'/>" + body + "</prosody></speak>";
    }

    /** Signed percent for SSML ({@code +6%}, {@code -6%}, {@code +0%}). */
    static String percent(int value) {
        return (value >= 0 ? "+" : "") + value + "%";
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ── Shared process plumbing (also used by WinRtVoiceEngine) ──────────────

    private List<String> sapiVoiceList() {
        List<String> cached = sapiVoices;
        if (cached != null) return cached;
        synchronized (this) {
            if (sapiVoices == null) sapiVoices = probeSapiVoices();
            return sapiVoices;
        }
    }

    private List<String> probeSapiVoices() {
        if (!isWindows()) return new ArrayList<String>();
        Path logFile = null;
        try {
            Files.createDirectories(workDir);
            logFile = Files.createTempFile(workDir, "voices", ".log");
            List<String> command = new ArrayList<String>();
            command.add(powershell());
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(scriptFile(workDir, "voices.ps1", VOICES_SCRIPT).toString());
            int exit = runProcess(command, logFile, PROBE_TIMEOUT_MILLIS);
            List<String> voices = new ArrayList<String>();
            if (exit == 0) {
                for (String line : new String(Files.readAllBytes(logFile),
                        StandardCharsets.UTF_8).split("\\r?\\n")) {
                    String name = line.trim();
                    if (!name.isEmpty()) voices.add(name);
                }
            }
            return voices;
        } catch (Exception error) {
            System.err.println("WindowsSapiTts: voice probe failed: " + error);
            return new ArrayList<String>();
        } finally {
            if (logFile != null) deleteQuietly(logFile);
        }
    }

    static Path scriptFile(Path workDir, String name, String content) throws IOException {
        Path script = workDir.resolve(name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(script) || Files.size(script) != bytes.length) {
            Files.write(script, bytes);
        }
        return script;
    }

    static int runProcess(List<String> command, Path logFile, long timeoutMillis)
            throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                return -1;
            }
            return process.exitValue();
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while rendering speech", interrupted);
        }
    }

    /** Encode a signed percent so PowerShell cannot read it as a flag. */
    static String code(int percent) {
        if (percent > 0) return "p" + percent;
        if (percent < 0) return "n" + (-percent);
        return "0";
    }

    static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isEmpty()) {
            Path system = Paths.get(root, "System32", "WindowsPowerShell", "v1.0",
                    "powershell.exe");
            if (Files.isRegularFile(system)) return system.toString();
        }
        return "powershell.exe";
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Temp files are best-effort: a lingering voice handle must not fail a line. */
    static void deleteQuietly(Path file) {
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException stillOpen) {
                // Windows can keep the WAV handle a moment after the process exits.
                try {
                    Thread.sleep(40L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static String tail(Path logFile) {
        try {
            String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8).trim();
            return log.length() > 300 ? log.substring(log.length() - 300) : log;
        } catch (Exception ignored) {
            return "";
        }
    }
}
