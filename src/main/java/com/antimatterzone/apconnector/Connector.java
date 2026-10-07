package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One native AP connection, one private local game connection; no AMZ network dependency. */
public final class Connector implements AutoCloseable {
    static final String ROUTE = "/api/archipelago/v1/client/", GDT_ROUTE = "/api/archipelago/gdt/client/";
    private final ConnectionConfig config;
    private final GameAdapter adapter;
    private final LocalState state;
    private final String fingerprint, token;
    private final Path leaseRoot;
    private final SessionLease connectionLease;
    private SessionLease bindingLease;
    private final HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private final List<JsonNode> items = new ArrayList<>();
    private final Set<Long> pendingChecks = new TreeSet<>(), confirmedChecks = new TreeSet<>();
    private final List<JsonNode> messages = new ArrayList<>();
    private JsonNode slotData, dataPackage = Json.MAPPER.createObjectNode();
    private WebSocket socket;
    private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);
    private boolean started, closed, terminal, connected, reconnectScheduled, goalRequested, goalAccepted;
    private int team=-1, slot=-1, throughIndex=-1, reconnectAttempts, hintPoints;
    private long epoch, lastHintMillis;
    private String seed="", contractHash="", failure="not_connected";
    private int hintCost, locationCheckPoints;

    public Connector(ConnectionConfig config) throws Exception {
        this(config,SessionLease.defaultRoot());
    }
    Connector(ConnectionConfig config,Path leaseRoot) throws Exception {
        this.config=config; adapter=GameAdapters.require(config.gameId); fingerprint=config.fingerprint();
        this.leaseRoot=leaseRoot;
        connectionLease=SessionLease.acquire(leaseRoot,"alias\n"+config.canonicalEndpoint()+"\n"+config.slotName);
        try {state=new LocalState(config.instance);} catch(Exception error) {connectionLease.close();throw error;}
        try {loadState();} catch(Exception error) {try {state.close();}finally {connectionLease.close();}throw new IOException("Connector state is corrupt or belongs to another connection.");}
        byte[] random=new byte[32]; new SecureRandom().nextBytes(random); token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        try {server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);}
        catch(Exception error) {try {state.close();}finally {connectionLease.close();}throw error;}
        server.createContext(ROUTE,this::handle);
        if ("game-dev-tycoon".equals(config.gameId)) server.createContext(GDT_ROUTE,this::handle);
        server.setExecutor(handlers); server.start();
    }
    public synchronized CompletableFuture<Void> connect() {
        if (!started && !closed) { started=true; connectSocket(); }
        return ready;
    }
    public String baseUrl() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    /** Secret local bearer: hand directly over a protected pipe, never log this value. */
    public Map<String,Object> gameConfiguration() {
        return Map.of("schemaVersion",1,"bridgeBaseUrl",baseUrl(),"botBaseUrl",baseUrl(),"serverId",config.serverId,
                "clientId",config.clientId,"sessionToken",token,"gameVersion","1.7.9","steamBuild","20332874");
    }
    public synchronized Path writeLaunchConfiguration() throws IOException { return state.writeLaunch(gameConfiguration()); }
    public synchronized Map<String,Object> status() {
        return Map.of("connected",connected,"terminal",terminal,"failure",failure,"encrypted",config.encrypted(),
                "team",team,"slot",slot,"receivedItems",items.size(),"acknowledgedThrough",throughIndex,"goalAccepted",goalAccepted);
    }
    private synchronized void connectSocket() {
        if (closed || terminal) return; reconnectScheduled=false; long attempt=++epoch;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(12)).buildAsync(config.endpoint,new Listener(attempt))
            .whenComplete((ws,error)->{if(error!=null) disconnected(attempt);});
    }
    private synchronized void disconnected(long attempt) {
        if (attempt!=epoch || closed || terminal) return;
        // Fence callbacks immediately, not when the delayed replacement socket opens.
        epoch++; connected=false; failure="reconnecting";
        if (socket!=null) { socket.abort(); socket=null; }
        if (reconnectScheduled) return; reconnectScheduled=true;
        scheduler.schedule(this::connectSocket,Math.min(20,1L<<Math.min(reconnectAttempts++,4)),TimeUnit.SECONDS);
    }
    private synchronized void fail(String safeReason) {
        if (terminal || closed) return; terminal=true; connected=false; failure=safeReason;
        ready.completeExceptionally(new IOException(safeReason)); if(socket!=null) socket.abort();
        if(bindingLease!=null) bindingLease.close();connectionLease.close();
    }
    private synchronized void send(Map<String,Object> packet) {
        if (socket==null || closed || terminal) return;
        WebSocket current=socket; long attempt=epoch;
        try {
            String text=Json.MAPPER.writeValueAsString(List.of(packet));
            sendTail=sendTail.handle((ignored,error)->null).thenCompose(ignored-> {
                synchronized(this) { if (attempt!=epoch || closed || terminal) return CompletableFuture.completedFuture(current); }
                return current.sendText(text,true);
            }).exceptionally(error->{ disconnected(attempt); return null; });
        } catch(Exception error) { fail("packet_serialization_failed"); }
    }
    synchronized void acceptPacket(JsonNode packet) {
        if (closed || terminal) return;
        try {
            switch(packet.path("cmd").asText()) {
                case "RoomInfo" -> {
                    String next=packet.path("seed_name").asText(); JsonNode version=packet.path("version");
                    if(next.isBlank() || (!seed.isBlank() && !seed.equals(next))) {fail("seed_identity_changed");return;}
                    if(version.path("major").asInt(-1)!=0 || version.path("minor").asInt(-1)!=6 || version.path("build").asInt(-1)<7) {fail("unsupported_ap_version");return;}
                    seed=next; hintCost=packet.path("hint_cost").asInt(); locationCheckPoints=packet.path("location_check_points").asInt();
                    send(Map.of("cmd","Connect","game",adapter.gameName(),"name",config.slotName,"password",config.password,"uuid",config.clientId,
                        "version",Map.of("major",0,"minor",6,"build",7,"class","Version"),"items_handling",7,"tags",List.of("AP"),"slot_data",true));
                }
                case "Connected" -> {
                    int nextTeam=packet.path("team").asInt(-1), nextSlot=packet.path("slot").asInt(-1);
                    if(!packet.path("team").isIntegralNumber() || !packet.path("team").canConvertToInt()
                        || !packet.path("slot").isIntegralNumber() || !packet.path("slot").canConvertToInt()
                        || seed.isBlank() || nextTeam<0 || nextSlot<1 || (team>=0 && (team!=nextTeam || slot!=nextSlot))
                        || (config.expectedTeam!=null && (config.expectedTeam!=nextTeam || config.expectedSlot!=nextSlot))) {fail("slot_identity_mismatch");return;}
                    JsonNode received=packet.path("slot_data"); String nextHash=adapter.validateContract(received,seed,nextTeam,nextSlot);
                    if(!contractHash.isBlank() && !contractHash.equals(nextHash)) {fail("contract_identity_changed");return;}
                    if(bindingLease==null) {
                        try {bindingLease=SessionLease.acquire(leaseRoot,"binding\n"+config.canonicalEndpoint()+"\n"+seed+"\n"+nextTeam+"\n"+nextSlot);}
                        catch(Exception error) {fail("slot_already_owned");return;}
                    }
                    team=nextTeam;slot=nextSlot;slotData=received.deepCopy();contractHash=nextHash;
                    // Connected is a full authoritative snapshot; RoomUpdate is additive.
                    // Retaining old confirmations would strand the outbox after server restore.
                    confirmedChecks.clear();acceptChecks(packet.path("checked_locations"));goalAccepted=false;
                    hintPoints=packet.path("hint_points").asInt();
                    persist(); connected=true;failure="";reconnectAttempts=0;
                    send(Map.of("cmd","Sync")); send(Map.of("cmd","StatusUpdate","status",20));
                    send(Map.of("cmd","GetDataPackage","games",List.of(adapter.gameName())));
                    send(Map.of("cmd","SetNotify","keys",List.of(statusKey())));send(Map.of("cmd","Get","keys",List.of(statusKey())));
                    flush();ready.complete(null);
                }
                case "ReceivedItems" -> {
                    if(!connected) {fail("items_before_identity");return;}
                    int index=packet.path("index").asInt(-1); JsonNode incoming=packet.path("items");
                    if(!packet.path("index").isIntegralNumber() || !packet.path("index").canConvertToInt() || index<0 || !incoming.isArray() || incoming.size()>100_000) {fail("malformed_item_history");return;}
                    if(index>items.size()) {send(Map.of("cmd","Sync"));return;}
                    for(JsonNode item:incoming) {
                        if(!item.path("item").isIntegralNumber() || !adapter.items().containsKey(item.path("item").longValue())
                            || !item.path("location").isIntegralNumber() || !item.path("player").isIntegralNumber()) {fail("unknown_or_malformed_item");return;}
                        JsonNode record=Json.MAPPER.valueToTree(Map.of("index",index,"id",item.path("item").longValue(),
                            "name",adapter.items().get(item.path("item").longValue()),"location",item.path("location").longValue(),
                            "player",item.path("player").intValue(),"flags",item.path("flags").asInt()));
                        if(index<items.size() && !items.get(index).equals(record)) {fail("item_replay_changed");return;}
                        if(index==items.size()) items.add(record); index++;
                        if(items.size()>100_000) {fail("item_history_limit");return;}
                    }
                    persist();
                }
                case "RoomUpdate" -> {if(connected) {acceptChecks(packet.path("checked_locations")); if(packet.has("hint_points")) hintPoints=packet.path("hint_points").asInt(); persist();}}
                case "Retrieved" -> {if(connected && packet.path("keys").has(statusKey())) {goalAccepted=packet.path("keys").path(statusKey()).asInt()==30;persist();}}
                case "SetReply" -> {if(connected && statusKey().equals(packet.path("key").asText())) {goalAccepted=packet.path("value").asInt()==30;persist();}}
                case "DataPackage" -> {
                    if(!connected) return; JsonNode game=packet.path("data").path("games").path(adapter.gameName());
                    for(var entry:adapter.items().entrySet()) if(game.path("item_name_to_id").path(entry.getValue()).asLong(-1)!=entry.getKey()) {fail("data_package_mismatch");return;}
                    dataPackage=packet.path("data").deepCopy();
                }
                case "PrintJSON" -> {if(connected) {messages.add(packet.deepCopy());if(messages.size()>100) messages.removeFirst();}}
                case "ConnectionRefused" -> fail("connection_refused");
                case "InvalidPacket" -> fail("server_rejected_packet");
                default -> { }
            }
        } catch(Exception error) { fail("invalid_contract_or_state_write"); }
    }
    private String statusKey() {return "_read_client_status_"+team+"_"+slot;}
    private void acceptChecks(JsonNode checks) {if(checks.isArray()) for(JsonNode check:checks) if(check.isIntegralNumber() && adapter.locations().contains(check.longValue())) confirmedChecks.add(check.longValue());}
    private void flush() {
        if(!connected) return; Set<Long> unsent=new TreeSet<>(pendingChecks);unsent.removeAll(confirmedChecks);
        if(!unsent.isEmpty()) send(Map.of("cmd","LocationChecks","locations",unsent));
        if(goalRequested) {send(Map.of("cmd","StatusUpdate","status",30));send(Map.of("cmd","Get","keys",List.of(statusKey())));}
    }
    private void loadState() throws Exception {
        JsonNode node=state.read();if(node==null) return;
        if(node.path("schemaVersion").asInt()!=1 || !fingerprint.equals(node.path("fingerprint").asText())) throw new IOException();
        seed=node.path("seed").asText();team=node.path("team").asInt(-1);slot=node.path("slot").asInt(-1);contractHash=node.path("contractHash").asText();
        slotData=node.path("slotData");
        if(!contractHash.isBlank() && !contractHash.equals(adapter.validateContract(slotData,seed,team,slot))) throw new IOException();
        if(!node.path("items").isArray() || node.path("items").size()>100_000) throw new IOException();
        for(JsonNode item:node.path("items")) {
            if(!item.path("index").isIntegralNumber() || !item.path("id").isIntegralNumber()
                || !item.path("location").isIntegralNumber() || !item.path("player").isIntegralNumber() || !item.path("flags").isIntegralNumber()
                || item.path("index").asInt(-1)!=items.size() || !adapter.items().getOrDefault(item.path("id").asLong(-1),"").equals(item.path("name").asText())) throw new IOException();
            items.add(Json.MAPPER.valueToTree(Map.of("index",items.size(),"id",item.path("id").longValue(),
                "name",item.path("name").asText(),"location",item.path("location").longValue(),
                "player",item.path("player").intValue(),"flags",item.path("flags").intValue())));
        }
        for(JsonNode id:node.path("pendingChecks")) {if(!id.isIntegralNumber() || !adapter.locations().contains(id.longValue())) throw new IOException();pendingChecks.add(id.longValue());}
        acceptChecks(node.path("confirmedChecks")); throughIndex=node.path("throughIndex").asInt(-1);
        if(throughIndex< -1 || throughIndex>=items.size()) throw new IOException();
        goalRequested=node.path("goalRequested").asBoolean();goalAccepted=node.path("goalAccepted").asBoolean();
        if(goalRequested && !pendingChecks.containsAll(adapter.goalLocations())) throw new IOException();
    }
    private void persist() throws IOException {
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("schemaVersion",1);snapshot.put("fingerprint",fingerprint);
        snapshot.put("seed",seed);snapshot.put("team",team);snapshot.put("slot",slot);snapshot.put("contractHash",contractHash);
        snapshot.put("slotData",slotData);snapshot.put("items",items);snapshot.put("pendingChecks",pendingChecks);snapshot.put("confirmedChecks",confirmedChecks);
        snapshot.put("throughIndex",throughIndex);snapshot.put("goalRequested",goalRequested);snapshot.put("goalAccepted",goalAccepted);state.write(snapshot);
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String origin=exchange.getRequestHeaders().getFirst("Origin");
            if(!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                || !("127.0.0.1:"+server.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"))
                || (origin!=null && !origin.equals("null") && !origin.equals("file://"))) {reply(exchange,403,Map.of("error","forbidden"));return;}
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin",origin==null?"null":origin);
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers","Authorization, Content-Type");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods","POST, OPTIONS");
            if(exchange.getRequestMethod().equals("OPTIONS")) {reply(exchange,204,null);return;}
            String authorization=exchange.getRequestHeaders().getFirst("Authorization");
            if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),String.valueOf(authorization).getBytes(StandardCharsets.UTF_8))) {reply(exchange,401,Map.of("error","unauthorized"));return;}
            if(!exchange.getRequestMethod().equals("POST") || exchange.getRequestURI().getRawQuery()!=null) {reply(exchange,405,Map.of("error","method_not_allowed"));return;}
            byte[] body=exchange.getRequestBody().readNBytes(1_048_577);if(body.length>1_048_576) {reply(exchange,413,Map.of("error","body_too_large"));return;}
            JsonNode input=Json.MAPPER.readTree(body);
            synchronized(this) {
                if(input==null || !config.serverId.equals(input.path("serverId").asText()) || !config.clientId.equals(input.path("clientId").asText())) {reply(exchange,403,Map.of("error","identity_mismatch"));return;}
                String path=exchange.getRequestURI().getPath(), prefix=path.startsWith(ROUTE)?ROUTE:GDT_ROUTE;
                String action=path.substring(prefix.length());
                if(action.equals("status")) {reply(exchange,200,status());return;}
                if(terminal || closed || slotData==null) {reply(exchange,503,Map.of("error",terminal?"session_blocked":"identity_not_ready"));return;}
                switch(action) {
                    case "sync", "poll" -> {
                        int next=input.path("nextItemIndex").asInt(0);
                        if((input.has("nextItemIndex") && (!input.path("nextItemIndex").isIntegralNumber() || !input.path("nextItemIndex").canConvertToInt()))
                            || next<0 || next>items.size()) {send(Map.of("cmd","Sync"));reply(exchange,409,Map.of("error","history_not_ready"));return;}
                        reply(exchange,200,Map.of("ok",true,"slotData",slotData,"connection",Map.of("seed_name",seed,"team",team,"slot",slot,"connected",connected,"encrypted",config.encrypted()),
                            "items",new ArrayList<>(items.subList(next,items.size())),"dataPackage",dataPackage,"messages",new ArrayList<>(messages),
                            "hintPolicy",Map.of("costPercent",hintCost,"pointsPerCheck",locationCheckPoints,"points",hintPoints)));
                    }
                    case "items/ack" -> {
                        int through=input.path("throughIndex").asInt(-2);
                        if(!input.path("throughIndex").isIntegralNumber() || !input.path("throughIndex").canConvertToInt()
                            || !contractHash.equals(input.path("contractHash").asText()) || through< -1 || through>=items.size()) {reply(exchange,400,Map.of("error","invalid_ack"));return;}
                        throughIndex=Math.max(throughIndex,through);persist();reply(exchange,200,Map.of("ok",true));
                    }
                    case "progress" -> {
                        JsonNode ids=input.path("checkedLocationIds");
                        if(!contractHash.equals(input.path("contractHash").asText()) || !ids.isArray() || ids.size()>adapter.locations().size()) {reply(exchange,400,Map.of("error","invalid_progress"));return;}
                        Set<Long> checks=new TreeSet<>();for(JsonNode id:ids) {if(!id.isIntegralNumber() || !adapter.locations().contains(id.longValue())) {reply(exchange,400,Map.of("error","invalid_location"));return;}checks.add(id.longValue());}
                        pendingChecks.addAll(checks);if(input.path("clientStatus").asInt()==30 && pendingChecks.containsAll(adapter.goalLocations())) goalRequested=true;
                        persist();flush();reply(exchange,200,Map.of("ok",true,"queued",!connected,"clientGoalAccepted",goalAccepted));
                    }
                    case "hint" -> {
                        String itemName=input.path("itemName").asText();
                        if(!connected) {reply(exchange,503,Map.of("error","ap_reconnecting"));return;}
                        if(!input.path("confirmCost").asBoolean() || !adapter.items().containsValue(itemName)) {reply(exchange,400,Map.of("error","explicit_valid_hint_required"));return;}
                        long now=System.currentTimeMillis();if(now-lastHintMillis<2000) {reply(exchange,429,Map.of("error","hint_rate_limited"));return;}
                        lastHintMillis=now;send(Map.of("cmd","Say","text","!hint "+itemName));reply(exchange,202,Map.of("ok",true,"serverDecidesCost",true));
                    }
                    default -> reply(exchange,404,Map.of("error","not_found"));
                }
            }
        } catch(com.fasterxml.jackson.core.JsonProcessingException error) {reply(exchange,400,Map.of("error","invalid_json"));}
        catch(IOException error) {fail("state_or_request_io_failed");reply(exchange,503,Map.of("error","state_io_failed"));}
        catch(Exception error) {reply(exchange,400,Map.of("error","invalid_request"));}
        finally {exchange.close();}
    }
    private void reply(HttpExchange exchange,int status,Object value) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Content-Type","application/json");
        byte[] bytes=value==null?new byte[0]:Json.MAPPER.writeValueAsBytes(value);exchange.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);
        if(bytes.length>0) exchange.getResponseBody().write(bytes);
    }
    private final class Listener implements WebSocket.Listener {
        private final long attempt;private final StringBuilder pending=new StringBuilder();
        Listener(long attempt) {this.attempt=attempt;}
        public void onOpen(WebSocket webSocket) {
            synchronized(Connector.this) {if(attempt!=epoch || closed || terminal) {webSocket.abort();return;}socket=webSocket;sendTail=CompletableFuture.completedFuture(null);}
            scheduler.schedule(()->{synchronized(Connector.this) {if(attempt==epoch && !connected) disconnected(attempt);}},20,TimeUnit.SECONDS);
            webSocket.request(1);
        }
        public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last) {
            synchronized(Connector.this) {
                if(attempt!=epoch || closed || terminal) return null;
                pending.append(data);if(pending.length()>8_388_608) {fail("packet_size_limit");return null;}
                if(last) {try {JsonNode packets=Json.MAPPER.readTree(pending.toString());if(!packets.isArray()) throw new IOException();for(JsonNode packet:packets) acceptPacket(packet);}
                    catch(Exception error) {fail("malformed_server_packet");}pending.setLength(0);}
            }
            webSocket.request(1);return null;
        }
        public CompletionStage<?> onClose(WebSocket webSocket,int statusCode,String reason) {disconnected(attempt);return null;}
        public void onError(WebSocket webSocket,Throwable error) {disconnected(attempt);}
    }
    public synchronized void close() {
        if(closed) return;closed=true;connected=false;epoch++;server.stop(0);handlers.shutdownNow();scheduler.shutdownNow();if(socket!=null) socket.abort();
        ready.completeExceptionally(new IOException("connector_closed"));try {state.close();}catch(IOException ignored) { }
        if(bindingLease!=null)bindingLease.close();connectionLease.close();
    }
}
