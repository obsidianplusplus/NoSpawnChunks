package cn.panda.nospawnchunks;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import lombok.Getter;
import cn.panda.nospawnchunks.listeners.WorldListener;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * NoSpawnChunks 插件主类。
 * 该插件用于控制特定世界中区块的卸载行为，以减少内存占用和提高服务器性能。
 */
public class NoSpawnChunks extends JavaPlugin {
	// 配置相关字段
	private @Getter List<String> worlds;
	private @Getter boolean keepSpawnInMemory;
	private @Getter boolean autoEnabled;
	private @Getter boolean allWorlds;
	private @Getter int interval;
	private @Getter int minPlayersToUnload;
	private @Getter int minChunkActivity;
	private boolean gcTask;
	private boolean gcUnloading;

	// 新增配置字段：无玩家时的行为控制
	private @Getter boolean unloadAllWhenEmpty;
	private @Getter boolean pauseTaskWhenEmpty;

	// 区块状态记录
	private final ConcurrentHashMap<String, Map<Vector, Long>> worldChunkActivity = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Map<Vector, Boolean>> chunkInUse = new ConcurrentHashMap<>();

	@Override
	public void onEnable() {
		long start = System.currentTimeMillis();

		// 加载配置
		saveDefaultConfig();
		reloadConfig();
		loadConfig();

		// 注册监听器
		PluginManager pm = getServer().getPluginManager();
		WorldListener worldListener = new WorldListener(this);
		pm.registerEvents(worldListener, this);

		// 部署自动卸载任务
		if (autoEnabled) {
			long intervalTicks = interval * 60L * 20L; // 分钟 → 秒 → ticks
			new BukkitRunnable() {
				@Override
				public void run() {
					// 如果无玩家且配置要求暂停任务，则跳过此次执行
					if (pauseTaskWhenEmpty && !hasPlayersOnline()) {
						return;
					}
					scheduleChunkUnload();
					if (gcTask) {
						runGarbageCollector();
					}
				}
			}.runTaskTimerAsynchronously(this, 60L, intervalTicks);
		}

		log(Level.INFO, "插件已启用. 版本: {0}, 耗时: {1} 毫秒.", getDescription().getFullName(), System.currentTimeMillis() - start);
	}

	@Override
	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		log(Level.INFO, "插件已禁用.");
	}

	// ---- 新增方法：检查是否有玩家在线
	public boolean hasPlayersOnline() {
		return !Bukkit.getOnlinePlayers().isEmpty(); // 优化为 isEmpty()
	}

	// ---- 日志记录（使用 MessageFormat 修复占位符）
	public void log(Level level, String message, Object... args) {
		String formattedMessage = MessageFormat.format("[NoSpawnChunks] " + message, args);
		getLogger().log(level, formattedMessage);
	}

	// ---- 加载配置
	private void loadConfig() {
		worlds = new ArrayList<>();
		for (String world : getConfig().getStringList("worlds")) {
			worlds.add(world.toLowerCase());
		}

		autoEnabled = getConfig().getBoolean("task.enabled", true);
		allWorlds = worlds.isEmpty() || worlds.contains("*");
		interval = getConfig().getInt("task.interval", 15);
		keepSpawnInMemory = getConfig().getBoolean("keepSpawnInMemory", false);
		minPlayersToUnload = getConfig().getInt("minPlayersToUnload", 0);
		minChunkActivity = getConfig().getInt("minChunkActivity", 5);

		gcTask = getConfig().getBoolean("garbageCollector.task", false);
		gcUnloading = getConfig().getBoolean("garbageCollector.unloading", false);

		// 新增配置项：无玩家时的行为
		unloadAllWhenEmpty = getConfig().getBoolean("emptyServer.unloadAllChunks", true);
		pauseTaskWhenEmpty = getConfig().getBoolean("emptyServer.pauseAutoTask", true);
	}

	// 调度区块卸载到主线程
	public void scheduleChunkUnload() {
		Bukkit.getServer().getScheduler().runTask(this, this::unloadChunks);
	}

	// 卸载所有符合条件的区块
	public void unloadChunks() {
		long start = System.currentTimeMillis();
		log(Level.INFO, "开始执行区块卸载任务...");

		int unloadedChunks = 0;
		for (World world : getServer().getWorlds()) {
			if (allWorlds || worlds.contains(world.getName().toLowerCase())) {
				unloadedChunks += unloadChunksForWorld(world);
			}
		}

		log(Level.INFO, "区块卸载任务完成. 总计卸载区块数量: {0}, 耗时: {1} 毫秒.", unloadedChunks, System.currentTimeMillis() - start);
	}

	// 卸载指定世界的区块
	public int unloadChunksForWorld(World world) {
		long start = System.currentTimeMillis();
		log(Level.INFO, "开始卸载世界 {0} 的区块...", world.getName());

		int unloadedChunks = 0;
		boolean forceUnload = !hasPlayersOnline() && unloadAllWhenEmpty;

		for (Chunk chunk : world.getLoadedChunks()) {
			// 如果强制卸载或符合卸载条件
			if (forceUnload || shouldUnloadChunk(world, chunk)) {
				if (chunk.unload(true)) {
					unloadedChunks++;
				}
			}
		}

		log(Level.INFO, "世界 {0} 的区块卸载完成. 卸载区块数量: {1}, 耗时: {2} 毫秒.", world.getName(), unloadedChunks, System.currentTimeMillis() - start);
		return unloadedChunks;
	}

	// 判断区块是否应该被卸载
	private boolean shouldUnloadChunk(World world, Chunk chunk) {
		Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());
		Map<Vector, Boolean> chunkUseMap = chunkInUse.getOrDefault(world.getName(), new ConcurrentHashMap<>());
		if (chunkUseMap.getOrDefault(chunkVector, false)) {
			return false;
		}

		Map<Vector, Long> chunkActivityMap = worldChunkActivity.getOrDefault(world.getName(), new ConcurrentHashMap<>());
		long activity = chunkActivityMap.getOrDefault(chunkVector, 0L);
		return activity < minChunkActivity;
	}

	// 延迟卸载区块
	public void unloadLater(final World world, long delay) {
		Bukkit.getServer().getScheduler().runTaskLater(this, () -> {
			unloadChunksForWorld(world);
			if (gcUnloading) {
				runGarbageCollector();
			}
		}, delay);
	}

	// 更新区块活跃度
	public void updateChunkActivity(World world, Chunk chunk) {
		Map<Vector, Long> chunkActivityMap = worldChunkActivity.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>());
		Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());
		chunkActivityMap.compute(chunkVector, (k, v) -> (v == null ? 1 : v + 1));
	}

	// 更新区块使用状态
        public void updateChunkInUse(World world, Chunk chunk, boolean inUse) {
                Map<Vector, Boolean> chunkUseMap = chunkInUse.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>());
                Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());
                chunkUseMap.put(chunkVector, inUse);
        }

        /**
         * 移除给定区块的缓存数据，避免长时间存储导致的内存泄漏。
         *
         * @param world 所属世界
         * @param chunk 目标区块
         */
        public void removeChunkData(World world, Chunk chunk) {
                Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());

                Map<Vector, Boolean> chunkUseMap = chunkInUse.get(world.getName());
                if (chunkUseMap != null) {
                        chunkUseMap.remove(chunkVector);
                }

                Map<Vector, Long> chunkActivityMap = worldChunkActivity.get(world.getName());
                if (chunkActivityMap != null) {
                        chunkActivityMap.remove(chunkVector);
                }
        }

	// 执行垃圾回收
	public void runGarbageCollector() {
		long freeMemoryStart = Runtime.getRuntime().freeMemory();
		System.gc();
		long freeMemoryEnd = Runtime.getRuntime().freeMemory();
		long diff = (freeMemoryEnd - freeMemoryStart) / 1024 / 1024;
		if (diff > 0) {
			log(Level.INFO, "执行垃圾回收完成，已释放内存: {0} MB.", diff);
		} else {
			log(Level.INFO, "执行垃圾回收完成，未释放额外内存.");
		}
	}
}