package de.oliver.fancysitula.api.packets;

import net.kyori.adventure.key.Key;

import java.util.Map;

public class FS_ServerboundCustomClickActionPacket extends FS_ServerboundPacket {

    private final Key id;
    private final Map<String, String> payload;

    public FS_ServerboundCustomClickActionPacket(Type type, Key id, Map<String, String> payload) {
        super(type);
        this.id = id;
        this.payload = payload;
    }

    public Key getId() {
        return id;
    }

    public Map<String, String> getPayload() {
        return payload;
    }

    @Override
    public Type getType() {
        return super.getType();
    }
}
