// NoSpawnChunks.java
package cn.panda.nospawnchunks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import lombok.Getter;
import cn.panda.nospawnchunks.listeners.WorldListener;

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
	// 获取需要处理的世界列表
	private @Getter List<String> worlds;
	// 是否保持出生点区块在内存中
	private @Getter boolean keepSpawnInMemory;
	// 是否启用自动卸载任务
	private @Getter boolean autoEnabled;
	// 是否所有世界都启用卸载
	private @Getter boolean allWorlds;
	// 自动卸载任务的间隔（单位：分钟）
	private @Getter int interval;
	// 触发卸载的最小玩家数量
	private @Getter int minPlayersToUnload;
	// 区块最小活跃度
	private @Getter int minChunkActivity;

	// 是否启用垃圾回收任务
	private boolean gcTask;
	// 是否在卸载区块时执行垃圾回收
	private boolean gcUnloading;
	// 记录区块的活跃度, 使用 ConcurrentHashMap 保证线程安全
	private final ConcurrentHashMap<String, Map<Vector, Long>> worldChunkActivity = new ConcurrentHashMap<>();
	// 记录区块是否被使用, 使用 ConcurrentHashMap 保证线程安全
	private final ConcurrentHashMap<String, Map<Vector, Boolean>> chunkInUse = new ConcurrentHashMap<>();

	// private WorldListener worldListener; // 世界监听器实例 - 已转换为局部变量

	@Override
	public void onEnable() {
		long start = System.currentTimeMillis();

		// 加载配置
		saveDefaultConfig();
		reloadConfig();
		loadConfig();

		// 注册监听器
		PluginManager pm = getServer().getPluginManager();
		// 世界监听器现在作为局部变量使用
		WorldListener worldListener = new WorldListener(this);
		pm.registerEvents(worldListener, this);

		// 部署卸载任务
		if (autoEnabled) {
			new BukkitRunnable() {
				@Override
				public void run() {
					unloadChunks(); // 执行区块卸载

					if (gcTask) {
						runGarbageCollector(); // 执行垃圾回收
					}
				}
			}.runTaskTimerAsynchronously(this, 60L, interval * 20L); // 启动定时任务, interval 转为 tick
		}

		// 插件启用完成日志
		log(Level.INFO, "插件已启用. 版本: {0}, 耗时: {1} 毫秒.", getDescription().getFullName(), System.currentTimeMillis() - start);
	}

	@Override
	public void onDisable() {
		getServer().getScheduler().cancelTasks(this); // 取消所有插件任务
		log(Level.INFO, "插件已禁用.");
	}

	// ---- 日志记录
	public void log(Level level, String message, Object... args) {
		String formattedMessage = "[NoSpawnChunks] " + String.format(message, args);
		getLogger().log(level, formattedMessage);
	}

	// ---- 加载和重载配置
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
	}

	// 卸载所有符合条件的世界的区块
	public void unloadChunks() {
		long start = System.currentTimeMillis();
		log(Level.INFO, "开始执行区块卸载任务...");

		int unloadedChunks = 0;

		for (World world : getServer().getWorlds()) {
			if (allWorlds || worlds.contains(world.getName().toLowerCase())) {
				unloadedChunks += unloadChunks(world);
			}
		}

		log(Level.INFO, "区块卸载任务完成. 总计卸载区块数量: {0}, 耗时: {1} 毫秒.", unloadedChunks, System.currentTimeMillis() - start);
	}

	// 卸载指定世界的区块
	public int unloadChunks(World world) {
		long start = System.currentTimeMillis();
		log(Level.INFO, "开始卸载世界 {0} 的区块...", world.getName());

		int unloadedChunks = 0;
		// 只有当世界内的玩家数量小于等于配置的最小卸载玩家数量时才执行卸载
		if (world.getPlayers().size() <= minPlayersToUnload) {
			// 使用 world.getLoadedChunks() 获取所有已加载的区块
			for (Chunk chunk : world.getLoadedChunks()) {
				if (shouldUnloadChunk(world, chunk)) {
					// 执行区块卸载，并记录卸载成功的区块数量
					if (chunk.unload(true)) {
						unloadedChunks++;
					}
				}
			}
		}

		log(Level.INFO, "世界 {0} 的区块卸载完成. 卸载区块数量: {1}, 耗时: {2} 毫秒.", world.getName(), unloadedChunks, System.currentTimeMillis() - start);

		return unloadedChunks;
	}

	// 判断区块是否应该被卸载
	private boolean shouldUnloadChunk(World world, Chunk chunk) {
		Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());
		// 使用缓存的区块是否使用信息
		Map<Vector, Boolean> chunkUseMap = chunkInUse.getOrDefault(world.getName(), new ConcurrentHashMap<>());
		// 如果区块正在被使用，则不卸载
		if (chunkUseMap.getOrDefault(chunkVector, false)) {
			return false;
		}

		//  判断区块活跃度
		Map<Vector, Long> chunkActivityMap = worldChunkActivity.getOrDefault(world.getName(), new ConcurrentHashMap<>());
		long activity = chunkActivityMap.getOrDefault(chunkVector, 0L);

		// 只有当区块的活跃度小于配置的最小活跃度时才卸载
		return activity < minChunkActivity;
	}

	/**
	 * 延迟卸载指定世界的区块。
	 * @param world 需要卸载区块的世界
	 * @param delay 延迟时间（单位：tick，20 tick = 1秒）
	 * @apiNote 请注意，频繁地延迟卸载可能会影响服务器性能，建议合理设置延迟时间。
	 */
	public void unloadLater(final World world, long delay) {
		getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
			unloadChunks(world);
			// 如果配置为在卸载区块后执行垃圾回收
			if (gcUnloading) {
				runGarbageCollector();
			}
		}, delay);
	}

	// 更新区块活跃度信息
	public void updateChunkActivity(World world, Chunk chunk) {
		// 获取或创建世界对应的区块活跃度 Map
		Map<Vector, Long> chunkActivityMap = worldChunkActivity.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>());
		Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());
		// 增加区块的活跃度计数
		chunkActivityMap.compute(chunkVector, (k, v) -> (v == null ? 1 : v + 1));
	}

	// 更新区块是否在使用信息
	public void updateChunkInUse(World world, Chunk chunk, boolean inUse) {
		// 获取或创建世界对应的区块使用状态 Map
		Map<Vector, Boolean> chunkUseMap = chunkInUse.computeIfAbsent(world.getName(), k -> new ConcurrentHashMap<>());
		Vector chunkVector = new Vector(chunk.getX(), 0, chunk.getZ());
		// 设置区块的使用状态
		chunkUseMap.put(chunkVector, inUse);
	}

	// ---- 垃圾回收
	public void runGarbageCollector() {
		long freeMemoryStart = Runtime.getRuntime().freeMemory();
		System.gc(); // 执行垃圾回收
		long freeMemoryEnd = Runtime.getRuntime().freeMemory();
		long diff = (freeMemoryEnd - freeMemoryStart) / 1024 / 1024;
		if (diff > 0)
			log(Level.INFO, "执行垃圾回收完成，已释放内存: {0} MB.", diff);
		else
			log(Level.INFO, "执行垃圾回收完成，未释放额外内存.");
	}
}