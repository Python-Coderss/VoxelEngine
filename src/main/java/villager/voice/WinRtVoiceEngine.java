package villager.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Local speech through the Windows Runtime speech synthesizer
 * ({@code Windows.Media.SpeechSynthesis}).
 *
 * <p>Why this exists: the older {@code System.Speech} (SAPI) path only sees the
 * legacy desktop voices. Windows 10/11 also ships <em>OneCore</em> voices —
 * George, Mark, Richard, Susan, Linda and friends — which are the same voices
 * used by Narrator and are noticeably less robotic. WinRT exposes them, but
 * only through a WinRT async API, so this engine drives a small PowerShell
 * helper (no network, no model download) that renders the SSML to a plain WAV.</p>
 *
 * <p>Every failure is silent: {@link WindowsSapiTts} falls back to its SAPI
 * implementation when this engine cannot list voices or a render fails, so the
 * game still speaks on machines without the Windows Runtime projection.</p>
 */
final class WinRtVoiceEngine {

    private static final long PROBE_TIMEOUT_MILLIS = 15000L;
    private static final long RENDER_TIMEOUT_MILLIS = 25000L;

    /** Host newline; keeps the generated PowerShell readable on any OS. */
    private static final String NL = System.lineSeparator();

    /**
     * Lists the OneCore voices. {@code DisplayName} is exactly the string the
     * render script and {@code VoiceProfile} candidates match against.
     */
    private static final String VOICES_SCRIPT = String.join(NL, new String[] {
        "try {",
        "    [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media, ContentType = WindowsRuntime] | Out-Null",
        "    [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices | ForEach-Object { Write-Output $_.DisplayName }",
        "} catch { exit 3 }",
        ""
    });

    /**
     * Renders an SSML file to WAV. The tricky part is unwrapping WinRT's
     * {@code IAsyncOperation<SpeechSynthesisStream>} from PowerShell 5.1, hence
     * the reflected {@code AsTask} helper plus {@code AsStreamForRead}.
     *
     * <p>Single quotes are used throughout so the script needs no escaping in
     * Java; only the two mandatory paths and the optional voice are injected.</p>
     */
    private static final String SPEAK_SCRIPT = String.join(NL, new String[] {
        "param(",
        "    [Parameter(Mandatory=$true)][string]$SsmlFile,",
        "    [Parameter(Mandatory=$true)][string]$OutFile,",
        "    [string]$Voice = ''",
        ")",
        "$ErrorActionPreference = 'Stop'",
        "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        "$null = [Windows.Media.SpeechSynthesis.SpeechSynthesizer, Windows.Media, ContentType = WindowsRuntime]",
        "$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {",
        "    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and",
        "    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'",
        "})[0]",
        "function Await($WinRtTask, $ResultType) {",
        "    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)",
        "    $netTask = $asTask.Invoke($null, @($WinRtTask))",
        "    $netTask.Wait(-1) | Out-Null",
        "    return $netTask.Result",
        "}",
        "$synth = New-Object Windows.Media.SpeechSynthesis.SpeechSynthesizer",
        "if ($Voice -ne '') {",
        "    $selected = [Windows.Media.SpeechSynthesis.SpeechSynthesizer]::AllVoices |",
        "        Where-Object { $_.DisplayName -eq $Voice } | Select-Object -First 1",
        "    if ($null -ne $selected) { $synth.Voice = $selected }",
        "}",
        "$ssml = [System.IO.File]::ReadAllText($SsmlFile, [System.Text.Encoding]::UTF8)",
        "$stream = Await ($synth.SynthesizeSsmlToStreamAsync($ssml)) ([Windows.Media.SpeechSynthesis.SpeechSynthesisStream])",
        "$netStream = [System.IO.WindowsRuntimeStreamExtensions]::AsStreamForRead($stream)",
        "$file = [System.IO.File]::Create($OutFile)",
        "$netStream.CopyTo($file)",
        "$file.Dispose()",
        "$netStream.Dispose()",
        ""
    });

    private final Path workDir;
    private volatile Boolean available;
    private volatile List<String> voices;

    WinRtVoiceEngine(Path workDir) {
        this.workDir = workDir;
    }

    /** True when the Windows Runtime projection can list at least one voice. */
    boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            available = !listVoices().isEmpty();
            return available;
        }
    }

    /** Installed OneCore voice names (empty when unavailable). */
    List<String> voices() {
        List<String> cached = voices;
        if (cached != null) return cached;
        synchronized (this) {
            if (voices == null) voices = listVoices();
            return voices;
        }
    }

    /**
     * Render complete SSML with the named voice.
     *
     * @throws IOException when WinRT is unavailable, the helper times out, or
     *                     the helper produced no usable WAV
     */
    WavAudio renderSsml(String ssml, String voice) throws IOException {
        if (ssml == null || ssml.trim().isEmpty()) {
            throw new IOException("cannot synthesize empty SSML");
        }
        Files.createDirectories(workDir);
        Path ssmlFile = Files.createTempFile(workDir, "winrt", ".xml");
        Path outFile = Files.createTempFile(workDir, "winrt", ".wav");
        Path logFile = Files.createTempFile(workDir, "winrt", ".log");
        try {
            Files.write(ssmlFile, ssml.getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(outFile);
            List<String> command = new ArrayList<String>();
            command.add(WindowsSapiTts.powershell());
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(WindowsSapiTts.scriptFile(workDir, "winrt-speak.ps1",
                    SPEAK_SCRIPT).toString());
            command.add(ssmlFile.toAbsolutePath().toString());
            command.add(outFile.toAbsolutePath().toString());
            command.add(voice == null ? "" : voice);
            int exit = WindowsSapiTts.runProcess(command, logFile, RENDER_TIMEOUT_MILLIS);
            if (exit != 0 || !Files.isRegularFile(outFile) || Files.size(outFile) <= 44) {
                throw new IOException("WinRT speech render failed (exit " + exit + "): "
                        + WindowsSapiTts.tail(logFile));
            }
            return WavAudio.read(outFile);
        } finally {
            WindowsSapiTts.deleteQuietly(ssmlFile);
            WindowsSapiTts.deleteQuietly(outFile);
            WindowsSapiTts.deleteQuietly(logFile);
        }
    }

    private List<String> listVoices() {
        if (!WindowsSapiTts.isWindows()) return new ArrayList<String>();
        Path logFile = null;
        try {
            Files.createDirectories(workDir);
            logFile = Files.createTempFile(workDir, "winrt-voices", ".log");
            List<String> command = new ArrayList<String>();
            command.add(WindowsSapiTts.powershell());
            command.add("-NoProfile");
            command.add("-NonInteractive");
            command.add("-ExecutionPolicy");
            command.add("Bypass");
            command.add("-File");
            command.add(WindowsSapiTts.scriptFile(workDir, "winrt-voices.ps1",
                    VOICES_SCRIPT).toString());
            int exit = WindowsSapiTts.runProcess(command, logFile, PROBE_TIMEOUT_MILLIS);
            List<String> found = new ArrayList<String>();
            if (exit == 0) {
                for (String line : new String(Files.readAllBytes(logFile),
                        StandardCharsets.UTF_8).split("\\r?\\n")) {
                    String name = line.trim();
                    if (!name.isEmpty()) found.add(name);
                }
            }
            return found;
        } catch (Exception error) {
            // No WinRT projection, no PowerShell, locked cache dir: just say no.
            return new ArrayList<String>();
        } finally {
            if (logFile != null) WindowsSapiTts.deleteQuietly(logFile);
        }
    }
}
