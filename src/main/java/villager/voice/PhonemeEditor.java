package villager.voice;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone hand-authored IPA/token inspector. It never calls eSpeak or
 * another phonemizer. The map is display-only because this converted model's
 * public Java TTS API does not accept raw token/IPA input.
 */
public final class PhonemeEditor {
    private final JFrame frame = new JFrame("Villager Phoneme Editor");
    private final Path tokens;
    private final Path mapping;
    private final PhonemeTableModel tableModel;
    private final JTable table;
    private final JTextArea notation = new JTextArea("{dh-schwa f-ah-m-er}", 3, 60);
    private final JTextArea report = new JTextArea(12, 60);
    private final JLabel status = new JLabel("Mapping/inspection only; generation is disabled for this model.");

    public PhonemeEditor(Path modelDirectory, Path mapping) throws Exception {
        Path models = modelDirectory.toAbsolutePath().normalize();
        this.tokens = models.resolve("tokens.txt");
        this.mapping = mapping == null ? models.resolve("phoneme-map.tsv")
                : mapping.toAbsolutePath().normalize();
        this.tableModel = new PhonemeTableModel(PhonemeGuide.load(tokens, this.mapping));
        this.table = new JTable(tableModel);
        build();
    }

    public static void main(String[] args) throws Exception {
        Path models = args.length > 0 ? Paths.get(args[0]) : Paths.get("models", "java");
        Path mapping = args.length > 1 ? Paths.get(args[1]) : models.resolve("phoneme-map.tsv");
        launch(models, mapping);
    }

    public static void launch(Path models, Path mapping) {
        javax.swing.SwingUtilities.invokeLater(() -> {
            try {
                new PhonemeEditor(models, mapping).show();
            } catch (Exception error) {
                JOptionPane.showMessageDialog(null, error.getMessage(),
                        "Phoneme editor", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void build() {
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(980, 720);
        frame.setLocationByPlatform(true);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(90);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        table.getColumnModel().getColumn(3).setPreferredWidth(500);

        JPanel left = new JPanel(new BorderLayout(6, 6));
        left.setBorder(BorderFactory.createTitledBorder("Token vocabulary (edit representation/description)"));
        left.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel tableButtons = new JPanel(new GridLayout(1, 1, 6, 0));
        JButton save = new JButton("Save map");
        save.addActionListener(e -> saveMap());
        tableButtons.add(save);
        left.add(tableButtons, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(6, 6));
        JPanel notationPanel = new JPanel(new BorderLayout(6, 6));
        notationPanel.setBorder(BorderFactory.createTitledBorder(
                "Manual phonemes: {phoneme-phoneme per word; spaces between words}"));
        notation.setLineWrap(true);
        notation.setWrapStyleWord(true);
        notationPanel.add(new JScrollPane(notation), BorderLayout.CENTER);
        JButton inspect = new JButton("Inspect IPA and token IDs");
        inspect.addActionListener(e -> inspectNotation());
        notationPanel.add(inspect, BorderLayout.SOUTH);
        right.add(notationPanel, BorderLayout.NORTH);

        report.setEditable(false);
        report.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        right.add(new JScrollPane(report), BorderLayout.CENTER);
        right.add(status, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(500);
        frame.add(split, BorderLayout.CENTER);
    }

    private void inspectNotation() {
        try {
            report.setText(tableModel.getGuide().inspect(notation.getText()));
            status.setText("Inspection updated. This uses the readable 44-phoneme formant notation; no IPA or eSpeak is used.");
        } catch (Exception error) {
            report.setText("Notation error: " + error.getMessage());
            status.setText("Notation needs correction");
        }
    }

    private void saveMap() {
        try {
            tableModel.getGuide().save(mapping);
            status.setText("Saved hand-authored map to " + mapping);
        } catch (Exception error) {
            status.setText("Could not save map: " + error.getMessage());
        }
    }

    private void show() {
        frame.setVisible(true);
    }

    private static final class PhonemeTableModel extends AbstractTableModel {
        private final PhonemeGuide guide;
        private final String[] columns = {"IPA", "Token ID", "Your representation", "English-readable description"};

        private PhonemeTableModel(PhonemeGuide guide) {
            this.guide = guide;
        }

        private PhonemeGuide getGuide() {
            return guide;
        }

        @Override
        public int getRowCount() {
            return guide.entries().size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            PhonemeGuide.Entry entry = guide.entries().get(row);
            switch (column) {
                case 0: return entry.getSymbol();
                case 1: return entry.getTokenId();
                case 2: return entry.getRepresentation();
                case 3: return entry.getDescription();
                default: return "";
            }
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 2 || column == 3;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            PhonemeGuide.Entry entry = guide.entries().get(row);
            if (column == 2) {
                entry.setRepresentation(String.valueOf(value));
            } else if (column == 3) {
                entry.setDescription(String.valueOf(value));
            }
            guide.reindex();
            fireTableCellUpdated(row, column);
        }
    }
}
