package fr.jpca.archi.k8simporter;

import org.yaml.snakeyaml.Yaml;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Map;
import java.util.List;

public class Config {
    private static Map<String, Object> config;

    public static void load(String yamlPath) throws FileNotFoundException {
        InputStream inputStream = new FileInputStream(yamlPath);
        System.out.println("loading k8s importer config yaml file " + yamlPath);
        Yaml yaml = new Yaml();
        config = yaml.load(inputStream);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        if (config == null)
            return null;
        return (T) config.get(key);
    }

    @SuppressWarnings("unchecked")
    public static <T> T get(String key, T defaultValue) {
        if (config == null)
            return defaultValue;
        Object val = config.get(key);
        return val != null ? (T) val : defaultValue;
    }

    public static void set(String key, Object value) {
        if (config != null) {
            config.put(key, value);
        }
    }

    // Typed helpers
    public static String getString(String key) {
        return get(key);
    }

    public static String getString(String key, String defaultValue) {
        return get(key, defaultValue);
    }
    public static Boolean getBoolean(String key) {
        return get(key);
    }

    public static Boolean getBoolean(String key, boolean defaultValue) {
        return get(key, defaultValue);
    }

    public static Integer getInt(String key) {
        return get(key);
    }

    public static List<String> getList(String key) {
        return get(key);
    }

    public static Map<String, String> getMap(String key) {
        return get(key);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Map<String, String>> getMapOfMaps(String key) {
        return get(key);
    }

}
