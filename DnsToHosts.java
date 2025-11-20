import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class DnsToHosts {

    // --- НАСТРОЙКИ ПО УМОЛЧАНИЮ (Google DNS) ---
    private static String dohUrl = "https://dns.google/dns-query";
    private static boolean enableIpv4 = true;
    private static boolean enableIpv6 = true;

    // Имена файлов
    private static final String CONFIG_FILE = "config.txt";
    private static final String INPUT_FILE = "input.txt";
    private static final String OUTPUT_FILE = "output.txt";
    private static final String EXTERNAL_FILE = "extra.txt";
    private static final String FINAL_FILE = "host.txt";

    // Константы DNS
    private static final int TYPE_A = 1;       // IPv4
    private static final int TYPE_AAAA = 28;   // IPv6
    private static final int CLASS_IN = 1;     // Internet

    public static void main(String[] args) {
        System.out.println("Запуск DnsToHosts...");

        // 1. Загрузка конфигурации
        loadConfig();

        // 2. Основной процесс резолвинга
        List<String> outputLines = new ArrayList<>();

        try {
            if (!Files.exists(Paths.get(INPUT_FILE))) {
                System.err.println("Файл " + INPUT_FILE + " не найден! Создаю пустой.");
                Files.createFile(Paths.get(INPUT_FILE));
            }

            List<String> lines = Files.readAllLines(Paths.get(INPUT_FILE), StandardCharsets.UTF_8);

            for (String line : lines) {
                String trimmed = line.trim();

                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    outputLines.add(line);
                    continue;
                }

                System.out.print("Обработка: " + trimmed + " ... ");
                
                Set<String> resolvedIps = new LinkedHashSet<>();
                
                if (enableIpv4) {
                    resolvedIps.addAll(resolveBinary(trimmed, TYPE_A));
                }
                if (enableIpv6) {
                    resolvedIps.addAll(resolveBinary(trimmed, TYPE_AAAA));
                }

                if (resolvedIps.isEmpty()) {
                    System.out.println("Не найдено (или отключено в конфиге).");
                } else {
                    System.out.println("OK (" + resolvedIps.size() + " IP)");
                    for (String ip : resolvedIps) {
                        outputLines.add(ip + " " + trimmed);
                    }
                }
            }

            // Запись промежуточных результатов
            try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(OUTPUT_FILE), StandardCharsets.UTF_8)) {
                for (String outLine : outputLines) {
                    writer.write(outLine);
                    writer.newLine();
                }
            }
            System.out.println("Резолвинг завершен. Результаты в " + OUTPUT_FILE);

            // 3. Слияние файлов
            mergeFiles();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Загружает настройки из config.txt.
     */
    private static void loadConfig() {
        Path path = Paths.get(CONFIG_FILE);
        if (!Files.exists(path)) {
            System.out.println("Файл " + CONFIG_FILE + " не найден. Создаю стандартный (Google DNS).");
            try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                writer.write("# Настройки DnsToHosts"); writer.newLine();
                writer.write("# Примеры: https://dns.google/dns-query"); writer.newLine();
                writer.write("server=https://dns.google/dns-query"); writer.newLine();
                writer.write("ipv4=true"); writer.newLine();
                writer.write("ipv6=true"); writer.newLine();
            } catch (IOException e) {
                System.err.println("Не удалось создать конфиг: " + e.getMessage());
            }
        } else {
            try {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String line : lines) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String key = parts[0].trim().toLowerCase();
                        String value = parts[1].trim();

                        switch (key) {
                            case "server": dohUrl = value; break;
                            case "ipv4": enableIpv4 = Boolean.parseBoolean(value); break;
                            case "ipv6": enableIpv6 = Boolean.parseBoolean(value); break;
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("Ошибка чтения конфига: " + e.getMessage());
            }
        }
        
        System.out.println("--- Текущая конфигурация ---");
        System.out.println("Server: " + dohUrl);
        System.out.println("IPv4:   " + (enableIpv4 ? "ON" : "OFF"));
        System.out.println("IPv6:   " + (enableIpv6 ? "ON" : "OFF"));
        System.out.println("----------------------------");
    }

    private static void mergeFiles() {
        System.out.println("\n--- Начало сборки " + FINAL_FILE + " ---");
        List<String> filesToMerge = new ArrayList<>();
        filesToMerge.add(OUTPUT_FILE);
        filesToMerge.add(EXTERNAL_FILE);

        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(FINAL_FILE), StandardCharsets.UTF_8)) {
            for (String fileName : filesToMerge) {
                Path path = Paths.get(fileName);
                if (Files.exists(path)) {
                    System.out.println("Добавление содержимого: " + fileName);
                    writer.write("# --- Start of " + fileName + " ---"); writer.newLine();
                    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            writer.write(line); writer.newLine();
                        }
                    }
                    writer.write("# --- End of " + fileName + " ---"); writer.newLine(); writer.newLine();
                } else {
                    System.out.println("Файл пропущен (не найден): " + fileName);
                }
            }
            System.out.println("Успешно! Итоговый файл создан: " + FINAL_FILE);
        } catch (IOException e) {
            System.err.println("Ошибка при сборке файлов: " + e.getMessage());
        }
    }

    // --- Бинарный DNS клиент ---

    private static List<String> resolveBinary(String domain, int recordType) {
        List<String> ips = new ArrayList<>();
        try {
            byte[] requestData = createDnsQuery(domain, recordType);
            URL url = new URL(dohUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/dns-message");
            conn.setRequestProperty("Accept", "application/dns-message");
            conn.setRequestProperty("User-Agent", "Java-DNS-Client");
            conn.setConnectTimeout(5000); 
            conn.setReadTimeout(5000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestData);
            }

            int status = conn.getResponseCode();
            if (status == 200) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                try (InputStream is = conn.getInputStream()) {
                    byte[] data = new byte[1024];
                    int nRead;
                    while ((nRead = is.read(data, 0, data.length)) != -1) {
                        buffer.write(data, 0, nRead);
                    }
                }
                ips.addAll(parseDnsResponse(buffer.toByteArray(), recordType));
            }
        } catch (Exception e) {
            System.err.print("[Err: " + e.getMessage() + "] ");
        }
        return ips;
    }

    private static byte[] createDnsQuery(String domain, int type) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeShort(0x1234); dos.writeShort(0x0100); dos.writeShort(0x0001); 
        dos.writeShort(0x0000); dos.writeShort(0x0000); dos.writeShort(0x0000); 
        String[] parts = domain.split("\\.");
        for (String part : parts) {
            byte[] partBytes = part.getBytes(StandardCharsets.UTF_8);
            dos.writeByte(partBytes.length);
            dos.write(partBytes);
        }
        dos.writeByte(0); 
        dos.writeShort(type); dos.writeShort(CLASS_IN); 
        return baos.toByteArray();
    }

    private static List<String> parseDnsResponse(byte[] data, int expectedType) {
        List<String> results = new ArrayList<>();
        DnsBuffer buffer = new DnsBuffer(data);
        try {
            buffer.skip(4); int qCount = buffer.readShort(); int ansCount = buffer.readShort(); buffer.skip(4); 
            for (int i = 0; i < qCount; i++) { buffer.skipName(); buffer.skip(4); }
            for (int i = 0; i < ansCount; i++) {
                buffer.skipName(); int type = buffer.readShort(); int clazz = buffer.readShort();
                buffer.readInt(); int dataLen = buffer.readShort();
                if (type == expectedType && clazz == CLASS_IN) results.add(bytesToIp(buffer.readBytes(dataLen)));
                else buffer.skip(dataLen); 
            }
        } catch (Exception e) { }
        return results;
    }

    private static String bytesToIp(byte[] b) {
        if (b.length == 4) return (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + "." + (b[3] & 0xFF);
        else if (b.length == 16) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i += 2) {
                if (i > 0) sb.append(":");
                int group = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
                sb.append(Integer.toHexString(group));
            }
            return sb.toString();
        }
        return null;
    }

    static class DnsBuffer {
        byte[] data; int pos;
        DnsBuffer(byte[] data) { this.data = data; this.pos = 0; }
        int readShort() { return ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF); }
        int readInt() { return ((data[pos++] & 0xFF) << 24) | ((data[pos++] & 0xFF) << 16) | ((data[pos++] & 0xFF) << 8) | (data[pos++] & 0xFF); }
        byte[] readBytes(int len) { byte[] res = new byte[len]; System.arraycopy(data, pos, res, 0, len); pos += len; return res; }
        void skip(int len) { pos += len; }
        void skipName() {
            while (true) {
                int len = data[pos] & 0xFF;
                if (len == 0) { pos++; return; }
                if ((len & 0xC0) == 0xC0) { pos += 2; return; }
                pos += (len + 1);
            }
        }
    }
}