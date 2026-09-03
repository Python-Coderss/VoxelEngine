package villager.voice;

import java.nio.file.Paths;

/** Dev probe: print per-token phonemes to inspect G2P behavior. */
public final class TestMisakiDebug {
    public static void main(String[] args) throws Exception {
        MisakiEnG2P g2p = new MisakiEnG2P(Paths.get(args[0]));
        java.util.List<String> ss = new java.util.ArrayList<String>();
        if (args.length > 1) {
            for (int i = 1; i < args.length; i++) {
                ss.add(args[i]);
            }
        } else {
            ss.add("I am haggling you.");
        }
        StringBuilder sb = new StringBuilder();
        for (String s : ss) {
            sb.append(s).append('\t').append(g2p.phonemize(s)).append('\n');
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/g2p_debug.txt"),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("wrote /tmp/g2p_debug.txt");
        // Token-level dump
        StringBuilder td = new StringBuilder();
        java.lang.reflect.Method m = MisakiEnG2P.class.getDeclaredMethod("phonemizeTokens", String.class);
        m.setAccessible(true);
        for (String s : ss) {
            td.append("== ").append(s).append('\n');
            td.append(m.invoke(g2p, s)).append('\n');
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("/tmp/g2p_tokens.txt"),
                td.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("wrote /tmp/g2p_tokens.txt");
    }
}
