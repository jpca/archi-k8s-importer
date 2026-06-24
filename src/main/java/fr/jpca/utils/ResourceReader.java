package fr.jpca.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class ResourceReader {

    public static String readResourceAsString(String resourcePath) throws IOException {
        // resourcePath ex: "data/mon-fichier.txt"
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = ResourceReader.class.getClassLoader();
        }

        try (InputStream is = cl.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Ressource not available in classpath: " + resourcePath);
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return br.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        String content = readResourceAsString("data/mon-fichier.txt");
        System.out.println(content);
    }
}