package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.file.Path;

/** Secret-bearing configuration deliberately has no generated record toString. */
public final class ConnectionConfig {
    final URI endpoint;
    final String gameId, slotName, password, clientId, serverId;
    final Path instance;
    final Integer expectedTeam, expectedSlot;
    final boolean dmsManaged;
    public ConnectionConfig(URI endpoint, String gameId, String slotName, String password,
            Path instance, String clientId, String serverId, boolean dmsManaged, Integer team, Integer slot) {
        if (endpoint == null || !("ws".equals(endpoint.getScheme()) || "wss".equals(endpoint.getScheme()))
                || endpoint.getHost() == null || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                || ((dmsManaged || normalizedHost(endpoint).equals("ap.darkmatterservers.com")
                || normalizedHost(endpoint).endsWith(".ap.darkmatterservers.com")) && !"wss".equals(endpoint.getScheme())))
            throw new IllegalArgumentException("Use a credential-free WS/WSS address; DMS connections require WSS.");
        if (slotName == null || slotName.isBlank() || slotName.length() > 200 || slotName.chars().anyMatch(Character::isISOControl)
                || password == null || password.length() > 4096 || instance == null
                || clientId == null || !clientId.matches("[A-Za-z0-9_-]{1,100}")
                || serverId == null || !serverId.matches("[A-Za-z0-9_-]{1,100}")
                || ((team == null) != (slot == null)) || (team != null && (team < 0 || slot < 1)))
            throw new IllegalArgumentException("Invalid connection identity.");
        GameAdapters.require(gameId);
        this.endpoint = endpoint; this.gameId = gameId; this.slotName = slotName; this.password = password;
        this.instance = instance.toAbsolutePath().normalize(); this.clientId = clientId; this.serverId = serverId;
        this.dmsManaged = dmsManaged; this.expectedTeam = team; this.expectedSlot = slot;
    }
    static ConnectionConfig fromJson(JsonNode node) {
        if (!node.isObject() || !node.path("schemaVersion").isIntegralNumber() || node.path("schemaVersion").asInt() != 1
                || !node.path("schemaVersion").canConvertToInt() || !node.path("dmsManaged").isBoolean()
                || (node.has("expectedTeam") && (!node.path("expectedTeam").isIntegralNumber() || !node.path("expectedTeam").canConvertToInt()))
                || (node.has("expectedSlot") && (!node.path("expectedSlot").isIntegralNumber() || !node.path("expectedSlot").canConvertToInt())))
            throw new IllegalArgumentException("Unsupported launcher contract.");
        for(String name:java.util.List.of("endpoint","gameId","slotName","instance","clientId","serverId"))
            if(!node.path(name).isTextual() || node.path(name).asText().isBlank()) throw new IllegalArgumentException("Invalid launcher field.");
        if(node.has("password") && !node.path("password").isTextual()) throw new IllegalArgumentException("Invalid launcher field.");
        var allowed=java.util.Set.of("schemaVersion","endpoint","gameId","slotName","password","instance","clientId","serverId","dmsManaged","expectedTeam","expectedSlot");
        var fields=node.fieldNames();while(fields.hasNext()) if(!allowed.contains(fields.next())) throw new IllegalArgumentException("Unknown launcher field.");
        return new ConnectionConfig(URI.create(node.path("endpoint").asText()), node.path("gameId").asText(),
                node.path("slotName").asText(), node.path("password").asText(), Path.of(node.path("instance").asText()),
                node.path("clientId").asText(), node.path("serverId").asText(), node.path("dmsManaged").asBoolean(),
                node.has("expectedTeam") ? node.path("expectedTeam").intValue() : null,
                node.has("expectedSlot") ? node.path("expectedSlot").intValue() : null);
    }
    String fingerprint() throws Exception { return Json.hash(endpoint.normalize()+"\n"+gameId+"\n"+slotName+"\n"+clientId+"\n"+serverId); }
    String canonicalEndpoint() {
        String path=endpoint.normalize().getRawPath();
        return endpoint.getScheme()+"://"+normalizedHost(endpoint)+":"
                +(endpoint.getPort()<0?(encrypted()?443:80):endpoint.getPort())+(path==null || path.isEmpty()?"/":path);
    }
    private static String normalizedHost(URI endpoint) {
        String host=endpoint.getHost();
        if(host.startsWith("[") && host.endsWith("]")) return host.toLowerCase(java.util.Locale.ROOT);
        host=java.net.IDN.toASCII(host,java.net.IDN.USE_STD3_ASCII_RULES).toLowerCase(java.util.Locale.ROOT);
        return host.endsWith(".")?host.substring(0,host.length()-1):host;
    }
    public boolean encrypted() { return "wss".equals(endpoint.getScheme()); }
    @Override public String toString() { return "ConnectionConfig[redacted]"; }
}
