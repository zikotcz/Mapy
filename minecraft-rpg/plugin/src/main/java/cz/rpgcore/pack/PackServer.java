package cz.rpgcore.pack;

import com.sun.net.httpserver.HttpServer;
import cz.rpgcore.RPGCore;
import cz.rpgcore.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Executors;

/** Posílá hráčům resource pack. V režimu builtin ho sám servíruje přes malý HTTP server. */
public final class PackServer implements Listener {
    private final RPGCore plugin;
    private HttpServer server;
    private byte[] hash;
    private byte[] bytes;
    private String mode = "off";

    public PackServer(RPGCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        mode = plugin.getConfig().getString("resource-pack.mode", "builtin").toLowerCase();
        if (mode.equals("off")) {
            return;
        }
        File file = new File(plugin.getDataFolder(), "resourcepack.zip");
        try {
            if (plugin.getConfig().getBoolean("resource-pack.use-bundled", true) || !file.exists()) {
                plugin.saveResource("resourcepack.zip", true);
            }
            bytes = Files.readAllBytes(file.toPath());
            hash = MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (Exception ex) {
            plugin.getLogger().severe("Resource pack se nepodařilo načíst: " + ex.getMessage());
            mode = "off";
            return;
        }
        if (!mode.equals("builtin")) {
            return;
        }
        int port = plugin.getConfig().getInt("resource-pack.port", 8163);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    if (exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
                        exchange.sendResponseHeaders(200, -1);
                        return;
                    }
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            });
            server.setExecutor(Executors.newFixedThreadPool(2));
            server.start();
            plugin.getLogger().info("Resource pack běží na portu " + port + " (" + bytes.length / 1024 + " kB).");
        } catch (IOException ex) {
            plugin.getLogger().severe("Nelze spustit server pro resource pack na portu " + port + ": " + ex.getMessage()
                    + " - změň port v config.yml nebo použij mode: url.");
            mode = "off";
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> send(e.getPlayer()), 20L);
    }

    public boolean send(Player p) {
        if (mode.equals("off") || hash == null || !p.isOnline()) {
            return false;
        }
        String url;
        if (mode.equals("url")) {
            url = plugin.getConfig().getString("resource-pack.url", "");
            if (url.isEmpty()) {
                return false;
            }
        } else {
            String host = plugin.getConfig().getString("resource-pack.public-address", "");
            if (host.isEmpty()) {
                InetSocketAddress virtual = p.getVirtualHost();
                host = virtual != null ? virtual.getHostString() : Bukkit.getIp();
                if (host == null || host.isEmpty()) {
                    host = "localhost";
                }
                if (host.endsWith(".")) {
                    host = host.substring(0, host.length() - 1);
                }
            }
            // hash v adrese = klient si po změně packu stáhne novou verzi
            url = "http://" + host + ":" + plugin.getConfig().getInt("resource-pack.port", 8163) + "/" + HexFormat.of().formatHex(hash) + ".zip";
        }
        p.setResourcePack(url, hash, Text.mm(plugin.getConfig().getString("resource-pack.prompt", "")),
                plugin.getConfig().getBoolean("resource-pack.required", false));
        return true;
    }
}
