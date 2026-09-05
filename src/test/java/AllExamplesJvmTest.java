import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Compiles every root-level example through the shared pipeline
 * (Lexer → Parser → Semantic → Optimizer → LemonIR → JvmBackend) and asserts
 * that each program's observable JVM output matches the recorded manifest.
 */
public class AllExamplesJvmTest {

    @Test
    public void allRootExamplesMatchJvmOutputManifest() throws Exception {
        Map<String, String> expectedOutputs = loadManifest();
        TreeSet<String> examples = listRootExamples();

        assertEquals("Every root example must have one manifest entry",
                examples, new TreeSet<>(expectedOutputs.keySet()));

        for (String example : examples) {
            String output = compileAndRun(example);
            assertEquals("JVM output mismatch for " + example,
                    normalize(expectedOutputs.get(example)), normalize(output));
        }
    }

    private Map<String, String> loadManifest() throws Exception {
        File manifest = new File("examples/example-output-manifest.tsv");
        assertTrue("Example output manifest should exist", manifest.exists());
        Map<String, String> outputs = new LinkedHashMap<>();
        for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.US_ASCII)) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            assertEquals("Manifest line must contain name and base64 output: " + line, 2, parts.length);
            assertFalse("Duplicate manifest entry: " + parts[0], outputs.containsKey(parts[0]));
            outputs.put(parts[0], new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        }
        return outputs;
    }

    private TreeSet<String> listRootExamples() {
        File[] files = new File("examples").listFiles((dir, name) -> name.endsWith(".lemon"));
        TreeSet<String> examples = new TreeSet<>();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                examples.add(name.substring(0, name.lastIndexOf('.')));
            }
        }
        return examples;
    }

    private String compileAndRun(String name) throws Exception {
        File sourceFile = new File("examples/" + name + ".lemon");
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile(sourceFile);
        return JvmTestSupport.run(compiled);
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n");
    }
}
