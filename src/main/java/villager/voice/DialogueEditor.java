package villager.voice;

import com.voxel.audio.DialogueCatalog;
import com.voxel.audio.DialogueLine;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JCheckBox;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Editor for the game's editable dialogue catalog. Run with
 * {@code villager.voice.Main --dialogue-editor}.
 */
public final class DialogueEditor {
    private static final String[] PROFESSIONS = {
            "*", "FARMER", "BUILDER", "NEWS_ANCHOR", "SHOPKEEPER", "NITWIT"
    };
    private static final String[] PERIODS = {"*", "MORNING", "DAY", "EVENING", "NIGHT"};

    private final JFrame frame = new JFrame("Villager Dialogue Editor");
    private final JList<DialogueLine> entries = new JList<DialogueLine>();
    private final JTextField id = new JTextField();
    private final JTextArea text = new JTextArea(5, 34);
    private final JComboBox<String> profession = new JComboBox<String>(PROFESSIONS);
    private final JComboBox<String> period = new JComboBox<String>(PERIODS);
    private final JSlider speed = slider(50, 200, 100);
    private final JSlider pitch = slider(-120, 120, 0);
    private final JSlider volume = slider(0, 200, 100);
    private final JSlider tone = slider(-100, 100, 0);
    private final JSlider natural = slider(0, 50, 36);
    private final JSlider singing = slider(0, 100, 0);
    private final JSlider sarcasm = slider(0, 100, 0);
    private final JCheckBox question = new JCheckBox("Question / rising ending");
    private final JComboBox<String> emotion = new JComboBox<String>(
            new String[]{"neutral", "happy", "sad", "angry", "scared"});
    private final JLabel status = new JLabel("Ready");
    private final Path modelDirectory;
    private Path catalogPath = DialogueCatalog.DEFAULT_PATH;
    private List<DialogueLine> values = new ArrayList<DialogueLine>();

    public DialogueEditor(Path modelDirectory) {
        this.modelDirectory = modelDirectory;
        build();
        loadCatalog(catalogPath);
    }

    public static void launch(Path models) {
        javax.swing.SwingUtilities.invokeLater(() -> new DialogueEditor(models).show());
    }

    private void build() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));
        frame.setMinimumSize(new Dimension(980, 650));

        entries.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        entries.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value.getId() + "  —  " + value.getText());
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
            label.setBackground(selected ? new java.awt.Color(55, 90, 130)
                    : (index % 2 == 0 ? new java.awt.Color(35, 35, 42) : new java.awt.Color(43, 43, 51)));
            label.setForeground(java.awt.Color.WHITE);
            return label;
        });
        entries.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && entries.getSelectedValue() != null) {
                apply(entries.getSelectedValue());
            }
        });
        JScrollPane listScroll = new JScrollPane(entries);
        listScroll.setBorder(BorderFactory.createTitledBorder("Dialogue entries"));
        listScroll.setPreferredSize(new Dimension(330, 0));
        frame.add(listScroll, BorderLayout.WEST);

        JPanel editor = new JPanel(new BorderLayout(8, 8));
        editor.setBorder(BorderFactory.createEmptyBorder(10, 4, 10, 10));
        JPanel identity = new JPanel(new GridLayout(3, 2, 6, 6));
        identity.setBorder(BorderFactory.createTitledBorder("Dialogue metadata"));
        identity.add(new JLabel("ID"));
        identity.add(id);
        identity.add(new JLabel("Profession"));
        identity.add(profession);
        identity.add(new JLabel("Period"));
        identity.add(period);
        editor.add(identity, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        JPanel textPanel = new JPanel(new BorderLayout(5, 5));
        textPanel.add(new JLabel("Text"), BorderLayout.NORTH);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        textPanel.add(new JScrollPane(text), BorderLayout.CENTER);
        center.add(textPanel, BorderLayout.NORTH);

        JPanel controls = new JPanel(new GridLayout(9, 1, 4, 4));
        controls.setBorder(BorderFactory.createTitledBorder("Voice metadata"));
        controls.add(row("Speed", speed, "%.2fx", 100.0,
                "Speech rate: 0.50x slow/deliberate, 2.00x fast/energetic."));
        controls.add(row("Pitch", pitch, "%+.1f st", 10.0,
                "Static pitch offset in semitones; 0 is the model default."));
        controls.add(row("Volume", volume, "%.0f%%", 1.0,
                "Output loudness; 100% is unchanged."));
        controls.add(row("Mood", tone, "%+.2f", 100.0,
                "-1 serious/weighty, 0 neutral, +1 joking/playful."));
        controls.add(row("Natural source", natural, "%.0f%%", 1.0,
                "Natural VITS body under RVC; more is less synthetic."));
        controls.add(row("Singing", singing, "%.0f%%", 1.0,
                "0% spoken; 100% adds the strongest musical pitch movement."));
        controls.add(row("Sarcasm", sarcasm, "%.0f%%", 1.0,
                "Dry/deadpan delivery; higher values flatten prosody and lower the voice."));
        JPanel emotionRow = new JPanel(new BorderLayout(8, 0));
        emotionRow.add(new JLabel("Emotion"), BorderLayout.WEST);
        emotionRow.add(emotion, BorderLayout.CENTER);
        emotionRow.setToolTipText("Broad emotional color changes timing, pitch, and loudness.");
        controls.add(emotionRow);
        question.setToolTipText("Adds a smooth terminal pitch rise; omitted metadata auto-detects text ending in ?.");
        controls.add(question);
        center.add(controls, BorderLayout.CENTER);
        editor.add(center, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new GridLayout(2, 4, 6, 6));
        JButton add = new JButton("New entry");
        add.addActionListener(e -> newEntry());
        JButton apply = new JButton("Apply changes");
        apply.addActionListener(e -> applyCurrent());
        JButton load = new JButton("Open catalog");
        load.addActionListener(e -> chooseCatalog(false));
        JButton save = new JButton("Save catalog");
        save.addActionListener(e -> chooseCatalog(true));
        JButton preview = new JButton("Preview WAV");
        preview.addActionListener(e -> preview());
        JButton remove = new JButton("Remove entry");
        remove.addActionListener(e -> removeCurrent());
        buttons.add(add);
        buttons.add(apply);
        buttons.add(load);
        buttons.add(save);
        buttons.add(preview);
        buttons.add(remove);
        buttons.add(new JLabel());
        buttons.add(new JLabel());
        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.add(buttons, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);
        editor.add(south, BorderLayout.SOUTH);
        frame.add(editor, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationByPlatform(true);
    }

    private JPanel row(String label, JSlider slider, String format, double scale, String help) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        JLabel name = new JLabel(label, SwingConstants.LEFT);
        name.setPreferredSize(new Dimension(115, 20));
        JLabel value = new JLabel();
        value.setPreferredSize(new Dimension(70, 20));
        value.setHorizontalAlignment(SwingConstants.RIGHT);
        Runnable update = () -> value.setText(String.format(Locale.ROOT, format,
                slider.getValue() / scale));
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
        return new JSlider(min, max, value);
    }

    private SpeechOptions options() {
        return new SpeechOptions(speed.getValue() / 100.0, pitch.getValue() / 10.0,
                volume.getValue() / 100.0, tone.getValue() / 100.0,
                natural.getValue() / 100.0, (String) emotion.getSelectedItem(),
                singing.getValue() / 100.0, sarcasm.getValue() / 100.0,
                question.isSelected());
    }

    private void apply(DialogueLine line) {
        id.setText(line.getId());
        text.setText(line.getText());
        profession.setSelectedItem(line.getProfession());
        period.setSelectedItem(line.getPeriod());
        SpeechOptions o = line.getOptions();
        speed.setValue((int) Math.round(o.getSpeed() * 100));
        pitch.setValue((int) Math.round(o.getPitchSemitones() * 10));
        volume.setValue((int) Math.round(o.getVolume() * 100));
        tone.setValue((int) Math.round(o.getTone() * 100));
        natural.setValue((int) Math.round(o.getNaturalSourceMix() * 100));
        singing.setValue((int) Math.round(o.getSinging() * 100));
        sarcasm.setValue((int) Math.round(o.getSarcasm() * 100));
        question.setSelected(o.isQuestion());
        emotion.setSelectedItem(o.getEmotion());
    }

    private void applyCurrent() {
        int selected = entries.getSelectedIndex();
        if (selected < 0) {
            status.setText("Select an entry first");
            return;
        }
        try {
            DialogueLine old = values.get(selected);
            values.set(selected, new DialogueLine(id.getText(), text.getText(),
                    (String) profession.getSelectedItem(), (String) period.getSelectedItem(),
                    old.getVariant(), options()));
            refresh(selected);
            status.setText("Changes applied; save the catalog to persist them");
        } catch (Exception error) {
            status.setText("Invalid entry: " + error.getMessage());
        }
    }

    private void newEntry() {
        values.add(new DialogueLine("new_line_" + values.size(), "Hmm...", "*", "*",
                values.size(), SpeechOptions.DEFAULT));
        refresh(values.size() - 1);
    }

    private void removeCurrent() {
        int selected = entries.getSelectedIndex();
        if (selected < 0) return;
        values.remove(selected);
        refresh(Math.max(0, selected - 1));
    }

    private void refresh(int selected) {
        entries.setModel(new DefaultComboBoxModel<DialogueLine>(
                values.toArray(new DialogueLine[values.size()])));
        if (!values.isEmpty()) {
            entries.setSelectedIndex(Math.min(selected, values.size() - 1));
        }
    }

    private void loadCatalog(Path path) {
        try {
            DialogueCatalog catalog;
            if (Files.isRegularFile(path)) {
                catalog = DialogueCatalog.load(path);
            } else {
                catalog = DialogueCatalog.loadDefault();
            }
            values = new ArrayList<DialogueLine>(catalog.getLines());
            catalogPath = path;
            refresh(0);
            status.setText(values.isEmpty() ? "No catalog entries; create one" : "Loaded " + path);
        } catch (Exception error) {
            values = new ArrayList<DialogueLine>();
            refresh(0);
            status.setText(FilesMessage(path, error));
        }
    }

    private static String FilesMessage(Path path, Exception error) {
        return "Using empty catalog (" + path + "): " + error.getMessage();
    }

    private void chooseCatalog(boolean save) {
        JFileChooser chooser = new JFileChooser(catalogPath.toFile());
        chooser.setSelectedFile(new File(catalogPath.toString()));
        int result = save ? chooser.showSaveDialog(frame) : chooser.showOpenDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath();
            if (save) {
                try {
                    new DialogueCatalog(values).save(selected);
                    catalogPath = selected;
                    status.setText("Saved " + selected);
                } catch (Exception error) {
                    status.setText("Could not save: " + error.getMessage());
                }
            } else {
                loadCatalog(selected);
            }
        }
    }

    private void preview() {
        String line = text.getText().trim();
        if (line.isEmpty()) {
            status.setText("Enter dialogue text first");
            return;
        }
        status.setText("Generating preview...");
        final SpeechOptions previewOptions = options();
        new SwingWorker<Path, Void>() {
            @Override
            protected Path doInBackground() throws Exception {
                VillagerSynthesizer synthesizer = new VillagerSynthesizer(modelDirectory);
                try {
                    Path output = Paths.get("dev", "voice-editor", "dialogue-preview.wav");
                    synthesizer.render(line, previewOptions).write(output);
                    return output;
                } finally {
                    synthesizer.close();
                }
            }

            @Override
            protected void done() {
                try {
                    status.setText("Preview saved to " + get());
                } catch (Exception error) {
                    status.setText("Preview failed: " + error.getMessage());
                }
            }
        }.execute();
    }

    private void show() {
        frame.setVisible(true);
    }
}
