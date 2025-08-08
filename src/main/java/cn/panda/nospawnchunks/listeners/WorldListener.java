// WorldListener.java
package cn.panda.nospawnchunks.listeners;

import lombok.AllArgsConstructor;
import cn.panda.nospawnchunks.NoSpawnChunks;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldInitEvent;

/**
 * 世界监听器，负责监听世界相关的事件，并根据配置执行相应的操作。
 */
@AllArgsConstructor
public class WorldListener implements Listener {
	private final NoSpawnChunks plugin;

	// 世界初始化事件处理
	@EventHandler(priority = EventPriority.MONITOR)
	public void onWorldInit(WorldInitEvent event) {
		World world = event.getWorld();
		// 如果配置为所有世界都启用，或者当前世界在配置的世界列表中
		if (plugin.isAllWorlds() || plugin.getWorlds().contains(world.getName().toLowerCase())) {
			// 设置是否保持出生点区块在内存中
			world.setKeepSpawnInMemory(plugin.isKeepSpawnInMemory());
		}
	}

	// 玩家切换世界事件处理
	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
		World world = event.getFrom();
		// 如果配置为所有世界都启用，或者离开的世界在配置的世界列表中，并且世界内的玩家数量小于等于配置的最小卸载玩家数量
		if ((plugin.isAllWorlds() || plugin.getWorlds().contains(world.getName().toLowerCase())) && world.getPlayers().size() <= plugin.getMinPlayersToUnload()) {
			// 延迟卸载区块（注意：此处延迟时间可能需要根据服务器情况进行调整）
			plugin.unloadLater(world, 20L);
		}
	}

	// 玩家退出游戏事件处理
	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerQuit(PlayerQuitEvent event) {
		World world = event.getPlayer().getWorld();
		// 如果配置为所有世界都启用，或者玩家所在的世界在配置的世界列表中，并且世界内的玩家数量小于等于配置的最小卸载玩家数量
		if ((plugin.isAllWorlds() || plugin.getWorlds().contains(world.getName().toLowerCase())) && world.getPlayers().size() <= plugin.getMinPlayersToUnload()) {
			// 延迟卸载区块（注意：此处延迟时间可能需要根据服务器情况进行调整）
			plugin.unloadLater(world, 20L);
		}
	}

	// 区块加载事件监听
	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkLoad(ChunkLoadEvent event) {
		Chunk chunk = event.getChunk();
		World world = chunk.getWorld();
		// 如果配置为所有世界都启用，或者当前世界在配置的世界列表中
		if (plugin.isAllWorlds() || plugin.getWorlds().contains(world.getName().toLowerCase())) {
			// 更新区块是否被使用的状态
			plugin.updateChunkInUse(world, chunk, true);
			// 更新区块的活跃度
			plugin.updateChunkActivity(world, chunk);
		}
	}

	// 区块卸载事件监听
	@EventHandler(priority = EventPriority.MONITOR)
	public void onChunkUnload(ChunkUnloadEvent event) {
		Chunk chunk = event.getChunk();
		World world = chunk.getWorld();
		// 如果配置为所有世界都启用，或者当前世界在配置的世界列表中
                if (plugin.isAllWorlds() || plugin.getWorlds().contains(world.getName().toLowerCase())) {
                        // 更新区块是否被使用的状态，并移除相关缓存
                        plugin.updateChunkInUse(world, chunk, false);
                        plugin.removeChunkData(world, chunk);
                }
        }
}