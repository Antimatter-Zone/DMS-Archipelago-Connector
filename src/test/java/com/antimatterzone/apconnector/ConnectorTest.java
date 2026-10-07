package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class ConnectorTest {
    @TempDir Path temporary;
    private static final String SECRET="test-only-native-password";
    private ConnectionConfig config(Path directory) {return config(directory,URI.create("ws://127.0.0.1:1"));}
    private ConnectionConfig config(Path directory,URI endpoint) {return new ConnectionConfig(endpoint,"game-dev-tycoon","Player alias",SECRET,directory,"client-a","manual-room",false,null,null);}
    static JsonNode contract(String seed,int team,int slot) throws Exception {
        ObjectNode contract=Json.MAPPER.valueToTree(Map.of("schema_version",1,"world",Map.of("id","gdt_tycoon","version","0.2.0-source"),
                "game",Map.of("version","1.7.9","steam_build","20332874"),"binding",Map.of("seed_name",seed,"team",team,"slot",slot)));
        contract.put("contract_hash",Json.hash(Json.canonical(contract)));return contract;
    }
    static void handshake(Connector connector,int team,int slot) throws Exception {
        connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","RoomInfo","seed_name","test-seed","version",Map.of("major",0,"minor",6,"build",7))));
        connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","Connected","team",team,"slot",slot,"slot_data",Map.of("contract",contract("test-seed",team,slot)))));
    }
    static void items(Connector connector,long id) {
        connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","ReceivedItems","index",0,"items",List.of(Map.of("item",id,"location",4261001,"player",2,"flags",1)))));
    }
    private HttpResponse<String> post(Connector connector,String action,Map<String,Object> extras) throws Exception {return post(connector,action,extras,true,null,false);}
    private HttpResponse<String> post(Connector connector,String action,Map<String,Object> extras,boolean authorized,String origin,boolean alias) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(Map.of("serverId","manual-room","clientId","client-a"));body.putAll(extras);
        var builder=HttpRequest.newBuilder(URI.create(connector.baseUrl()+(alias?Connector.GDT_ROUTE:Connector.ROUTE)+action)).header("Content-Type","application/json");
        if(authorized) builder.header("Authorization","Bearer "+connector.gameConfiguration().get("sessionToken"));
        if(origin!=null) builder.header("Origin",origin);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private String hash(Connector connector) throws Exception {return Json.MAPPER.readTree(post(connector,"poll",Map.of()).body()).path("slotData").path("contract").path("contract_hash").asText();}
    static void await(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(),"condition did not become true");
    }
    @Test void manualIdentityIsLearnedNotAssumedAndAliasWorks() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,2,7);
            JsonNode response=Json.MAPPER.readTree(post(connector,"poll",Map.of(),true,"null",true).body());
            assertEquals(2,response.path("connection").path("team").asInt());assertEquals(7,response.path("connection").path("slot").asInt());
            assertFalse(response.path("connection").path("encrypted").asBoolean());
        }
    }
    @Test void bearerOriginAndClientIsolation() throws Exception {
        Path second=Files.createDirectory(temporary.resolve("second"));
        var otherConfig=new ConnectionConfig(URI.create("ws://127.0.0.1:1"),"game-dev-tycoon","Different player",SECRET,second,"client-a","manual-room",false,null,null);
        try(var connector=new Connector(config(temporary));var other=new Connector(otherConfig)) {
            handshake(connector,0,1);handshake(other,0,2);
            assertNotEquals(connector.baseUrl(),other.baseUrl());assertNotEquals(connector.gameConfiguration().get("sessionToken"),other.gameConfiguration().get("sessionToken"));
            assertEquals(401,post(connector,"poll",Map.of(),false,null,false).statusCode());
            assertEquals(403,post(connector,"poll",Map.of(),true,"https://attacker.invalid",false).statusCode());
            assertEquals(403,post(connector,"poll",Map.of("clientId","wrong")).statusCode());
            assertEquals(405,post(connector,"poll?password=not-a-secret",Map.of()).statusCode());
        }
    }
    @Test void duplicateConnectorForInstanceFailsClosed() throws Exception {
        try(var connector=new Connector(config(temporary))) {assertThrows(IOException.class,()->new Connector(config(temporary)));}
    }
    @Test void endpointAliasLeaseRejectsDifferentInstanceAndReleasesOnClose() throws Exception {
        Path first=Files.createDirectory(temporary.resolve("first")),second=Files.createDirectory(temporary.resolve("second"));
        Path leases=temporary.resolve("leases");
        try(var connector=new Connector(config(first),leases)) {assertThrows(IOException.class,()->new Connector(config(second),leases));}
        try(var connector=new Connector(config(second),leases)) {assertFalse((boolean)connector.status().get("terminal"));}
        try(var paths=Files.list(leases)) {
            for(Path path:paths.toList()) {assertTrue(path.getFileName().toString().matches("[a-f0-9]{64}\\.lock"));assertEquals(0,Files.size(path));}
        }
    }
    @Test void learnedIdentityLeaseRejectsDifferentAliasesForSameSlot() throws Exception {
        Path first=Files.createDirectory(temporary.resolve("first")),second=Files.createDirectory(temporary.resolve("second"));
        Path leases=temporary.resolve("leases");
        var alias=new ConnectionConfig(URI.create("ws://127.0.0.1:1"),"game-dev-tycoon","Other AP alias",SECRET,second,"client-a","manual-room",false,null,null);
        try(var one=new Connector(config(first),leases);var two=new Connector(alias,leases)) {
            handshake(one,0,1);handshake(two,0,1);assertEquals("slot_already_owned",two.status().get("failure"));assertTrue((boolean)one.status().get("connected"));
        }
        try(var fresh=new Connector(alias,leases)) {handshake(fresh,0,1);assertTrue((boolean)fresh.status().get("connected"));}
    }
    @Test void failedConstructorDoesNotStrandEndpointLease() throws Exception {
        Path invalid=temporary.resolve("missing"),leases=temporary.resolve("leases");
        assertThrows(IOException.class,()->new Connector(config(invalid),leases));
        Files.createDirectory(invalid);try(var connector=new Connector(config(invalid),leases)) {assertFalse((boolean)connector.status().get("terminal"));}
    }
    @Test void endpointLeaseCanonicalizesDefaultPortHostCaseAndEmptyPath() throws Exception {
        Path first=Files.createDirectory(temporary.resolve("first")),second=Files.createDirectory(temporary.resolve("second")),leases=temporary.resolve("leases");
        try(var one=new Connector(config(first,URI.create("wss://EXAMPLE.invalid")),leases)) {
            assertThrows(IOException.class,()->new Connector(config(second,URI.create("wss://example.invalid:443/")),leases));
        }
    }
    @Test void ackNeverDiscardsReplayAndPersistsAcrossProcessRestart() throws Exception {
        String token;
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);items(connector,4260001);token=connector.gameConfiguration().get("sessionToken").toString();
            assertEquals(200,post(connector,"items/ack",Map.of("contractHash",hash(connector),"throughIndex",0)).statusCode());
            assertEquals(200,post(connector,"items/ack",Map.of("contractHash",hash(connector),"throughIndex",-1)).statusCode());
            assertEquals(0,connector.status().get("acknowledgedThrough"));
            assertEquals(1,Json.MAPPER.readTree(post(connector,"poll",Map.of()).body()).path("items").size());
        }
        try(var restarted=new Connector(config(temporary))) {
            assertNotEquals(token,restarted.gameConfiguration().get("sessionToken"));handshake(restarted,0,1);items(restarted,4260001);
            assertEquals(1,Json.MAPPER.readTree(post(restarted,"poll",Map.of()).body()).path("items").size());
            assertEquals(0,restarted.status().get("acknowledgedThrough"));
        }
    }
    @Test void itemReplayComparesSourceAndFlagsNotOnlyName() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);items(connector,4260001);
            connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","ReceivedItems","index",0,"items",List.of(Map.of("item",4260001,"location",4261002,"player",2,"flags",1)))));
            assertEquals("item_replay_changed",connector.status().get("failure"));assertEquals(503,post(connector,"poll",Map.of()).statusCode());
        }
    }
    @Test void unknownItemAndEarlyItemsFailClosed() throws Exception {
        try(var connector=new Connector(config(temporary))) {items(connector,4260001);assertEquals("items_before_identity",connector.status().get("failure"));}
        try(var connector=new Connector(config(temporary))) {handshake(connector,0,1);items(connector,1);assertEquals("unknown_or_malformed_item",connector.status().get("failure"));}
    }
    @Test void seedAndSlotCannotChangeOnReconnect() throws Exception {
        try(var connector=new Connector(config(temporary))) {handshake(connector,0,1);handshake(connector,0,2);assertEquals("slot_identity_mismatch",connector.status().get("failure"));}
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","RoomInfo","seed_name","different","version",Map.of("major",0,"minor",6,"build",7))));
            assertEquals("seed_identity_changed",connector.status().get("failure"));
        }
    }
    @Test void expectedHubIdentityIsAdditionalNotInferred() throws Exception {
        var config=new ConnectionConfig(URI.create("wss://example.invalid"),"game-dev-tycoon","Alias",SECRET,temporary,"client-a","manual-room",true,0,2);
        try(var connector=new Connector(config)) {handshake(connector,0,1);assertEquals("slot_identity_mismatch",connector.status().get("failure"));}
    }
    @Test void wrongHashAndBuildCannotConnect() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","RoomInfo","seed_name","test-seed","version",Map.of("major",0,"minor",6,"build",7))));
            ObjectNode contract=(ObjectNode)contract("test-seed",0,1);contract.put("contract_hash","0".repeat(64));
            connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","Connected","team",0,"slot",1,"slot_data",Map.of("contract",contract))));
            assertTrue((boolean)connector.status().get("terminal"));
        }
    }
    @Test void acknowledgementsValidateRangeAndContract() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);items(connector,4260001);
            assertEquals(400,post(connector,"items/ack",Map.of("contractHash",hash(connector),"throughIndex",1)).statusCode());
            assertEquals(400,post(connector,"items/ack",Map.of("contractHash","wrong","throughIndex",0)).statusCode());
            assertEquals(409,post(connector,"poll",Map.of("nextItemIndex",2)).statusCode());
        }
    }
    @Test void progressKnownLocationsOnlyAndGoalNeedsServerAck() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);String hash=hash(connector);
            assertEquals(400,post(connector,"progress",Map.of("contractHash",hash,"checkedLocationIds",List.of(999999))).statusCode());
            assertEquals(200,post(connector,"progress",Map.of("contractHash",hash,"checkedLocationIds",List.of(4261001),"clientStatus",30)).statusCode());
            JsonNode journal=Json.MAPPER.readTree(Files.readString(temporary.resolve(".dms-connector/connector-state.json")));assertFalse(journal.path("goalRequested").asBoolean());
            var goals=GameAdapters.require("game-dev-tycoon").goalLocations();
            assertEquals(200,post(connector,"progress",Map.of("contractHash",hash,"checkedLocationIds",goals,"clientStatus",30)).statusCode());
            assertFalse((boolean)connector.status().get("goalAccepted"));
            connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","Retrieved","keys",Map.of("_read_client_status_0_1",30))));
            assertTrue((boolean)connector.status().get("goalAccepted"));
        }
    }
    @Test void journalAndLaunchConfigNeverContainApPassword() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);Path launch=connector.writeLaunchConfiguration();
            assertFalse(Files.readString(launch).contains(SECRET));
            assertFalse(Files.readString(temporary.resolve(".dms-connector/connector-state.json")).contains(SECRET));
            assertFalse(Json.MAPPER.writeValueAsString(connector.status()).contains(SECRET));
            assertEquals("ConnectionConfig[redacted]",config(temporary).toString());
        }
        assertFalse(Files.exists(temporary.resolve(".dms-connector/dms-ap-config.json")));
    }
    @Test void corruptOrWrongConnectionJournalIsRejected() throws Exception {
        try(var connector=new Connector(config(temporary))) {handshake(connector,0,1);}
        var wrong=new ConnectionConfig(URI.create("ws://127.0.0.1:2"),"game-dev-tycoon","Alias",SECRET,temporary,"client-a","manual-room",false,null,null);
        assertThrows(IOException.class,()->new Connector(wrong));
        Files.writeString(temporary.resolve(".dms-connector/connector-state.json"),"{broken");
        assertThrows(IOException.class,()->new Connector(config(temporary)));
    }
    @Test void hintsAreExplicitBoundedAndUseServerPolicy() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);
            assertEquals(400,post(connector,"hint",Map.of("itemName","Unlock Topic")).statusCode());
            assertEquals(400,post(connector,"hint",Map.of("itemName","Unlock Topic\n!release","confirmCost",true)).statusCode());
            assertEquals(202,post(connector,"hint",Map.of("itemName","Unlock Topic","confirmCost",true)).statusCode());
            assertEquals(429,post(connector,"hint",Map.of("itemName","Unlock Topic","confirmCost",true)).statusCode());
        }
    }
    @Test void mismatchedDataPackageIsRejected() throws Exception {
        try(var connector=new Connector(config(temporary))) {handshake(connector,0,1);connector.acceptPacket(Json.MAPPER.valueToTree(Map.of("cmd","DataPackage","data",Map.of())));assertEquals("data_package_mismatch",connector.status().get("failure"));}
    }
    @Test void connectionConfigRejectsSecretUrlsAndPlaintextDms() {
        for(String address:List.of("https://example.invalid","ws://user:pass@example.invalid","ws://example.invalid/?secret=1","ws://example.invalid/#secret",
                "ws://room.ap.darkmatterservers.com:443","ws://AP.DARKMATTERSERVERS.COM","ws://ROOM.AP.DARKMATTERSERVERS.COM.","ws://room.ap.darkmatterservers.com.")) {
            assertThrows(IllegalArgumentException.class,()->config(temporary,URI.create(address)));
        }
        assertThrows(IllegalArgumentException.class,()->new ConnectionConfig(URI.create("ws://localhost:38281"),"game-dev-tycoon","Player",SECRET,temporary,"id","room",true,null,null));
        assertThrows(IllegalArgumentException.class,()->new ConnectionConfig(URI.create("wss://example.invalid"),"the-farmer-was-replaced","Player",SECRET,temporary,"id","room",false,null,null));
        assertDoesNotThrow(()->config(temporary,URI.create("ws://notap.darkmatterservers.com")));
        assertDoesNotThrow(()->config(temporary,URI.create("ws://ap.darkmatterservers.com.attacker.invalid")));
    }
    @Test void stdinReaderIsBounded() throws Exception {
        assertEquals("hello",Main.readBoundedLine(new ByteArrayInputStream("hello\nmore".getBytes(StandardCharsets.UTF_8)),5));
        assertThrows(IOException.class,()->Main.readBoundedLine(new ByteArrayInputStream("123456".getBytes(StandardCharsets.UTF_8)),5));
    }
    @Test void canonicalUnicodeMatchesPythonContractHashInput() throws Exception {
        JsonNode value=Json.MAPPER.valueToTree(Map.of("name","café 🌾","literal","\\uABCD"));
        assertEquals("{\"literal\":\"\\\\uABCD\",\"name\":\"caf\\u00e9 \\ud83c\\udf3e\"}",Json.canonical(value));
    }
    @Test void integerFieldsDoNotCoerceStringsOrFloats() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);items(connector,4260001);
            assertEquals(400,post(connector,"items/ack",Map.of("contractHash",hash(connector),"throughIndex",0.5)).statusCode());
            assertEquals(409,post(connector,"poll",Map.of("nextItemIndex","0")).statusCode());
        }
    }
    @Test void realWebSocketHandshakeItemsHintsProgressReconnectAndRestart() throws Exception {
        try(var ap=new FakeAp()) {
            String hash;
            try(var connector=new Connector(config(temporary,ap.uri()))) {
                connector.connect().get(8,TimeUnit.SECONDS);await(()->(int)connector.status().get("receivedItems")==1);
                assertEquals(3,connector.status().get("team"));assertEquals(7,connector.status().get("slot"));hash=hash(connector);
                assertEquals(200,post(connector,"progress",Map.of("contractHash",hash,"checkedLocationIds",List.of(4261001))).statusCode());
                await(()->ap.checks.contains(4261001L));
                assertEquals(202,post(connector,"hint",Map.of("itemName","Unlock Topic","confirmCost",true)).statusCode());
                await(()->ap.hints.get()==1);ap.drop();
                await(()->ap.connections.get()>=2 && Boolean.TRUE.equals(connector.status().get("connected")));
                assertEquals(1,connector.status().get("receivedItems"));
                assertEquals(200,post(connector,"items/ack",Map.of("contractHash",hash,"throughIndex",0)).statusCode());
            }
            try(var restarted=new Connector(config(temporary,ap.uri()))) {
                restarted.connect().get(8,TimeUnit.SECONDS);await(()->Boolean.TRUE.equals(restarted.status().get("connected")));
                assertEquals(1,restarted.status().get("receivedItems"));assertEquals(0,restarted.status().get("acknowledgedThrough"));
                assertEquals(hash,hash(restarted));
            }
            assertNull(ap.failure);
        }
    }
    @Test void realWrongPasswordFailsClosedWithoutLoggingServerReason() throws Exception {
        try(var ap=new FakeAp()) {
            var config=new ConnectionConfig(ap.uri(),"game-dev-tycoon","Player alias","wrong",temporary,"client-a","manual-room",false,null,null);
            try(var connector=new Connector(config)) {
                assertThrows(ExecutionException.class,()->connector.connect().get(8,TimeUnit.SECONDS));
                assertEquals("connection_refused",connector.status().get("failure"));
            }
        }
    }
    @Test void offlineProgressSurvivesConnectorRestartAndIsResent() throws Exception {
        try(var ap=new FakeAp()) {
            String hash;
            try(var connector=new Connector(config(temporary,ap.uri()))) {
                connector.connect().get(8,TimeUnit.SECONDS);hash=hash(connector);ap.drop();
                await(()->!Boolean.TRUE.equals(connector.status().get("connected")));
                var response=post(connector,"progress",Map.of("contractHash",hash,"checkedLocationIds",List.of(4261002)));
                assertEquals(200,response.statusCode());assertTrue(Json.MAPPER.readTree(response.body()).path("queued").asBoolean());
            }
            try(var connector=new Connector(config(temporary,ap.uri()))) {
                connector.connect().get(8,TimeUnit.SECONDS);await(()->ap.checks.contains(4261002L));
                assertEquals(Set.of(4261002L),ap.checks);assertEquals(hash,hash(connector));
            }
        }
    }
    @Test void journalWriteFailureNeverAcknowledgesProgress() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);String hash=hash(connector);
            Files.createDirectory(temporary.resolve(".dms-connector/connector-state.json.tmp"));
            assertEquals(503,post(connector,"progress",Map.of("contractHash",hash,"checkedLocationIds",List.of(4261001))).statusCode());
            assertTrue((boolean)connector.status().get("terminal"));
        }
    }
    @Test void malformedLocalJsonDoesNotDestroyAuthenticatedConnection() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);
            var request=HttpRequest.newBuilder(URI.create(connector.baseUrl()+Connector.ROUTE+"poll"))
                .header("Authorization","Bearer "+connector.gameConfiguration().get("sessionToken"))
                .POST(HttpRequest.BodyPublishers.ofString("{invalid")).build();
            assertEquals(400,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue((boolean)connector.status().get("connected"));
        }
    }
    @Test void protectedStdioProcessHasNoAmzDependencyAndNeverReturnsApPassword() throws Exception {
        try(var ap=new FakeAp()) {
            String classpath=String.join(File.pathSeparator,
                Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(com.fasterxml.jackson.databind.ObjectMapper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(com.fasterxml.jackson.core.JsonFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                Path.of(com.fasterxml.jackson.annotation.JsonCreator.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
            Process process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")?"java.exe":"java").toString(),
                "-cp",classpath,Main.class.getName(),"--stdio").start();
            try {
                Map<String,Object> launch=new LinkedHashMap<>(Map.of("schemaVersion",1,"endpoint",ap.uri().toString(),"gameId","game-dev-tycoon",
                    "slotName","Player alias","password",SECRET,"instance",temporary.toString(),"clientId","stdio-client","serverId","stdio-room","dmsManaged",false));
                OutputStream input=process.getOutputStream();input.write((Json.MAPPER.writeValueAsString(launch)+"\n").getBytes(StandardCharsets.UTF_8));input.flush();
                CompletableFuture<String> response=CompletableFuture.supplyAsync(()->{try{return Main.readBoundedLine(process.getInputStream(),16384);}catch(IOException error){throw new CompletionException(error);}});
                String output=response.get(8,TimeUnit.SECONDS);JsonNode local=Json.MAPPER.readTree(output);
                assertFalse(output.contains(SECRET));assertTrue(local.path("sessionToken").asText().length()>=40);
                assertTrue(local.path("bridgeBaseUrl").asText().startsWith("http://127.0.0.1:"));
                input.write("STOP\n".getBytes(StandardCharsets.UTF_8));input.flush();assertTrue(process.waitFor(8,TimeUnit.SECONDS));assertEquals(0,process.exitValue());
                assertFalse(new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8).contains(SECRET));
            } finally {if(process.isAlive())process.destroyForcibly();}
        }
    }
    @Test void stdinSchemaDoesNotCoerceOrAcceptUnknownFields() throws Exception {
        ObjectNode valid=Json.MAPPER.valueToTree(Map.of("schemaVersion",1,"endpoint","ws://localhost:38281","gameId","game-dev-tycoon",
            "slotName","Player","instance",temporary.toString(),"clientId","client-a","serverId","room","dmsManaged",false));
        assertEquals("",ConnectionConfig.fromJson(valid).password);
        for(String field:List.of("endpoint","gameId","slotName","instance","clientId","serverId","password")) {
            ObjectNode malformed=valid.deepCopy();malformed.putNull(field);assertThrows(IllegalArgumentException.class,()->ConnectionConfig.fromJson(malformed));
        }
        ObjectNode badBoolean=valid.deepCopy();badBoolean.put("dmsManaged","true");assertThrows(IllegalArgumentException.class,()->ConnectionConfig.fromJson(badBoolean));
        ObjectNode badTeam=valid.deepCopy();badTeam.put("expectedTeam",0.5);badTeam.put("expectedSlot",1);assertThrows(IllegalArgumentException.class,()->ConnectionConfig.fromJson(badTeam));
        ObjectNode unknown=valid.deepCopy();unknown.put("rawPassword","secret");assertThrows(IllegalArgumentException.class,()->ConnectionConfig.fromJson(unknown));
    }
    @Test void disconnectImmediatelyFencesOldListenerPackets() throws Exception {
        try(var connector=new Connector(config(temporary))) {
            handshake(connector,0,1);
            Class<?> listenerClass=Class.forName(Connector.class.getName()+"$Listener");
            var constructor=listenerClass.getDeclaredConstructor(Connector.class,long.class);constructor.setAccessible(true);
            WebSocket.Listener stale=(WebSocket.Listener)constructor.newInstance(connector,0L);
            var disconnect=Connector.class.getDeclaredMethod("disconnected",long.class);disconnect.setAccessible(true);disconnect.invoke(connector,0L);
            stale.onText(null,Json.MAPPER.writeValueAsString(List.of(Map.of("cmd","ReceivedItems","index",0,"items",List.of(Map.of("item",4260001,"location",4261001,"player",2))))),true);
            assertFalse((boolean)connector.status().get("connected"));assertFalse((boolean)connector.status().get("terminal"));assertEquals(0,connector.status().get("receivedItems"));
        }
    }
    @Test void serverRestoreReconcilesPreviouslyConfirmedOutboxChecks() throws Exception {
        try(var ap=new FakeAp();var connector=new Connector(config(temporary,ap.uri()))) {
            connector.connect().get(8,TimeUnit.SECONDS);
            assertEquals(200,post(connector,"progress",Map.of("contractHash",hash(connector),"checkedLocationIds",List.of(4261003))).statusCode());
            await(()->{try{return Json.MAPPER.readTree(Files.readString(temporary.resolve(".dms-connector/connector-state.json"))).path("confirmedChecks").size()==1;}catch(IOException ignored){return false;}});
            ap.checks.clear();ap.drop();
            await(()->ap.connections.get()>=2 && ap.checks.contains(4261003L));
            assertTrue((boolean)connector.status().get("connected"));assertNull(ap.failure);
        }
    }

    /** Minimal loopback AP fixture using actual RFC6455 framing and Java's real WebSocket client. */
    static final class FakeAp implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));
        final ExecutorService threads=Executors.newVirtualThreadPerTaskExecutor();
        final Set<Long> checks=ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger connections=new java.util.concurrent.atomic.AtomicInteger(),hints=new java.util.concurrent.atomic.AtomicInteger();
        volatile Socket current;volatile boolean stopped;volatile Throwable failure;
        FakeAp() throws Exception {threads.submit(()->{while(!stopped){try{Socket client=listener.accept();current=client;connections.incrementAndGet();threads.submit(()->serve(client));}catch(IOException error){if(!stopped)failure=error;}}});}
        URI uri(){return URI.create("ws://127.0.0.1:"+listener.getLocalPort());}
        void drop() throws IOException {current.close();}
        void serve(Socket socket) {
            try(socket) {
                InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();
                while(!headers.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")){int next=input.read();if(next<0)return;headers.write(next);if(headers.size()>8192)throw new IOException();}
                String key=Arrays.stream(headers.toString(StandardCharsets.US_ASCII).split("\r\n")).filter(line->line.toLowerCase(Locale.ROOT).startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":",2)[1].trim();
                String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "+accept+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
                send(output,Map.of("cmd","RoomInfo","seed_name","wire-seed","version",Map.of("major",0,"minor",6,"build",7),"hint_cost",10,"location_check_points",1));
                for(;;) {
                    byte[] frame=readFrame(input);if(frame==null)return;
                    for(JsonNode packet:Json.MAPPER.readTree(frame)) {
                        switch(packet.path("cmd").asText()) {
                            case "Connect" -> {
                                if(!SECRET.equals(packet.path("password").asText())) {send(output,Map.of("cmd","ConnectionRefused","errors",List.of("InvalidPassword")));continue;}
                                assertEquals("Game Dev Tycoon",packet.path("game").asText());
                                send(output,Map.of("cmd","Connected","team",3,"slot",7,"checked_locations",checks,"slot_data",Map.of("contract",contract("wire-seed",3,7))));
                            }
                            case "Sync" -> send(output,Map.of("cmd","ReceivedItems","index",0,"items",List.of(Map.of("item",4260001,"location",4261001,"player",2,"flags",1))));
                            case "GetDataPackage" -> send(output,Map.of("cmd","DataPackage","data",Map.of("games",Map.of("Game Dev Tycoon",Map.of("item_name_to_id",Map.of("Unlock Topic",4260001,"Unlock Research",4260002,"Cash 10k",4260003,"Fans 100",4260004))))));
                            case "LocationChecks" -> {for(JsonNode id:packet.path("locations"))checks.add(id.longValue());send(output,Map.of("cmd","RoomUpdate","checked_locations",checks));}
                            case "Say" -> {assertEquals("!hint Unlock Topic",packet.path("text").asText());hints.incrementAndGet();send(output,Map.of("cmd","PrintJSON","type","Hint","data",List.of(Map.of("text","fixture hint","type","text"))));}
                            case "Get" -> send(output,Map.of("cmd","Retrieved","keys",Map.of("_read_client_status_3_7",20)));
                            default -> { }
                        }
                    }
                }
            } catch(IOException ignored) { } catch(Throwable error) {failure=error;}
        }
        private static byte[] readFrame(InputStream input) throws IOException {
            int first=input.read();if(first<0)return null;int second=input.read();if(second<0)return null;
            int opcode=first&15;if(opcode==8)return null;if(opcode!=1)throw new IOException("Unexpected fixture opcode.");
            long length=second&127;if(length==126)length=(input.read()<<8)|input.read();else if(length==127){length=0;for(int i=0;i<8;i++)length=(length<<8)|input.read();}
            if(length>1_048_576 || (second&128)==0)throw new IOException();
            byte[] mask=input.readNBytes(4),data=input.readNBytes((int)length);if(data.length!=length)throw new EOFException();
            for(int i=0;i<data.length;i++)data[i]^=mask[i%4];return data;
        }
        private static void send(OutputStream output,Map<String,Object> packet) throws Exception {
            byte[] data=Json.MAPPER.writeValueAsBytes(List.of(packet));output.write(0x81);
            if(data.length<126)output.write(data.length);else{output.write(126);output.write(data.length>>>8);output.write(data.length&255);}output.write(data);output.flush();
        }
        public void close() throws Exception {stopped=true;listener.close();if(current!=null)current.close();threads.shutdownNow();}
    }
}
