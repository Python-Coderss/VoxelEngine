import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.regex.*;

public class VoxelMethodBody {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java VoxelMethodBody -r <file> <signatureRegEx> <delegatePrefix>");
            System.exit(2);
        }
        if (!args[0].equals("-r")) { System.err.println("Only -r supported"); System.exit(2); }
        Path path = Paths.get(args[1]);
        String sigRegex = args[2].replace("\r\n", "\n").replace("\r", "\n");
        String delegate = args[3].replace("\r\n", "\n").replace("\r", "\n");
        byte[] raw = Files.readAllBytes(path);
        String content = new String(raw, StandardCharsets.UTF_8);
        String norm = content.replace("\r\n", "\n").replace("\r", "\n");

        Matcher m = Pattern.compile(sigRegex).matcher(norm);
        if (!m.find()) { System.err.println("No signature match for: " + sigRegex); System.exit(1); }
        int sigEnd = m.end();
        int braceOpen = findBraceOpen(norm, sigEnd);
        if (braceOpen < 0) { System.err.println("No '{' after signature"); System.exit(1); }
        int braceClose = findBraceClose(norm, braceOpen + 1);
        if (braceClose < 0) { System.err.println("No matching '}' found"); System.exit(1); }

        String before = norm.substring(0, braceOpen + 1);
        String after  = norm.substring(braceClose);
        String out = before + " " + delegate + "; " + after;

        Files.write(path, out.getBytes(StandardCharsets.UTF_8));
        System.out.printf("OK method_body_replace file=%s removed_chars=%d delegate=\"%s;\"%n",
                path, (braceClose - braceOpen), delegate);
    }

    /** Find first opening brace from a start index, skipping line/block comments and string/char literals. */
    static int findBraceOpen(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '{') return i;
            if (c == '/' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
                else if (n == '*') { i += 2; while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++; i += 2; }
                else { i++; }
            } else if (c == '"') {
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) i += 2; else i++;
                }
                i++;
            } else if (c == '\'') {
                i++;
                while (i < s.length() && s.charAt(i) != '\'') {
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) i += 2; else i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Find matching '}' for the '{' just before `start` (call with start = braceOpen + 1, depth = 1). */
    static int findBraceClose(String s, int start) {
        int depth = 1;
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '{') { depth++; i++; }
            else if (c == '}') { depth--; if (depth == 0) return i; i++; }
            else if (c == '/' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '/') { while (i < s.length() && s.charAt(i) != '\n') i++; }
                else if (n == '*') { i += 2; while (i + 1 < s.length() && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) i++; i += 2; }
                else { i++; }
            } else if (c == '"') {
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) i += 2; else i++;
                }
                i++;
            } else if (c == '\'') {
                i++;
                while (i < s.length() && s.charAt(i) != '\'') {
                    if (s.charAt(i) == '\\' && i + 1 < s.length()) i += 2; else i++;
                }
                i++;
            } else {
                i++;
            }
        }
        return -1;
    }
}
