package com.radiance.extra;

import static org.junit.jupiter.api.Assertions.*;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.*;
import org.apache.maven.artifact.versioning.*;
import org.junit.jupiter.api.Test;

class DistributionMetadataTest {
    @Test
    void compatibilityPinsMatchParentAndEmbeddedModDeclarations() throws Exception {
        Config metadata;
        try (var zip = new ZipFile(System.getProperty("radiance.extra.distribution"))) {
            metadata =
                    new TomlParser()
                            .parse(
                                    new String(
                                            zip.getInputStream(
                                                            zip.getEntry(
                                                                    "META-INF/neoforge.mods.toml"))
                                                    .readAllBytes(),
                                            StandardCharsets.UTF_8));
        }
        assertNotNull(metadata);
        List<Config> dependencies = metadata.get("dependencies.radiance_extra");
        assertEquals(
                Set.of("minecraft", "neoforge", "radiance"),
                dependencies.stream()
                        .filter(c -> "required".equals(c.get("type")))
                        .map(c -> (String) c.get("modId"))
                        .collect(Collectors.toSet()));
        var pins =
                dependencies.stream()
                        .filter(c -> "optional".equals(c.get("type")))
                        .collect(Collectors.toMap(c -> (String) c.get("modId"), c -> c));
        assertEquals(11, pins.size());
        assertFalse(dependencies.stream().anyMatch(c -> "zume".equals(c.get("modId"))));

        Map<String, String> published = new HashMap<>();
        for (String artifact :
                System.getProperty("radiance.test.compatibilityArtifacts")
                        .split(Pattern.quote(File.pathSeparator))) {
            try (var input = java.nio.file.Files.newInputStream(Path.of(artifact))) {
                collectModVersions(input, published);
            }
        }
        assertEquals(
                published.keySet(),
                pins.keySet(),
                "Pin actual mod IDs, not library or filename identities");
        for (var entry : published.entrySet()) {
            Config pin = pins.get(entry.getKey());
            assertEquals("[" + entry.getValue() + "]", pin.get("versionRange"), entry.getKey());
            assertEquals("CLIENT", pin.get("side"));
            assertEquals("NONE", pin.get("ordering"));
            var range = VersionRange.createFromVersionSpec(pin.get("versionRange"));
            assertTrue(range.containsVersion(new DefaultArtifactVersion(entry.getValue())));
            assertFalse(range.containsVersion(new DefaultArtifactVersion("0.0.1")));
            assertFalse(range.containsVersion(new DefaultArtifactVersion("99.0.0")));
        }
    }

    private static void collectModVersions(InputStream archive, Map<String, String> versions)
            throws Exception {
        try (var zip = new ZipInputStream(archive)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("META-INF/neoforge.mods.toml")) {
                    Config metadata =
                            new TomlParser()
                                    .parse(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                    List<Config> mods = metadata.get("mods");
                    for (Config mod : mods) {
                        String id = mod.get("modId"), version = mod.get("version");
                        String previous = versions.putIfAbsent(id, version);
                        assertTrue(
                                previous == null || previous.equals(version),
                                "Conflicting supplied versions of " + id);
                    }
                } else if (entry.getName().startsWith("META-INF/jarjar/")
                        && entry.getName().endsWith(".jar")) {
                    collectModVersions(new ByteArrayInputStream(zip.readAllBytes()), versions);
                }
            }
        }
    }
}
