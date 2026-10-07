package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

final class GameAdapters {
    static GameAdapter require(String id) {
        if ("game-dev-tycoon".equals(id)) return new Gdt();
        if ("the-farmer-was-replaced".equals(id)) throw new IllegalArgumentException("Farmer contract is not yet qualified; launch disabled.");
        throw new IllegalArgumentException("Unsupported game adapter.");
    }
    private static final class Gdt implements GameAdapter {
        private final Set<Long> locations;
        Gdt() {
            Set<Long> ids = new TreeSet<>(); int[] counts = {10,9,7,7,6,4};
            for (int category=0;category<counts.length;category++) for (int n=1;n<=counts[category];n++) ids.add(4261000L+category*100+n);
            locations = Set.copyOf(ids);
        }
        public String gameName() { return "Game Dev Tycoon"; }
        public Set<Long> locations() { return locations; }
        public Set<Long> goalLocations() { return Set.of(4261010L,4261109L,4261207L,4261307L,4261406L,4261504L); }
        public Map<Long,String> items() { return Map.of(4260001L,"Unlock Topic",4260002L,"Unlock Research",4260003L,"Cash 10k",4260004L,"Fans 100"); }
        public String validateContract(JsonNode slotData, String seed, int team, int slot) throws Exception {
            JsonNode contract = slotData.path("contract"); JsonNode binding = contract.path("binding");
            if (!contract.isObject() || contract.path("schema_version").asInt() != 1
                    || !"gdt_tycoon".equals(contract.path("world").path("id").asText())
                    || !"1.7.9".equals(contract.path("game").path("version").asText())
                    || !"20332874".equals(contract.path("game").path("steam_build").asText())
                    || !seed.equals(binding.path("seed_name").asText()) || team != binding.path("team").asInt(-1)
                    || slot != binding.path("slot").asInt(-1)) throw new IllegalArgumentException("AP game contract identity mismatch.");
            String hash = contract.path("contract_hash").asText(); ObjectNode unsigned = contract.deepCopy(); unsigned.remove("contract_hash");
            if (!hash.matches("[a-f0-9]{64}") || !hash.equals(Json.hash(Json.canonical(unsigned))))
                throw new IllegalArgumentException("AP contract hash mismatch.");
            return hash;
        }
    }
    private GameAdapters() { }
}
