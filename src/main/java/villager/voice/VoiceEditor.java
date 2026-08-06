package villager.voice;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JCheckBox;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone editor for previewing and saving villager voice profiles.
 * It deliberately runs outside the game window so model iteration cannot launch VoxelEngine.
 */
public final class VoiceEditor {
    private final JFrame frame = new JFrame("Villager Voice Editor");
    private final JTextArea text = new JTextArea("I am haggling you", 4, 34);
    private final JTextField presetName = new JTextField("default", 18);
    private final JLabel status = new JLabel("Ready");
    private final JSlider speed = slider(50, 200, 100);
    private final JSlider pitch = slider(-120, 120, 0);
    private final JSlider volume = slider(0, 200, 100);
    private final JSlider tone = slider(-100, 100, 0);
    private final JSlider natural = slider(0, 50, 36);
    private final JSlider singing = slider(0, 100, 0);
    private final JSlider sarcasm = slider(0, 100, 0);
    private final JCheckBox question = new JCheckBox("Question / rising ending");
    private final JComboBox<String> emotion = new JComboBox<>(
            new String[]{"neutral", "happy", "sad", "angry", "scared"});
    private final Path modelDirectory;
    private final Path previewDirectory = Paths.get("dev", "voice-editor");

    public VoiceEditor(Path modelDirectory) {
        this.modelDirectory = modelDirectory;
        build();
    }

    public static void main(String[] args) {
        launch(args.length > 0 ? Paths.get(args[0]) : Paths.get("dev", "voice-models"));
    }

    public static void launch(Path models) {
        javax.swing.SwingUtilities.invokeLater(() -> new VoiceEditor(models).show());
    }

    private void build() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(12, 12));
        frame.setResizable(false);

        JPanel contentPanel = new JPanel(new BorderLayout(10, 10));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel textPanel = new JPanel(new BorderLayout(6, 6));
        textPanel.add(new JLabel("Dialogue"), BorderLayout.NORTH);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        textPanel.add(text, BorderLayout.CENTER);
        contentPanel.add(textPanel, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(9, 1, 4, 4));
        controls.setBorder(BorderFactory.createTitledBorder("Voice parameters"));
        controls.add(row("Speed", speed, "%.2fx", 100.0,
                "Speech rate. 0.50x is slow and deliberate; 2.00x is fast and energetic."));
        controls.add(row("Pitch", pitch, "%+.1f st", 10.0,
                "Static pitch offset in semitones. 0 is the model default."));
        controls.add(row("Volume", volume, "%.0f%%", 1.0,
                "Output loudness. 100% is unchanged; it is normalized to avoid clipping."));
        controls.add(row("Mood", tone, "%+.1f", 100.0,
                "Delivery mood: -1 serious/weighty, 0 neutral, +1 joking/playful."));
        controls.add(row("Natural source", natural, "%.0f%%", 1.0,
                "Natural VITS body mixed under RVC. More sounds less synthetic."));
        controls.add(row("Singing", singing, "%.0f%%", 1.0,
                "Musical vibrato and sustained pitch. 0% is spoken; 100% is strongly sung."));
        controls.add(row("Sarcasm", sarcasm, "%.0f%%", 1.0,
                "Dry, deadpan delivery. Higher values flatten prosody and lower the voice."));
        JPanel emotionRow = new JPanel(new BorderLayout(8, 0));
        JLabel emotionLabel = new JLabel("Emotion", SwingConstants.LEFT);
        emotionLabel.setPreferredSize(new java.awt.Dimension(110, 20));
        emotionRow.add(emotionLabel, BorderLayout.WEST);
        emotionRow.add(emotion, BorderLayout.CENTER);
        emotionRow.setToolTipText("Broad emotional color applied to timing, pitch, and loudness.");
        controls.add(emotionRow);
        question.setToolTipText("Adds a smooth pitch rise at the end. Text ending in ? also enables this automatically when metadata omits the flag.");
        controls.add(question);
        contentPanel.add(controls, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        JPanel profile = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        profile.add(new JLabel("Preset name"), c);
        c.gridx = 1;
        profile.add(presetName, c);
        bottom.add(profile, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new GridLayout(1, 4, 6, 0));
        JButton preview = new JButton("Generate WAV");
        preview.addActionListener(e -> generatePreview());
        JButton save = new JButton("Save preset");
        save.addActionListener(e -> savePreset());
        JButton load = new JButton("Load preset");
        load.addActionListener(e -> loadPreset());
        JButton reset = new JButton("Reset");
        reset.addActionListener(e -> resetDefaults());
        buttons.add(preview);
        buttons.add(save);
        buttons.add(load);
        buttons.add(reset);
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);
        contentPanel.add(bottom, BorderLayout.SOUTH);

        frame.add(contentPanel, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationByPlatform(true);
    }

    private JPanel row(String label, JSlider slider, String format, double scale, String help) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel name = new JLabel(label, SwingConstants.LEFT);
        name.setPreferredSize(new java.awt.Dimension(110, 20));
        JLabel value = new JLabel();
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        value.setPreferredSize(new java.awt.Dimension(70, 20));
        Runnable update = () -> value.setText(String.format(java.util.Locale.ROOT,
                format, slider.getValue() / scale));
        slider.addChangeListener(e -> update.run());
        update.run();
        name.setToolTipText(help);
        slider.setToolTipText(help);
        row.setToolTipText(help);
        row.add(name, BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    private static JSlider slider(int min, int max, int value) {
        JSlider slider = new JSlider(min, max, value);
        slider.setPaintTicks(false);
        return slider;
    }

    private SpeechOptions options() {
        return new SpeechOptions(speed.getValue() / 100.0,
                pitch.getValue() / 10.0,
                volume.getValue() / 100.0,
                tone.getValue() / 100.0,
                natural.getValue() / 100.0,
                (String) emotion.getSelectedItem(),
                singing.getValue() / 100.0,
                sarcasm.getValue() / 100.0,
                question.isSelected());
    }

    private void generatePreview() {
        String line = text.getText().trim();
        if (line.isEmpty()) {
            status.setText("Enter dialogue first");
            return;
        }
        status.setText("Generating preview; the editor may take a moment...");
        setButtonsEnabled(false);
        final SpeechOptions previewOptions = options();
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                VillagerSynthesizer synthesizer = new VillagerSynthesizer(modelDirectory);
                try {
                    Path output = previewDirectory.resolve("preview.wav");
                    synthesizer.render(line, previewOptions).write(output);
                    return output;
                } finally {
                    synthesizer.close();
                }
            }

            @Override
            protected void done() {
                setButtonsEnabled(true);
                try {
                    status.setText("Preview saved to " + get());
                } catch (Exception error) {
                    status.setText("Preview failed: " + error.getMessage());
                }
            }
        }.execute();
    }

    private void savePreset() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(presetName.getText().trim() + ".json"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            new VoicePreset(presetName.getText(), options()).save(chooser.getSelectedFile().toPath());
            status.setText("Preset saved to " + chooser.getSelectedFile());
        } catch (Exception error) {
            status.setText("Could not save preset: " + error.getMessage());
        }
    }

    private void loadPreset() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            VoicePreset preset = VoicePreset.load(chooser.getSelectedFile().toPath());
            presetName.setText(preset.getName());
            apply(preset.getOptions());
            status.setText("Loaded " + chooser.getSelectedFile());
        } catch (Exception error) {
            status.setText("Could not load preset: " + error.getMessage());
        }
    }

    private void apply(SpeechOptions options) {
        speed.setValue((int) Math.round(options.getSpeed() * 100.0));
        pitch.setValue((int) Math.round(options.getPitchSemitones() * 10.0));
        volume.setValue((int) Math.round(options.getVolume() * 100.0));
        tone.setValue((int) Math.round(options.getTone() * 100.0));
        natural.setValue((int) Math.round(options.getNaturalSourceMix() * 100.0));
        singing.setValue((int) Math.round(options.getSinging() * 100.0));
        sarcasm.setValue((int) Math.round(options.getSarcasm() * 100.0));
        question.setSelected(options.isQuestion());
        emotion.setSelectedItem(options.getEmotion());
    }

    private void resetDefaults() {
        presetName.setText("default");
        apply(SpeechOptions.DEFAULT);
        status.setText("Reset to defaults");
    }

    private void setButtonsEnabled(boolean enabled) {
        for (java.awt.Component component : frame.getContentPane().getComponents()) {
            setTreeEnabled(component, enabled);
        }
    }

    private static void setTreeEnabled(java.awt.Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                setTreeEnabled(child, enabled);
            }
        }
    }

    private void show() {
        frame.setVisible(true);
    }
}
