package com.antimatterzone.apconnector;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Set;

/** Only compiled, reviewed adapters are selectable; no uploaded adapter or arbitrary world loading. */
interface GameAdapter {
    String gameName();
    Set<Long> locations();
    Set<Long> goalLocations();
    Map<Long,String> items();
    String validateContract(JsonNode slotData, String seed, int team, int slot) throws Exception;
}
