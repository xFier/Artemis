package com.github.maxopoly.artemis.nbt;

import com.github.maxopoly.artemis.ArtemisPlugin;
import com.github.maxopoly.artemis.rabbit.session.ArtemisPlayerDataTransferSession;
import com.github.maxopoly.artemis.util.BukkitConversion;
import com.github.maxopoly.zeus.ZeusMain;
import com.github.maxopoly.zeus.model.ConnectedMapState;
import com.github.maxopoly.zeus.model.ZeusLocation;
import com.github.maxopoly.zeus.rabbit.outgoing.artemis.SendPlayerData;
import com.github.maxopoly.zeus.rabbit.sessions.PlayerDataTransferSession;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import com.mojang.datafixers.DataFixer;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.craftbukkit.v1_18_R1.CraftServer;
import vg.civcraft.mc.civmodcore.nbt.wrappers.NBTCompound;
import vg.civcraft.mc.civmodcore.players.settings.PlayerSetting;
import vg.civcraft.mc.civmodcore.players.settings.PlayerSettingAPI;


public class CustomWorldNBTStorage extends PlayerDataStorage {

	private final ExecutorService saveExecutor = Executors.newFixedThreadPool(1);

	private static final String CUSTOM_DATA_ID = "artemis_data";

	private static final Set<UUID> activePlayers = new HashSet<>();
	private Map<UUID, Map<String, String>> customDataOriginallyLoaded;

	public static synchronized void addActivePlayer(UUID uuid) {
		activePlayers.add(uuid);
	}

	public static synchronized void removeActivePlayer(UUID uuid) {
		activePlayers.remove(uuid);
	}

	public static synchronized boolean isActive(UUID uuid) {
		return activePlayers.contains(uuid);
	}

	private final File playerDir;

	private CustomWorldNBTStorage(LevelStorageSource.LevelStorageAccess levelStorageAccess, DataFixer datafixer) {
		super(levelStorageAccess, datafixer);
		this.playerDir = levelStorageAccess.getLevelPath(LevelResource.PLAYER_DATA_DIR).toFile();
		this.playerDir.mkdirs();
		this.customDataOriginallyLoaded = new ConcurrentHashMap<>();
	}

	public void shutdown() {
		activePlayers.clear();
	}

	public void stopExecutor() {
		saveExecutor.shutdown();
	}

	public static ZeusLocation readZeusLocation(byte[] playerData) {
		try {
			NBTCompound nbtCompound = new NBTCompound(NbtIo.readCompressed(new ByteArrayInputStream(playerData)));
			double[] pos = nbtCompound.getDoubleArray("Pos");
			ConnectedMapState mapState = ArtemisPlugin.getInstance().getConfigManager().getConnectedMapState();
			return new ZeusLocation(mapState.getWorld(), pos[0], pos[1], pos[2]);
		} catch (IOException e) {
			ZeusMain.getInstance().getLogger().error("Failed to deserialize nbt", playerData);
			return null;
		}
	}

	public void vanillaSave(Player player) {
		CompoundTag compoundTag = player.saveWithoutId(new CompoundTag());
		insertCustomPlayerData(player.getUUID(), compoundTag);
		saveFullData(compoundTag, player.getUUID());
	}

	public void saveFullData(CompoundTag compoundTag, UUID uuid) {
		saveExecutor.submit(() -> {
			try {
				File file = File.createTempFile(uuid.toString() + "-", ".dat", this.playerDir);
				NbtIo.writeCompressed(compoundTag, new FileOutputStream(file));
				File file1 = new File(this.playerDir, uuid + ".dat");
				File file2 = new File(this.playerDir, uuid + ".dat_old");
				Util.safeReplaceFile(file1, file, file2);
			} catch (Exception exception) {
				ZeusMain.getInstance().getLogger().warn("Failed to save player data for {}", uuid.toString());
			}
		});

	}

	public void saveFullData(byte[] rawData, UUID uuid) {
		try {
			ByteArrayInputStream input = new ByteArrayInputStream(rawData);
			CompoundTag compoundTag = NbtIo.readCompressed(input);
			saveFullData(compoundTag, uuid);
		} catch (IOException e) {
			ZeusMain.getInstance().getLogger().warn("Failed to save player data for {}", uuid.toString());
		}
	}

	public CompoundTag vanillaLoad(UUID uuid) {
		CompoundTag compoundTag = null;
		try {
			File file = new File(this.playerDir, String.valueOf(uuid.toString()) + ".dat");
			if (file.exists() && file.isFile()) {
				compoundTag = NbtIo.readCompressed(new FileInputStream(file));
			}
		} catch (Exception exception) {
			ZeusMain.getInstance().getLogger().warn("Failed to vanilla load player data for " + uuid);
		}
		return compoundTag;
	}

	public void save(Player player) {
		if (isActive(player.getUUID())) {
			vanillaSave(player);
			return;
		}
		ArtemisPlugin artemis = ArtemisPlugin.getInstance();
		CompoundTag compoundTag = player.saveWithoutId(new CompoundTag());
		insertCustomPlayerData(player.getUUID(), compoundTag);
		if (ArtemisPlugin.getInstance().getConfigManager().isDebugEnabled()) {
			ArtemisPlugin.getInstance().getLogger().info("Saved NBT : " + compoundTag.toString());
		}
		String transactionId = ArtemisPlugin.getInstance().getTransactionIdManager().pullNewTicket();
		// create session which will be used to save data locally if Zeus is unavailable
		ArtemisPlayerDataTransferSession session = new ArtemisPlayerDataTransferSession(
				ArtemisPlugin.getInstance().getZeus(), transactionId, player);
		ArtemisPlugin.getInstance().getTransactionIdManager().putSession(session);
		// save both location and data in that session
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		try {
			NbtIo.writeCompressed(compoundTag, output);
		} catch (IOException e) {
			artemis.getLogger().severe("Failed to serialize player data: " + e.toString());
			return;
		}
		byte[] data = output.toByteArray();
		ZeusLocation location = new ZeusLocation(artemis.getConfigManager().getWorldName(), player.getX(),
				player.getY(), player.getZ());
		session.setData(data);
		session.setLocation(location);
		// always vanilla save
		vanillaSave(player);
		ArtemisPlugin.getInstance().getRabbitHandler()
				.sendMessage(new SendPlayerData(transactionId, player.getUUID(), data, location));
	}

	public CompoundTag load(Player player) {
		CompoundTag compoundTag = loadCompoundTag(player.getUUID());
		if (compoundTag != null) {
			int i = compoundTag.contains("DataVersion", 3) ? compoundTag.getInt("DataVersion") : -1;
			player.readAdditionalSaveData(NbtUtils.update(this.fixerUpper, DataFixTypes.PLAYER, compoundTag, i));
		}
		return compoundTag;
	}

	public CompoundTag getPlayerData(String s) {
		UUID uuid = UUID.fromString(s);
		return loadCompoundTag(uuid);
	}

	private CompoundTag loadCompoundTag(UUID uuid) {
		PlayerDataTransferSession session = ArtemisPlugin.getInstance().getPlayerDataCache().consumeSession(uuid);
		if (session == null) {
			return null;
		}
		if (session.getData().length == 0) {
			// new player, data will be generated
			return null;
		}
		ByteArrayInputStream input = new ByteArrayInputStream(session.getData());
		try {
			NBTCompound comp = new NBTCompound(NbtIo.readCompressed(input));
			ZeusLocation loc = session.getLocation();
			if (loc == null) {
				loc = BukkitConversion.convertLocation(
						ArtemisPlugin.getInstance().getRandomSpawnHandler().getRandomSpawnLocation(uuid));
			}
			if (loc != null) {
				comp.setDoubleArray("Pos", new double[] {loc.getX(), loc.getY(), loc.getZ()});
			}
			insertWorldUUID(comp);
			if (comp.hasKeyOfType(CUSTOM_DATA_ID, 10)) {
				CompoundTag customData = comp.getCompound(CUSTOM_DATA_ID);
				extractCustomPlayerData(uuid, customData);
			}
			if (ArtemisPlugin.getInstance().getConfigManager().isDebugEnabled()) {
				ArtemisPlugin.getInstance().getLogger().info("Loaded NBT : " + comp);
			}
			return comp;
		} catch (IOException e) {
			ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to load player data", e);
			return null;
		}
	}

	private static void insertWorldUUID(CompoundTag compoundTag) {
		String worldName = ArtemisPlugin.getInstance().getConfigManager().getConnectedMapState().getWorld();
		UUID worldUUID = Bukkit.getWorld(worldName).getUID();
		compoundTag.putLong("WorldUUIDLeast", worldUUID.getLeastSignificantBits());
		compoundTag.putLong("WorldUUIDMost", worldUUID.getMostSignificantBits());
	}

	public static CustomWorldNBTStorage insertCustomNBTHandler() {
		Server server = Bukkit.getServer();
		try {
			Field trueServerField = CraftServer.class.getDeclaredField("console");
			trueServerField.setAccessible(true);
			MinecraftServer trueServer = (MinecraftServer) trueServerField.get(server);
			Field nbtField = MinecraftServer.class.getDeclaredField("k");
			LevelStorageSource.LevelStorageAccess session = trueServer.storageSource;
			DataFixer dataFixer = trueServer.fixerUpper;
			CustomWorldNBTStorage customNBT = new CustomWorldNBTStorage(session, dataFixer);
			overwriteFinalField(nbtField, customNBT, trueServer);
			Field playerListField = CraftServer.class.getDeclaredField("playerList");
			playerListField.setAccessible(true);
			DedicatedPlayerList playerList = (DedicatedPlayerList) playerListField.get(server);
			Field nbtPlayerListField = PlayerList.class.getField("r");
			overwriteFinalField(nbtPlayerListField, customNBT, playerList);
			return customNBT;
		} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
			ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to set custom nbt handler", e);
			return null;
		}
	}

	private void extractCustomPlayerData(UUID player, CompoundTag specialDataCompound) {
		// we keep data in this map so settings not loaded on the server currently are
		// not reset
		Map<String, String> extractedData = new HashMap<>();
		for (PlayerSetting setting : PlayerSettingAPI.getAllSettings()) {
			if (!specialDataCompound.contains(setting.getIdentifier())) {
				continue;
			}
			String serial = specialDataCompound.getString(setting.getIdentifier());
			extractedData.put(setting.getIdentifier(), serial);
			try {
				Object deserialized = setting.deserialize(serial);
				setting.setValueInternal(player, deserialized);
			} catch(Exception e) {
				//otherwise bad data prevents login entirely
				ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE,
						"Failed to parse player setting " + setting.getIdentifier(), e);
			}
		}
		this.customDataOriginallyLoaded.put(player, extractedData);
	}

	private void insertCustomPlayerData(UUID player, CompoundTag generalPlayerDataCompound) {
		Map<String, String> dataToInsert = customDataOriginallyLoaded.computeIfAbsent(player, p -> new HashMap<>());
		for (PlayerSetting setting : PlayerSettingAPI.getAllSettings()) {
			if (!setting.hasValue(player)) {
				continue;
			}
			String serial = setting.serialize(setting.getValue(player));
			dataToInsert.put(setting.getIdentifier(), serial);
		}
		CompoundTag compoundTag = generalPlayerDataCompound;
		CompoundTag customDataCompoundTag = new CompoundTag();
		for (Entry<String, String> entry : dataToInsert.entrySet()) {
			customDataCompoundTag.putString(entry.getKey(), entry.getValue());
		}
		compoundTag.put(CUSTOM_DATA_ID, customDataCompoundTag);
	}

	private static void overwriteFinalField(Field field, Object newValue, Object obj) {
		try {
			field.setAccessible(true);
			field.set(obj, newValue);
		} catch (SecurityException | IllegalArgumentException | IllegalAccessException e) {
			ArtemisPlugin.getInstance().getLogger().log(Level.SEVERE, "Failed to set final field", e.getStackTrace());
		}
	}
}
