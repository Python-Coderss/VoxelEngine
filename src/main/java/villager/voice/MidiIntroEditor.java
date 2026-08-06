package villager.voice;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JSpinner.DefaultEditor;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Piano-roll editor for the MIDI-backed Villager News intro.
 *
 * Double-click an empty grid cell to add a note. Drag a note to move it or
 * change pitch. Select a note and use the controls to edit duration, velocity,
 * and syllable. Preview renders the neural villager WAV, not a generic piano.
 */
public final class MidiIntroEditor {
    private static final int MIN_PITCH = 36;
    private static final int MAX_PITCH = 84;
    private static final int BEAT_WIDTH = 72;
    private static final int ROW_HEIGHT = 14;
    private static final long GRID_TICKS = MidiNoteSequence.DEFAULT_PPQ / 2;

    private final JFrame frame = new JFrame("Villager News MIDI Editor");
    private final PianoRoll roll = new PianoRoll();
    private final JLabel status = new JLabel("Ready");
    private final JSpinner bpm = new JSpinner(new SpinnerNumberModel(130.0, 20.0, 300.0, 1.0));
    private final JSpinner duration = new JSpinner(new SpinnerNumberModel(240, 1, 3840, 60));
    private final JSpinner velocity = new JSpinner(new SpinnerNumberModel(100, 1, 127, 1));
    private final javax.swing.JTextField syllable = new javax.swing.JTextField("da", 8);
    private final Path modelDirectory;
    private MidiNoteSequence sequence;
    private MidiNoteSequence.Note selected;
    private Clip clip;

    public MidiIntroEditor(Path modelDirectory) {
        this.modelDirectory = modelDirectory;
        try {
            sequence = MidiNoteSequence.loadDefault();
            bpm.setValue(sequence.getBpm());
        } catch (Exception error) {
            sequence = new MidiNoteSequence(MidiNoteSequence.DEFAULT_PPQ, 130.0,
                    new java.util.ArrayList<MidiNoteSequence.Note>());
            status.setText("New sequence: " + error.getMessage());
        }
        build();
    }

    public static void launch(Path models) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            MidiIntroEditor editor = new MidiIntroEditor(models);
            editor.frame.setVisible(true);
        });
    }

    private void build() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1100, 720));
        frame.setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new GridLayout(1, 8, 6, 6));
        JButton open = new JButton("Open MIDI");
        open.addActionListener(e -> openMidi());
        JButton save = new JButton("Save MIDI");
        save.addActionListener(e -> saveMidi());
        JButton preview = new JButton("Preview Villager WAV");
        preview.addActionListener(e -> preview());
        JButton stop = new JButton("Stop");
        stop.addActionListener(e -> stopAudio());
        JButton add = new JButton("Add note");
        add.addActionListener(e -> addNote());
        JButton delete = new JButton("Delete note");
        delete.addActionListener(e -> deleteNote());
        top.add(open); top.add(save); top.add(preview); top.add(stop);
        top.add(add); top.add(delete); top.add(new JLabel("BPM")); top.add(bpm);
        frame.add(top, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(roll);
        scroll.setBorder(BorderFactory.createTitledBorder("Piano roll — double-click to add, drag to edit"));
        JPanel inspector = new JPanel(new GridLayout(10, 1, 5, 5));
        inspector.setBorder(BorderFactory.createTitledBorder("Selected note"));
        inspector.add(new JLabel("Duration (ticks)")); inspector.add(duration);
        inspector.add(new JLabel("Velocity (1–127)")); inspector.add(velocity);
        inspector.add(new JLabel("Syllable")); inspector.add(syllable);
        JButton apply = new JButton("Apply note");
        apply.addActionListener(e -> applySelected());
        inspector.add(apply);
        inspector.add(new JLabel("PPQ: " + MidiNoteSequence.DEFAULT_PPQ));
        inspector.add(new JLabel("Grid: eighth notes"));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, inspector);
        split.setResizeWeight(0.82);
        frame.add(split, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.pack();
        frame.setLocationByPlatform(true);
    }

    private void openMidi() {
        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            sequence = MidiNoteSequence.load(chooser.getSelectedFile().toPath());
            bpm.setValue(sequence.getBpm()); selected = null; roll.repaint();
            status.setText("Loaded " + chooser.getSelectedFile());
        } catch (Exception error) { status.setText("Could not load MIDI: " + error.getMessage()); }
    }

    private void saveMidi() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new java.io.File("villager-news-intro.mid"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        try {
            sequence.setBpm(((Number) bpm.getValue()).doubleValue());
            sequence.save(chooser.getSelectedFile().toPath());
            status.setText("Saved " + chooser.getSelectedFile());
        } catch (Exception error) { status.setText("Could not save MIDI: " + error.getMessage()); }
    }

    private void addNote() {
        MidiNoteSequence.Note note = new MidiNoteSequence.Note(60, 0,
                MidiNoteSequence.DEFAULT_PPQ / 2, 100, 0, "da");
        sequence.add(note); selected = note; applyToInspector(); roll.repaint();
    }

    private void deleteNote() {
        if (selected != null) { sequence.remove(selected); selected = null; roll.repaint(); }
    }

    private void applySelected() {
        if (selected == null) { status.setText("Select a note first"); return; }
        selected.setDurationTicks(((Number) duration.getValue()).longValue());
        selected.setVelocity(((Number) velocity.getValue()).intValue());
        selected.setText(syllable.getText()); roll.repaint();
    }

    private void applyToInspector() {
        if (selected == null) return;
        duration.setValue((int) selected.getDurationTicks());
        velocity.setValue(selected.getVelocity()); syllable.setText(selected.getText());
    }

    private void preview() {
        stopAudio();
        status.setText("Rendering neural villager preview...");
        java.util.List<MidiNoteSequence.Note> copied = new java.util.ArrayList<MidiNoteSequence.Note>();
        for (MidiNoteSequence.Note note : sequence.getNotes()) {
            copied.add(new MidiNoteSequence.Note(note.getPitch(), note.getStartTick(),
                    note.getDurationTicks(), note.getVelocity(), note.getChannel(), note.getText()));
        }
        final MidiNoteSequence snapshot = new MidiNoteSequence(sequence.getResolution(),
                ((Number) bpm.getValue()).doubleValue(), copied);
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws Exception {
                VillagerNewsIntro intro = VillagerNewsIntro.fromMidi(snapshot);
                VillagerSynthesizer synthesizer = new VillagerSynthesizer(modelDirectory);
                try {
                    Path output = Paths.get("dev", "voice-editor", "midi-intro-preview.wav");
                    intro.render(synthesizer, SpeechOptions.DEFAULT).write(output);
                    return output;
                } finally { synthesizer.close(); }
            }
            @Override protected void done() {
                try { Path path = get(); play(path); status.setText("Preview: " + path); }
                catch (Exception error) { status.setText("Preview failed: " + error.getMessage()); }
            }
        }.execute();
    }

    private void play(Path path) throws Exception {
        stopAudio();
        try (AudioInputStream input = AudioSystem.getAudioInputStream(path.toFile())) {
            clip = AudioSystem.getClip(); clip.open(input); clip.start();
        }
    }

    private void stopAudio() {
        if (clip != null) { clip.stop(); clip.close(); clip = null; }
    }

    private final class PianoRoll extends JPanel {
        private MidiNoteSequence.Note dragNote;
        private long dragStartTick;
        private int dragPitch;
        PianoRoll() {
            setBackground(new Color(25, 27, 34)); setPreferredSize(new Dimension(1400, 800));
            MouseAdapter mouse = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    dragNote = find(e.getPoint());
                    if (dragNote != null) { selected = dragNote; applyToInspector();
                        dragStartTick = dragNote.getStartTick(); dragPitch = dragNote.getPitch(); }
                    repaint();
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragNote == null) return;
                    long tick = Math.max(0, Math.round((e.getX() / (double) BEAT_WIDTH) * MidiNoteSequence.DEFAULT_PPQ));
                    tick = Math.max(0, Math.round(tick / (double) GRID_TICKS) * GRID_TICKS);
                    dragNote.setStartTick(tick);
                    dragNote.setPitch(Math.max(MIN_PITCH, Math.min(MAX_PITCH,
                            MAX_PITCH - e.getY() / ROW_HEIGHT)));
                    sequence.sortNotes(); repaint();
                }
                @Override public void mouseReleased(MouseEvent e) { dragNote = null; }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && find(e.getPoint()) == null) {
                        long tick = Math.max(0, Math.round((e.getX() / (double) BEAT_WIDTH)
                                * MidiNoteSequence.DEFAULT_PPQ / GRID_TICKS) * GRID_TICKS);
                        int pitch = Math.max(MIN_PITCH, Math.min(MAX_PITCH,
                                MAX_PITCH - e.getY() / ROW_HEIGHT));
                        selected = new MidiNoteSequence.Note(pitch, tick, GRID_TICKS, 100, 0, "da");
                        sequence.add(selected); applyToInspector(); repaint();
                    }
                }
            };
            addMouseListener(mouse); addMouseMotionListener(mouse);
        }
        private MidiNoteSequence.Note find(Point point) {
            long tick = Math.round(point.x / (double) BEAT_WIDTH * MidiNoteSequence.DEFAULT_PPQ);
            int pitch = MAX_PITCH - point.y / ROW_HEIGHT;
            for (MidiNoteSequence.Note note : sequence.getNotes()) {
                if (tick >= note.getStartTick() && tick <= note.getEndTick()
                        && Math.abs(note.getPitch() - pitch) <= 1) return note;
            }
            return null;
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int width = Math.max(getWidth(), (int) (sequence.getEndTick() * BEAT_WIDTH
                    / (double) MidiNoteSequence.DEFAULT_PPQ) + 200);
            setPreferredSize(new Dimension(width, (MAX_PITCH - MIN_PITCH + 1) * ROW_HEIGHT));
            for (int p = MIN_PITCH; p <= MAX_PITCH; p++) {
                int y = (MAX_PITCH - p) * ROW_HEIGHT;
                g.setColor(p % 12 == 0 ? new Color(55, 58, 70) : new Color(37, 40, 49));
                g.drawLine(0, y, width, y);
                if (p % 12 == 0) { g.setColor(Color.LIGHT_GRAY); g.drawString(noteName(p), 4, y + 11); }
            }
            for (long tick = 0; tick <= sequence.getEndTick() + MidiNoteSequence.DEFAULT_PPQ; tick += GRID_TICKS) {
                int x = (int) (tick * BEAT_WIDTH / (double) MidiNoteSequence.DEFAULT_PPQ);
                g.setColor(tick % MidiNoteSequence.DEFAULT_PPQ == 0 ? new Color(110, 115, 130) : new Color(62, 65, 76));
                g.drawLine(x, 0, x, getHeight());
            }
            for (MidiNoteSequence.Note note : sequence.getNotes()) {
                int x = (int) (note.getStartTick() * BEAT_WIDTH / (double) MidiNoteSequence.DEFAULT_PPQ);
                int w = Math.max(4, (int) (note.getDurationTicks() * BEAT_WIDTH / (double) MidiNoteSequence.DEFAULT_PPQ));
                int y = (MAX_PITCH - note.getPitch()) * ROW_HEIGHT + 1;
                g.setColor(note == selected ? new Color(255, 190, 70) : new Color(82, 175, 232));
                g.fillRoundRect(x, y, w, ROW_HEIGHT - 2, 5, 5);
                g.setColor(Color.WHITE); g.drawString(note.getText(), x + 3, y + 11);
            }
        }
        private String noteName(int midi) {
            String[] n = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
            return n[midi % 12] + (midi / 12 - 1);
        }
    }
}
