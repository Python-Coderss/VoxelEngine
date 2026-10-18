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

/**
 * Local text-to-speech through Windows SAPI (System.Speech).
 *
 * <p>No model is bundled and no service is called: this drives the voices the
 * machine already has (David, Hazel, Zira, …) through Windows PowerShell. The
 * line is written to a UTF-8 file and spoken with SSML so each character can
 * have its own rate and pitch; the result is a plain mono WAV that the voice
 * stack plays like any other clip. Renders are cached by the caller, so a line
 * is synthesized once per profile.</p>
 *
 * <p>Every failure is non-fatal: {@link #isAvailable()} turns false and callers
 * fall back to the recorded clip corpus or caption-only speech.</p>
 */
public final class WindowsSapiTts implements TextToSpeech {

    /** System property that disables local synthesis when set to {@code off}. */
    public static final String ENABLED_PROPERTY = "voxel.voice.tts";

    /** Longer lines would take seconds to render; dialogue is never this long. */
    private static final int MAX_TEXT_CHARS = 600;
    private static final long RENDER_TIMEOUT_MILLIS = 25000L;
    private static final long PROBE_TIMEOUT_MILLIS = 15000L;

    /**
     * Signed values are passed as codes ({@code n6}, {@code p6}, {@code 0}):
     * PowerShell's {@code -File} argument parser treats a leading minus as a
     * parameter name, so {@code -6} would fail to bind.
     */
    private static final String SPEAK_SCRIPT =
            "param(\n"
            + "    [Parameter(Mandatory=$true)][string]$TextFile,\n"
            + "    [Parameter(Mandatory=$true)][string]$OutFile,\n"
            + "    [string]$Voice = \"\",\n"
            + "    [string]$RateCode = \"0\",\n"
            + "    [string]$PitchCode = \"0\"\n"
            + ")\n"
            + "function Decode([string]$code) {\n"
            + "    if ($code -eq \"\" -or $code -eq \"0\") { return 0 }\n"
            + "    $sign = 1\n"
            + "    if ($code.StartsWith(\"n\")) { $sign = -1; $code = $code.Substring(1) }\n"
            + "    elseif ($code.StartsWith(\"p\")) { $code = $code.Substring(1) }\n"
            + "    return $sign * [int]$code\n"
            + "}\n"
            + "$Rate = Decode $RateCode\n"
            + "$Pitch = Decode $PitchCode\n"
            + "Add-Type -AssemblyName System.Speech\n"
            + "$text = [System.IO.File]::ReadAllText($TextFile, [System.Text.Encoding]::UTF8)\n"
            + "$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
            + "if ($Voice -ne \"\") { try { $synth.SelectVoice($Voice) } catch { } }\n"
            + "if ($Rate -gt 0) { $rateText = \"+$Rate%\" } elseif ($Rate -lt 0) { $rateText = \"$Rate%\" } else { $rateText = \"+0%\" }\n"
            + "if ($Pitch -gt 0) { $pitchText = \"+$Pitch%\" } elseif ($Pitch -lt 0) { $pitchText = \"$Pitch%\" } else { $pitchText = \"+0%\" }\n"
            + "$escaped = [System.Security.SecurityElement]::Escape($text)\n"
            + "$ssml = \"<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><prosody rate='$rateText' pitch='$pitchText'>$escaped</prosody></speak>\"\n"
            + "$synth.SetOutputToWaveFile($OutFile)\n"
            + "try { $synth.SpeakSsml($ssml) } catch { $synth.Speak($text) }\n"
            + "$synth.Dispose()\n";

    private static final String VOICES_SCRIPT =
            "Add-Type -AssemblyName System.Speech\n"
            + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer\n"
            + "$s.GetInstalledVoices() | ForEach-Object { if ($_.Enabled) { Write-Output $_.VoiceInfo.Name } }\n"
            + "$s.Dispose()\n";

    private final Path workDir;
    private volatile Boolean available;
    private volatile List<String> installedVoices;

    public WindowsSapiTts(Path workDir) {
        this.workDir = workDir;
    }

    /** True when local synthesis is enabled and the machine has a usable voice. */
    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            available = probe();
            return available;
        }
    }

    /** Installed SAPI voice names (empty when the probe failed). */
    public List<String> installedVoices() {
        if (installedVoices == null) {
            synchronized (this) {
                if (installedVoices == null) {
                    installedVoices = probeVoices();
                }
            }
        }
        return installedVoices;
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
        Files.createDirectories(workDir);
        Path textFile = Files.createTempFile(workDir, "line", ".txt");
        Path outFile = Files.createTempFile(workDir, "line", ".wav");
        Path logFile = Files.createTempFile(workDir, "line", ".log");
        try {
            Files.write(textFile, line.getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(outFile);
            List<String> command = new ArrayList<String>();
            command.add(powershell());
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(scriptFile("speak.ps1", SPEAK_SCRIPT).toString());
            command.add(textFile.toAbsolutePath().toString());
            command.add(outFile.toAbsolutePath().toString());
            command.add(resolveVoice(voice));
            command.add(code(voice.ratePercent()));
            command.add(code(voice.pitchPercent()));
            int exit = run(command, logFile, RENDER_TIMEOUT_MILLIS);
            if (exit != 0 || !Files.isRegularFile(outFile) || Files.size(outFile) <= 44) {
                throw new IOException("local speech render failed (exit " + exit + "): "
                        + tail(logFile));
            }
            return WavAudio.read(outFile);
        } finally {
            deleteQuietly(textFile);
            deleteQuietly(outFile);
            deleteQuietly(logFile);
        }
    }

    /**
     * The installed voice that best matches a profile, or {@code ""} to let
     * SAPI use its default when none of the candidates exist.
     */
    public String resolveVoice(VoiceProfile profile) {
        if (profile == null) return "";
        List<String> voices = installedVoices();
        for (String candidate : profile.voiceCandidates()) {
            for (String installed : voices) {
                if (installed.equalsIgnoreCase(candidate)
                        || installed.toLowerCase(Locale.ROOT)
                        .contains(candidate.toLowerCase(Locale.ROOT))) {
                    return installed;
                }
            }
        }
        return "";
    }

    private boolean probe() {
        String property = System.getProperty(ENABLED_PROPERTY, "auto");
        if (property != null && property.trim().equalsIgnoreCase("off")) return false;
        if (!isWindows()) return false;
        List<String> voices = probeVoices();
        return voices != null && !voices.isEmpty();
    }

    private List<String> probeVoices() {
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
            command.add(scriptFile("voices.ps1", VOICES_SCRIPT).toString());
            int exit = run(command, logFile, PROBE_TIMEOUT_MILLIS);
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
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException ignored) {
                    // Cache directory is disposable.
                }
            }
        }
    }

    private Path scriptFile(String name, String content) throws IOException {
        Path script = workDir.resolve(name);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (!Files.isRegularFile(script) || Files.size(script) != bytes.length) {
            Files.write(script, bytes);
        }
        return script;
    }

    private int run(List<String> command, Path logFile, long timeoutMillis) throws IOException {
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

    private static String powershell() {
        String root = System.getenv("SystemRoot");
        if (root != null && !root.isEmpty()) {
            Path system = Paths.get(root, "System32", "WindowsPowerShell", "v1.0",
                    "powershell.exe");
            if (Files.isRegularFile(system)) return system.toString();
        }
        return "powershell.exe";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Temp files are best-effort: a lingering SAPI handle must not fail a line. */
    private static void deleteQuietly(Path file) {
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

    private static String tail(Path logFile) {
        try {
            String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8).trim();
            return log.length() > 300 ? log.substring(log.length() - 300) : log;
        } catch (Exception ignored) {
            return "";
        }
    }
}
