package fr.spritai.staffManagerExtension;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public final class StaffManagerExtension extends JavaPlugin implements Listener {

    private HttpServer httpServer;
    private Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private String apiToken;
    private int httpPort;


    private Map<String, List<StaffMessage>> staffMessages = new ConcurrentHashMap<>();
    private Map<String, Map<String, Long>> staffPlaytime = new ConcurrentHashMap<>();
    private Map<UUID, Long> sessionStart = new ConcurrentHashMap<>();
    private Map<String, Object> allStaffs = new ConcurrentHashMap<>();


    private File messagesFile;
    private File playtimeFile;
    private File staffFile;

    @Override
    public void onEnable() {
        getLogger().info("§aStaffManagerTest enabled !");
        Bukkit.getPluginManager().registerEvents(this, this);

        saveDefaultConfig();
        apiToken = getConfig().getString("api-token");
        httpPort = getConfig().getInt("http-port", 8080); // Port configurable

        messagesFile = new File(getDataFolder(), "messages.json");
        playtimeFile = new File(getDataFolder(), "playtime.json");
        staffFile = new File(getDataFolder(), "staffs.json");

        loadData();
        loadStaffFile();
        startHttpServer();
    }

    @Override
    public void onDisable() {
        if (httpServer != null) httpServer.stop(0);
        saveData();
        saveStaffFile();
        getLogger().info("§cStaffManagerTest disabled !");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("staffmanager")) return false;

        if (args.length == 0) {
            sender.sendMessage("§eUsage : /staffmanager <list|purge>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list":
                if (!sender.hasPermission("staffmanager.list")) {
                    sender.sendMessage("§cYou don't have a permission !");
                    return true;
                }
                List<String> onlineStaff = Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("staffmanager.admin"))
                        .map(Player::getName)
                        .collect(Collectors.toList());
                if (onlineStaff.isEmpty()) {
                    sender.sendMessage("§eNo staff connected.");
                } else {
                    sender.sendMessage("§eStaffs connected : §a" + String.join(", ", onlineStaff));
                }
                break;

            case "purge":
                if (!sender.hasPermission("staffmanager.purge")) {
                    sender.sendMessage("§cYou don't have a permission !");
                    return true;
                }
                staffMessages.clear();
                staffPlaytime.clear();
                saveData();
                sender.sendMessage("§cStaff data has been cleared (messages + playtime).");
                break;

            default:
                sender.sendMessage("§eUnknown command. Usage : /staffmanager <list|purge>");
                break;
        }
        return true;
    }


    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("staffmanager.admin")) {
            staffMessages.computeIfAbsent(player.getName(), k -> new ArrayList<>())
                    .add(new StaffMessage(event.getMessage()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("staffmanager.admin")) return;

        sessionStart.put(player.getUniqueId(), System.currentTimeMillis());

        if (!allStaffs.containsKey(player.getName())) {
            allStaffs.put(player.getName(), new HashMap<>());
            saveStaffFile();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("staffmanager.admin")) return;

        long start = sessionStart.getOrDefault(player.getUniqueId(), System.currentTimeMillis());
        long durationMinutes = (System.currentTimeMillis() - start) / (1000 * 60);

        String date = LocalDate.now().toString();
        staffPlaytime.computeIfAbsent(player.getName(), k -> new HashMap<>());
        Map<String, Long> daily = staffPlaytime.get(player.getName());
        daily.put(date, daily.getOrDefault(date, 0L) + durationMinutes);

        sessionStart.remove(player.getUniqueId());
    }

    private void saveData() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(messagesFile), StandardCharsets.UTF_8)) {
                gson.toJson(staffMessages, writer);
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(playtimeFile), StandardCharsets.UTF_8)) {
                gson.toJson(staffPlaytime, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadData() {
        if (messagesFile.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(messagesFile), StandardCharsets.UTF_8)) {
                staffMessages = gson.fromJson(reader,
                        new TypeToken<ConcurrentHashMap<String, List<StaffMessage>>>() {}.getType());
            } catch (Exception e) {
                e.printStackTrace();
                staffMessages = new ConcurrentHashMap<>();
            }
        }
        if (playtimeFile.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(playtimeFile), StandardCharsets.UTF_8)) {
                staffPlaytime = gson.fromJson(reader,
                        new TypeToken<ConcurrentHashMap<String, Map<String, Long>>>() {}.getType());
            } catch (Exception e) {
                e.printStackTrace();
                staffPlaytime = new ConcurrentHashMap<>();
            }
        }
    }

    private void loadStaffFile() {
        if (!staffFile.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(staffFile), StandardCharsets.UTF_8)) {
            allStaffs = gson.fromJson(reader,
                    new TypeToken<ConcurrentHashMap<String, Object>>() {}.getType());
        } catch (Exception e) {
            e.printStackTrace();
            allStaffs = new ConcurrentHashMap<>();
        }
    }

    private void saveStaffFile() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(staffFile), StandardCharsets.UTF_8)) {
                gson.toJson(allStaffs, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(httpPort), 0);

            httpServer.createContext("/staffs", new StaffHandler());
            httpServer.createContext("/staffs/messages", new MessagesHandler());
            httpServer.createContext("/staffs/playtime", new PlaytimeHandler());

            httpServer.setExecutor(null);
            httpServer.start();
            getLogger().info("§eServer HTTP started on port " + httpPort);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return false;
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length == 2 && pair[0].equals("token") && pair[1].equals(apiToken)) {
                return true;
            }
        }
        return false;
    }

    class StaffHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            if (!isAuthorized(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (String staffName : allStaffs.keySet()) {
                Map<String, Object> data = new HashMap<>();
                data.put("name", staffName);

                Player p = Bukkit.getPlayer(staffName);
                data.put("online", p != null);

                Map<String, Long> playtime = staffPlaytime.getOrDefault(staffName, new HashMap<>());
                data.put("totalConnections", playtime.size());
                long totalMinutes = playtime.values().stream().mapToLong(Long::longValue).sum();
                double avgTime = playtime.isEmpty() ? 0 : (double) totalMinutes / playtime.size();
                data.put("averageTime", avgTime);
                data.put("activeDays", playtime.keySet().size());

                result.add(data);
            }

            sendJson(exchange, gson.toJson(result));
        }
    }

    class MessagesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            sendJson(exchange, gson.toJson(staffMessages));
        }
    }

    class PlaytimeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!isAuthorized(exchange)) {
                exchange.sendResponseHeaders(403, -1);
                return;
            }
            sendJson(exchange, gson.toJson(staffPlaytime));
        }
    }

    private void sendJson(HttpExchange exchange, String response) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }


    public static class StaffMessage {
        private String message;
        private String timestamp;

        public StaffMessage(String message) {
            this.message = message;
            this.timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        public String getMessage() {
            return message;
        }

        public String getTimestamp() {
            return timestamp;
        }
    }
}
