/**
 * Copyright © 2013 tuxed <write@imaginarycode.com>
 * This work is free. You can redistribute it and/or modify it under the
 * terms of the Do What The Fuck You Want To Public License, Version 2,
 * as published by Sam Hocevar. See http://www.wtfpl.net/ for more details.
 */
package com.imaginarycode.minecraft.redisbungee;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import lombok.NonNull;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;
import net.md_5.bungee.event.EventHandler;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisException;

import java.io.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The RedisBungee plugin.
 * <p/>
 * The only function of interest is {@link #getApi()}, which exposes some functions in this class.
 */
public final class RedisBungee extends Plugin implements Listener {
    private static Configuration configuration;
    private JedisPool pool;
    private static RedisBungeeAPI api;
    private PubSubListener psl = null;
    private static List<String> serverIds;

    /**
     * Fetch the {@link RedisBungeeAPI} object created on plugin start.
     *
     * @return the {@link RedisBungeeAPI} object
     */
    public static RedisBungeeAPI getApi() {
        return api;
    }

    static Configuration getConfiguration() {
        return configuration;
    }

    static List<String> getServerIds() {
        return serverIds;
    }

    final Multimap<String, String> serversToPlayers() {
        ImmutableMultimap.Builder<String, String> multimapBuilder = ImmutableMultimap.builder();
        for (String p : getPlayers()) {
            ServerInfo si = getServerFor(p);
            if (si != null)
                multimapBuilder = multimapBuilder.put(si.getName(), p);
        }
        return multimapBuilder.build();
    }

    final int getCount() {
        int c = getProxy().getOnlineCount();
        if (pool != null) {
            Jedis rsc = pool.getResource();
            try {
                for (String i : serverIds) {
                    if (i.equals(configuration.getString("server-id"))) continue;
                    if (rsc.exists("server:" + i + ":playerCount"))
                        try {
                            c += Integer.valueOf(rsc.get("server:" + i + ":playerCount"));
                        } catch (NumberFormatException e) {
                            getLogger().severe("I found a funny number for " + i + "'s player count. Resetting it to 0.");
                            rsc.set("server:" + i + ":playerCount", "0");
                        }
                }
            } catch (JedisConnectionException e) {
                // Redis server has disappeared!
                getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
                pool.returnBrokenResource(rsc);
                throw new RuntimeException("Unable to get total player count", e);
            } finally {
                pool.returnResource(rsc);
            }
        }
        return c;
    }

    final Set<String> getLocalPlayers() {
        ImmutableSet.Builder<String> setBuilder = ImmutableSet.builder();
        for (ProxiedPlayer pp : getProxy().getPlayers())
            setBuilder = setBuilder.add(pp.getName());
        return setBuilder.build();
    }

    final Set<String> getPlayers() {
        ImmutableSet.Builder<String> setBuilder = ImmutableSet.<String>builder().addAll(getLocalPlayers());
        if (pool != null) {
            Jedis rsc = pool.getResource();
            try {
                for (String i : serverIds) {
                    if (i.equals(configuration.getString("server-id"))) continue;
                    Set<String> users = rsc.smembers("server:" + i + ":usersOnline");
                    if (users != null && !users.isEmpty())
                        setBuilder = setBuilder.addAll(users);
                }
            } catch (JedisConnectionException e) {
                // Redis server has disappeared!
                getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
                pool.returnBrokenResource(rsc);
                throw new RuntimeException("Unable to get all players online", e);
            } finally {
                pool.returnResource(rsc);
            }
        }
        return setBuilder.build();
    }

    final Set<String> getPlayersOnServer(@NonNull String server) {
        checkArgument(getProxy().getServerInfo(server) != null, "server doesn't exist");
        return ImmutableSet.copyOf(serversToPlayers().get(server));
    }

    final ServerInfo getServerFor(@NonNull String name) {
        ServerInfo server = null;
        if (getProxy().getPlayer(name) != null) return getProxy().getPlayer(name).getServer().getInfo();
        if (pool != null) {
            Jedis tmpRsc = pool.getResource();
            try {
                if (tmpRsc.hexists("player:" + name, "server"))
                    server = getProxy().getServerInfo(tmpRsc.hget("player:" + name, "server"));
            } catch (JedisConnectionException e) {
                // Redis server has disappeared!
                getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
                pool.returnBrokenResource(tmpRsc);
                throw new RuntimeException("Unable to get server for " + name, e);
            } finally {
                pool.returnResource(tmpRsc);
            }
        }
        return server;
    }

    final long getLastOnline(@NonNull String name) {
        long time = -1L;
        if (getProxy().getPlayer(name) != null) return 0;
        if (pool != null) {
            Jedis tmpRsc = pool.getResource();
            try {
                if (tmpRsc.hexists("player:" + name, "online"))
                    try {
                        time = Long.valueOf(tmpRsc.hget("player:" + name, "online"));
                    } catch (NumberFormatException e) {
                        getLogger().info("I found a funny number for when " + name + " was last online!");
                        boolean found = false;
                        for (String proxyId : serverIds) {
                            if (proxyId.equals(configuration.getString("server-id"))) continue;
                            if (tmpRsc.sismember("server:" + proxyId + ":usersOnline", name)) {
                                found = true;
                                break;
                            }
                        }
                        String value = "0";
                        if (!found) {
                            value = String.valueOf(System.currentTimeMillis());
                            getLogger().info(name + " isn't online. Setting to current time.");
                        } else {
                            getLogger().info(name + " is online. Setting to 0. Please check your BungeeCord instances.");
                            getLogger().info("If they are working properly, and this error does not resolve in a few minutes, please let Tux know!");
                        }
                        tmpRsc.hset("player:" + name, "online", value);
                    }
            } catch (JedisConnectionException e) {
                // Redis server has disappeared!
                getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
                pool.returnBrokenResource(tmpRsc);
                throw new RuntimeException("Unable to get last time online for " + name, e);
            } finally {
                pool.returnResource(tmpRsc);
            }
        }
        return time;
    }

    final InetAddress getIpAddress(@NonNull String name) {
        if (getProxy().getPlayer(name) != null)
            return getProxy().getPlayer(name).getAddress().getAddress();
        InetAddress ia = null;
        if (pool != null) {
            Jedis tmpRsc = pool.getResource();
            try {
                if (tmpRsc.hexists("player:" + name, "ip"))
                    ia = InetAddress.getByName(tmpRsc.hget("player:" + name, "ip"));
            } catch (JedisConnectionException e) {
                // Redis server has disappeared!
                getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
                pool.returnBrokenResource(tmpRsc);
                throw new RuntimeException("Unable to fetch IP address for " + name, e);
            } catch (UnknownHostException ignored) {
                // Best to just return null
            } finally {
                pool.returnResource(tmpRsc);
            }
        }
        return ia;
    }

    final void sendProxyCommand(@NonNull String proxyId, @NonNull String command) {
        checkArgument(serverIds.contains(proxyId) || proxyId.equals("allservers"), "proxyId is invalid");
        Jedis jedis = pool.getResource();
        try {
            jedis.publish("redisbungee-" + proxyId, command);
        } catch (JedisConnectionException e) {
            // Redis server has disappeared!
            getLogger().log(Level.SEVERE, "Unable to get connection from pool - did your Redis server go away?", e);
            pool.returnBrokenResource(jedis);
            throw new RuntimeException("Unable to publish command", e);
        } finally {
            pool.returnResource(jedis);
        }
    }

    @Override
    public void onEnable() {
        try {
            loadConfig();
        } catch (IOException e) {
            throw new RuntimeException("Unable to load/save config", e);
        } catch (JedisConnectionException e) {
            throw new RuntimeException("Unable to connect to your Redis server!", e);
        }
        if (pool != null) {
            Jedis tmpRsc = pool.getResource();
            try {
                tmpRsc.set("server:" + configuration.getString("server-id") + ":playerCount", "0"); // reset
                if (tmpRsc.scard("server:" + configuration.getString("server-id") + ":usersOnline") > 0) {
                    for (String member : tmpRsc.smembers("server:" + configuration.getString("server-id") + ":usersOnline")) {
                        // Are they simply on a different proxy?
                        boolean found = false;
                        for (String proxyId : serverIds) {
                            if (proxyId.equals(configuration.getString("server-id"))) continue;
                            if (tmpRsc.sismember("server:" + proxyId + ":usersOnline", member)) {
                                found = true;
                                break;
                            }
                        }
                        if (!found)
                            cleanUpPlayer(member, tmpRsc);
                        else
                            tmpRsc.srem("server:" + configuration.getString("server-id") + ":usersOnline", member);
                    }
                }
            } finally {
                pool.returnResource(tmpRsc);
            }
            getProxy().getScheduler().schedule(this, new Runnable() {
                @Override
                public void run() {
                    Jedis rsc = pool.getResource();
                    try {
                        rsc.set("server:" + configuration.getString("server-id") + ":playerCount", String.valueOf(getProxy().getOnlineCount()));
                    } catch (JedisConnectionException e) {
                        // Redis server has disappeared!
                        getLogger().log(Level.SEVERE, "Unable to update proxy counts - did your Redis server go away?", e);
                        pool.returnBrokenResource(rsc);
                    } finally {
                        pool.returnResource(rsc);
                    }
                }
            }, 1, 3, TimeUnit.SECONDS);
            getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.GlistCommand());
            getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.FindCommand());
            getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.LastSeenCommand());
            getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.IpCommand());
            getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.SendToAll());
            getProxy().getPluginManager().registerCommand(this, new RedisBungeeCommands.ServerId());
            getProxy().getPluginManager().registerListener(this, this);
            api = new RedisBungeeAPI(this);
            psl = new PubSubListener();
            new Thread(psl, "RedisBungee PubSub Listener").start();
            getProxy().getScheduler().schedule(this, new Runnable() {
                @Override
                public void run() {
                    Jedis tmpRsc = pool.getResource();
                    try {
                        Set<String> players = getLocalPlayers();
                        for (String member : tmpRsc.smembers("server:" + configuration.getString("server-id") + ":usersOnline"))
                            if (!players.contains(member)) {
                                // Are they simply on a different proxy?
                                boolean found = false;
                                for (String proxyId : serverIds) {
                                    if (proxyId.equals(configuration.getString("server-id"))) continue;
                                    if (tmpRsc.sismember("server:" + proxyId + ":usersOnline", member)) {
                                        // Just clean up the set.
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    cleanUpPlayer(member, tmpRsc);
                                    getLogger().warning("Player found in set that was not found locally and globally: " + member);
                                } else {
                                    tmpRsc.srem("server:" + configuration.getString("server-id") + ":usersOnline", member);
                                    getLogger().warning("Player found in set that was not found locally, but is on another proxy: " + member);
                                }
                            }
                    } finally {
                        pool.returnResource(tmpRsc);
                    }
                }
            }, 1, 3, TimeUnit.MINUTES);
        }
        getProxy().registerChannel("RedisBungee");
    }

    @Override
    public void onDisable() {
        if (pool != null) {
            // Poison the PubSub listener
            psl.poison();
            getProxy().getScheduler().cancel(this);
            Jedis tmpRsc = pool.getResource();
            try {
                tmpRsc.set("server:" + configuration.getString("server-id") + ":playerCount", "0"); // reset
                if (tmpRsc.scard("server:" + configuration.getString("server-id") + ":usersOnline") > 0) {
                    for (String member : tmpRsc.smembers("server:" + configuration.getString("server-id") + ":usersOnline"))
                        cleanUpPlayer(member, tmpRsc);
                }
            } finally {
                pool.returnResource(tmpRsc);
            }
            pool.destroy();
        }
    }

    private void loadConfig() throws IOException, JedisConnectionException {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        File file = new File(getDataFolder(), "config.yml");

        if (!file.exists()) {
            file.createNewFile();
            try (InputStream in = getResourceAsStream("example_config.yml");
                 OutputStream out = new FileOutputStream(file)) {
                ByteStreams.copy(in, out);
            }
        }

        configuration = ConfigurationProvider.getProvider(YamlConfiguration.class).load(file);

        String redisServer = configuration.getString("redis-server", "localhost");
        int redisPort = configuration.getInt("redis-port", 6379);
        String redisPassword = configuration.getString("redis-password");

        if (redisPassword != null && (redisPassword.equals("") || redisPassword.equals("none"))) {
            redisPassword = null;
        }

        // Configuration sanity checks.
        if (configuration.get("server-id") == null || configuration.getString("server-id").equals("")) {
            throw new RuntimeException("server-id is not specified in the configuration or is empty");
        }

        if (configuration.getStringList("linked-servers").isEmpty()) {
            throw new RuntimeException("linked-servers is not specified in the configuration or is empty");
        }

        serverIds = ImmutableList.copyOf(configuration.getStringList("linked-servers"));

        if (redisServer != null) {
            if (!redisServer.equals("")) {
                pool = new JedisPool(new JedisPoolConfig(), redisServer, redisPort, 0, redisPassword);
                // Test the connection
                Jedis rsc = null;
                try {
                    rsc = pool.getResource();
                    rsc.exists(String.valueOf(System.currentTimeMillis()));
                    getLogger().log(Level.INFO, "Successfully connected to Redis.");
                } catch (JedisConnectionException e) {
                    if (rsc != null)
                        pool.returnBrokenResource(rsc);
                    pool.destroy();
                    pool = null;
                    rsc = null;
                    throw e;
                } finally {
                    if (rsc != null && pool != null) {
                        pool.returnResource(rsc);
                    }
                }
            }
        } else {
            throw new RuntimeException("No redis server specified!");
        }
    }

    @EventHandler
    public void onPreLogin(PreLoginEvent event) {
        if (pool != null) {
            Jedis rsc = pool.getResource();
            try {
                for (String server : serverIds) {
                    if (rsc.sismember("server:" + server + ":usersOnline", event.getConnection().getName())) {
                        event.setCancelled(true);
                        event.setCancelReason("You are already logged on to this server.");
                    }
                }
            } finally {
                pool.returnResource(rsc);
            }
        }
    }

    @EventHandler
    public void onPlayerConnect(final PostLoginEvent event) {
        if (pool != null) {
            Jedis rsc = pool.getResource();
            try {
                rsc.sadd("server:" + configuration.getString("server-id", "") + ":usersOnline", event.getPlayer().getName());
                rsc.hset("player:" + event.getPlayer().getName(), "online", "0");
                rsc.hset("player:" + event.getPlayer().getName(), "ip", event.getPlayer().getAddress().getAddress().getHostAddress());
            } finally {
                pool.returnResource(rsc);
            }
        }
        // I used to have a task that eagerly waited for the user to be connected.
        // Well, upon further inspection of BungeeCord's source code, this turned
        // out to not be needed at all, since ServerConnectedEvent is called anyway.
    }

    @EventHandler
    public void onPlayerDisconnect(final PlayerDisconnectEvent event) {
        if (pool != null) {
            Jedis rsc = pool.getResource();
            try {
                rsc.hset("player:" + event.getPlayer().getName(), "online", String.valueOf(System.currentTimeMillis()));
                cleanUpPlayer(event.getPlayer().getName(), rsc);
            } finally {
                pool.returnResource(rsc);
            }
        }
    }

    @EventHandler
    public void onServerChange(final ServerConnectedEvent event) {
        if (pool != null) {
            Jedis rsc = pool.getResource();
            try {
                rsc.hset("player:" + event.getPlayer().getName(), "server", event.getServer().getInfo().getName());
            } finally {
                pool.returnResource(rsc);
            }
        }
    }

    @EventHandler
    public void onPing(ProxyPingEvent event) {
        ServerPing old = event.getResponse();
        ServerPing reply = new ServerPing();
        if (configuration.getBoolean("player-list-in-ping", false)) {
            Set<String> players = getPlayers();
            ServerPing.PlayerInfo[] info = new ServerPing.PlayerInfo[players.size()];
            int idx = 0;
            for (String player : players) {
                info[idx] = new ServerPing.PlayerInfo(player, "");
                idx++;
            }
            reply.setPlayers(new ServerPing.Players(old.getPlayers().getMax(), players.size(), info));
        } else {
            reply.setPlayers(new ServerPing.Players(old.getPlayers().getMax(), getCount(), null));
        }
        reply.setDescription(old.getDescription());
        reply.setFavicon(old.getFavicon());
        reply.setVersion(old.getVersion());
        event.setResponse(reply);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (event.getTag().equals("RedisBungee") && event.getSender() instanceof Server) {
            ByteArrayDataInput in = ByteStreams.newDataInput(event.getData());

            String subchannel = in.readUTF();
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            String type;

            switch (subchannel) {
                case "PlayerList":
                    out.writeUTF("Players");
                    Set<String> source = Collections.emptySet();
                    type = in.readUTF();
                    if (type.equals("ALL")) {
                        out.writeUTF("ALL");
                        source = getPlayers();
                    } else {
                        try {
                            source = getPlayersOnServer(type);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    out.writeUTF(Joiner.on(',').join(source));
                    break;
                case "PlayerCount":
                    out.writeUTF("PlayerCount");
                    type = in.readUTF();
                    if (type.equals("ALL")) {
                        out.writeUTF("ALL");
                        out.writeInt(getCount());
                    } else {
                        out.writeUTF(type);
                        try {
                            out.writeInt(getPlayersOnServer(type).size());
                        } catch (IllegalArgumentException e) {
                            out.writeInt(0);
                        }
                    }
                    out.writeInt(getCount());
                    break;
                case "LastOnline":
                    String user = in.readUTF();
                    out.writeUTF("LastOnline");
                    out.writeUTF(user);
                    out.writeLong(getLastOnline(user));
                    break;
                default:
                    break;
            }

            ((Server) event.getSender()).sendData("RedisBungee", out.toByteArray());
        }
    }

    private void cleanUpPlayer(String player, Jedis rsc) {
        rsc.srem("server:" + configuration.getString("server-id") + ":usersOnline", player);
        rsc.hdel("player:" + player, "server");
        rsc.hdel("player:" + player, "ip");
    }

    private class PubSubListener implements Runnable {
        private Jedis rsc;
        private JedisPubSubHandler jpsh;

        private PubSubListener() {
        }

        @Override
        public void run() {
            try {
                rsc = pool.getResource();
                jpsh = new JedisPubSubHandler();
                rsc.subscribe(jpsh, "redisbungee-" + configuration.getString("server-id"), "redisbungee-allservers");
            } catch (JedisException | ClassCastException ignored) {
            }
        }

        public void poison() {
            jpsh.unsubscribe();
            pool.returnResource(rsc);
        }
    }

    private class JedisPubSubHandler extends JedisPubSub {
        @Override
        public void onMessage(String s, String s2) {
            String cmd;
            if (s2.startsWith("/")) {
                cmd = s2.substring(1);
            } else {
                cmd = s2;
            }
            if (s2.trim().length() == 0) return;
            getLogger().info("Invoking command from PubSub: /" + s2);
            getProxy().getPluginManager().dispatchCommand(RedisBungeeCommandSender.instance, cmd);
        }

        @Override
        public void onPMessage(String s, String s2, String s3) {
        }

        @Override
        public void onSubscribe(String s, int i) {
        }

        @Override
        public void onUnsubscribe(String s, int i) {
        }

        @Override
        public void onPUnsubscribe(String s, int i) {
        }

        @Override
        public void onPSubscribe(String s, int i) {
        }
    }
}
