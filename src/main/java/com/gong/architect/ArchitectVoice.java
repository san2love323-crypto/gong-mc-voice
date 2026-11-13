package com.gong.architect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Architect Voice — Simple Voice Chat ↔ 파이썬(voice_bridge.py) 브릿지
 *
 * 프로토콜 (TCP, 기본 localhost:8792):
 *   [4바이트 헤더길이][JSON 헤더][페이로드]
 *   모드 → 파이썬: {"t":"audio","player":"이름","uuid":"..."} + PCM(48kHz mono 16bit LE, 960샘플=20ms)
 *                  {"t":"silence","player":"이름"}   (마이크 패킷 안 올 때 - 말 끝 감지용, 파이썬이 타임아웃으로 처리하므로 선택)
 *   파이썬 → 모드: {"t":"speak","player":"이름","y_offset":3} + PCM(48kHz mono 16bit LE, 길이 자유)  → 그 플레이어 머리 위에서 재생
 *                  {"t":"speak_all"} + PCM → 모든 플레이어 각각 머리 위에서 재생
 *                  {"t":"effect","player":"이름","pitch":1.5}  → (예약) 음성 변조
 */
public class ArchitectVoice implements DedicatedServerModInitializer, VoicechatPlugin {

    public static final String PLUGIN_ID = "architect_voice";
    private static final String HOST = System.getProperty("architect.host", "127.0.0.1");
    private static final int PORT = Integer.getInteger("architect.port", 8792);

    private static MinecraftServer server;
    private static VoicechatServerApi api;
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private volatile Socket sock;
    private volatile DataOutputStream out;
    private final Object outLock = new Object();

    // ---------------- Fabric ----------------
    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> { server = s; startBridgeThread(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> closeSocket());
        log("Architect Voice 로드. 브릿지 " + HOST + ":" + PORT);
    }

    // ---------------- Simple Voice Chat 플러그인 ----------------
    @Override public String getPluginId() { return PLUGIN_ID; }

    @Override
    public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> { api = e.getVoicechat(); log("Voicechat API 준비"); });
        reg.registerEvent(MicrophonePacketEvent.class, this::onMic);
    }

    private void onMic(MicrophonePacketEvent e) {
        if (api == null || e.getSenderConnection() == null) return;
        byte[] opus = e.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) return;
        UUID uuid = e.getSenderConnection().getPlayer().getUuid();
        Object p = e.getSenderConnection().getPlayer().getPlayer();
        String name = p instanceof ServerPlayerEntity sp ? sp.getGameProfile().getName() : uuid.toString();
        OpusDecoder dec = decoders.computeIfAbsent(uuid, u -> api.createDecoder());
        short[] pcm;
        try { pcm = dec.decode(opus); } catch (Exception ex) { return; }
        if (pcm == null || pcm.length == 0) return;
        JsonObject h = new JsonObject();
        h.addProperty("t", "audio"); h.addProperty("player", name); h.addProperty("uuid", uuid.toString());
        send(h, shortsToBytes(pcm));
    }

    // ---------------- 재생 ----------------
    private void speakTo(String playerName, short[] pcm, double yOff) {
        if (api == null || server == null) return;
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(playerName);
            if (sp == null) return;
            playAt(sp, pcm, yOff);
        });
    }

    private void speakAll(short[] pcm, double yOff) {
        if (api == null || server == null) return;
        server.execute(() -> { for (ServerPlayerEntity sp : server.getPlayerManager().getPlayerList()) playAt(sp, pcm, yOff); });
    }

    private void playAt(ServerPlayerEntity sp, short[] pcm, double yOff) {
        ServerLevel level = api.fromServerLevel(sp.getServerWorld());
        Position pos = api.createPosition(sp.getX(), sp.getY() + yOff, sp.getZ());
        LocationalAudioChannel ch = api.createLocationalAudioChannel(UUID.randomUUID(), level, pos);
        if (ch == null) return;
        ch.setDistance(48f);
        OpusEncoder enc = api.createEncoder();
        Thread t = new Thread(() -> {
            try {
                int frame = 960;
                for (int i = 0; i < pcm.length; i += frame) {
                    short[] f = new short[frame];
                    System.arraycopy(pcm, i, f, 0, Math.min(frame, pcm.length - i));
                    ch.send(enc.encode(f));
                    Thread.sleep(20);
                }
                // 마지막 빈 프레임으로 채널 닫힘 신호
                ch.send(enc.encode(new short[frame]));
            } catch (Exception ignored) {
            } finally { enc.close(); }
        }, "architect-speak");
        t.setDaemon(true); t.start();
    }

    // ---------------- 브릿지 소켓 ----------------
    private void startBridgeThread() {
        Thread t = new Thread(() -> {
            while (server != null && !server.isStopped()) {
                try (Socket s = new Socket(HOST, PORT)) {
                    s.setTcpNoDelay(true);
                    sock = s; out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 65536));
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 65536));
                    log("브릿지 연결됨");
                    JsonObject hello = new JsonObject(); hello.addProperty("t", "hello"); hello.addProperty("mod", "architect_voice"); send(hello, new byte[0]);
                    while (true) {
                        int hl = in.readInt();
                        byte[] hb = new byte[hl]; in.readFully(hb);
                        JsonObject h = JsonParser.parseString(new String(hb, StandardCharsets.UTF_8)).getAsJsonObject();
                        int pl = h.has("len") ? h.get("len").getAsInt() : 0;
                        byte[] payload = new byte[pl]; if (pl > 0) in.readFully(payload);
                        handle(h, payload);
                    }
                } catch (Exception e) {
                    sock = null; out = null;
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
                }
            }
        }, "architect-bridge");
        t.setDaemon(true); t.start();
    }

    private void handle(JsonObject h, byte[] payload) {
        String t = h.get("t").getAsString();
        double yOff = h.has("y_offset") ? h.get("y_offset").getAsDouble() : 3.0;
        switch (t) {
            case "speak" -> speakTo(h.get("player").getAsString(), bytesToShorts(payload), yOff);
            case "speak_all" -> speakAll(bytesToShorts(payload), yOff);
            case "ping" -> { JsonObject r = new JsonObject(); r.addProperty("t", "pong"); send(r, new byte[0]); }
            default -> {}
        }
    }

    private void send(JsonObject header, byte[] payload) {
        DataOutputStream o = out;
        if (o == null) return;
        header.addProperty("len", payload.length);
        byte[] hb = header.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (outLock) {
            try { o.writeInt(hb.length); o.write(hb); o.write(payload); o.flush(); }
            catch (IOException e) { closeSocket(); }
        }
    }

    private void closeSocket() { try { if (sock != null) sock.close(); } catch (IOException ignored) {} sock = null; out = null; }

    // ---------------- 유틸 ----------------
    private static byte[] shortsToBytes(short[] s) {
        ByteBuffer b = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : s) b.putShort(v);
        return b.array();
    }
    private static short[] bytesToShorts(byte[] b) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = bb.getShort();
        return s;
    }
    private static void log(String m) { System.out.println("[ArchitectVoice] " + m); }
}
