package hu.montlikadani.tablist.tablist.groups;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.HashSet;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import hu.montlikadani.tablist.utils.scheduler.TLScheduler;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import hu.montlikadani.tablist.TabList;
import hu.montlikadani.tablist.config.constantsLoader.ConfigValues;
import hu.montlikadani.tablist.tablist.TabText;
import hu.montlikadani.tablist.user.TabListUser;
import hu.montlikadani.tablist.utils.PluginUtils;

public final class Groups {

	private final TabList plugin;

	private TLScheduler animationTask;

	private final List<TeamHandler> teams = new ArrayList<>();
	private final List<TeamHandler> orderedGroupsByWeight = new ArrayList<>();
	private final Deque<GroupPlayer> sortedPlayers = new ConcurrentLinkedDeque<>();
	private final Set<GroupPlayer> afkPlayersCache = new HashSet<>();

	private boolean toSort = true;
	private TeamHandler defaultAssignedGroup;

	public Groups(TabList plugin) {
		this.plugin = plugin;
	}

	/**
	 * Returns a copy list of teams
	 * 
	 * @return {@link List}
	 */
	public List<TeamHandler> getTeams() {
		return new ArrayList<>(teams);
	}

	public List<TeamHandler> orderedGroupsByWeight() {
		return orderedGroupsByWeight;
	}

	/**
	 * @return the default assigned group which every online player have except
	 * who already have a group specified
	 */
	public TeamHandler getDefaultAssignedGroup() {
		return defaultAssignedGroup;
	}

	/**
	 * Returns the team by name
	 * 
	 * @param name Team name
	 * @return {@link TeamHandler} if present, otherwise {@link Optional#empty()}
	 * @throws NullPointerException     if the specified name is null
	 * @throws IllegalArgumentException if the specified name is empty
	 */
	public Optional<TeamHandler> getTeam(String name) {
		if (name.isEmpty()) {
			throw new IllegalArgumentException("The team name can not be empty");
		}

		for (TeamHandler handler : teams) {
			if (handler.team.equalsIgnoreCase(name)) {
				return Optional.of(handler);
			}
		}

		return Optional.empty();
	}

	public boolean isToSort() {
		return toSort;
	}

	public void setToSort(boolean toSort) {
		this.toSort = toSort;
	}

	public void load() {
		teams.clear();
		orderedGroupsByWeight.clear();

		if (!ConfigValues.isPrefixSuffixEnabled()) {
			return;
		}

		org.bukkit.configuration.file.FileConfiguration gr = plugin.getConf().getGroups();

		String globPrefix = gr.getString("globalGroup.prefix", "");
		String globSuffix = gr.getString("globalGroup.suffix", "");
		String globTabName = gr.getString("globalGroup.tabname", "");

		if (!globTabName.isEmpty() || !globPrefix.isEmpty() || !globSuffix.isEmpty()) {
			TeamHandler team = new TeamHandler("global", TabText.parseFromText(plugin.getPlaceholders().replaceMiscVariables(globPrefix)),
					TabText.parseFromText(plugin.getPlaceholders().replaceMiscVariables(globSuffix)));

			team.global = true;
			team.tabName = TabText.parseFromText(plugin.getPlaceholders().replaceMiscVariables(globTabName));

			teams.add(team);
		}

		ConfigurationSection section = gr.getConfigurationSection("groups");
		if (section == null) {
			if (!teams.isEmpty()) {
				startTask();
			}

			return;
		}

		Set<String> keys = section.getKeys(false);

		if (keys.isEmpty()) {
			if (!teams.isEmpty()) {
				startTask();
			}

			return;
		}

		// Automatically add existing groups to the list for "lazy peoples"
		if (ConfigValues.isSyncPluginsGroups() && plugin.hasPermissionService()) {
			boolean saveRequired = false;

			me: for (String one : plugin.getPermissionService().getGroups()) {

				// If group already exists, skip it
				for (String name : keys) {
					if (one.equalsIgnoreCase(name)) {
						continue me;
					}
				}

				// Set luckperms prefix if it has any
				Object group = plugin.getPermissionService().groupObjectByName(one);

				if (group != null) {
					String prefix = ((net.luckperms.api.model.group.Group) group).getCachedData().getMetaData().getPrefix();

					section.set(one + ".prefix", prefix != null ? prefix : "[" + one + "] - ");
				} else {
					section.set(one + ".prefix", "[" + one + "] - ");
				}

				saveRequired = true;
			}

			if (saveRequired) {
				try {
					gr.save(plugin.getConf().getGroupsFile());
				} catch (java.io.IOException e) {
					e.printStackTrace();
				}
			}
		}

		int last = 0;

		for (String name : keys) {
			if ("exampleGroup".equals(name) || "PlayerName".equals(name)) {
				continue;
			}

			TabText prefix = TabText.parseFromText(plugin.getPlaceholders().replaceMiscVariables(section.getString(name + ".prefix", "")));
			TabText suffix = TabText.parseFromText(plugin.getPlaceholders().replaceMiscVariables(section.getString(name + ".suffix", "")));

			TeamHandler th = new TeamHandler(name, prefix, suffix, section.getString(name + ".permission", ""),
					last = section.getInt(name + ".sort-priority", last + 1));

			th.setAfkSortPriority(section.getInt(name + ".afk-sort-priority", -1));
			th.tabName = TabText.parseFromText(plugin.getPlaceholders().replaceMiscVariables(section.getString(name + ".tabname", "")));

			teams.add(th);
		}

		teams.add(defaultAssignedGroup = new TeamHandler("defaultLast", TabText.EMPTY, TabText.EMPTY, "", 0));

		// Sort groups by priority to match the lowest priority firstly (highest
		// priority is on the top of other)
		teams.sort(Comparator.<TeamHandler>comparingInt(t -> t.priority).reversed());

		orderGroupsByWeight();
		startTask();
	}

	private void orderGroupsByWeight() {
		orderedGroupsByWeight.clear();

		if (!ConfigValues.isUseLPWeightToOrderGroupsFirst() || ConfigValues.isPreferPrimaryVaultGroup()
				|| !plugin.hasPermissionService() || !plugin.getPermissionService().hasLuckPerms) {
			return;
		}

		orderedGroupsByWeight.addAll(teams);

		orderedGroupsByWeight.sort((team, team2) -> {
			if (team.global || team2.global) {
				return 0;
			}

			net.luckperms.api.model.group.Group group1 = (net.luckperms.api.model.group.Group) plugin.getPermissionService().groupObjectByName(team.team);

			if (group1 == null) {
				return 0;
			}

			net.luckperms.api.model.group.Group group2 = (net.luckperms.api.model.group.Group) plugin.getPermissionService().groupObjectByName(team2.team);

			if (group2 == null) {
				return 0;
			}

			java.util.OptionalInt optionalInt1 = group1.getWeight();

			if (!optionalInt1.isPresent()) {
				return 0;
			}

			java.util.OptionalInt optionalInt2 = group2.getWeight();

			return optionalInt2.isPresent() ? optionalInt2.getAsInt() - optionalInt1.getAsInt() : 0;
		});
	}

	/**
	 * Sets the player prefix, suffix, tab name, and position on tablist
	 * 
	 * @param groupPlayer  {@link GroupPlayer}
	 * @param safePriority Safe priority value. The value should be between 0 and 98.
	 */
	public void setPlayerTeam(GroupPlayer groupPlayer, int safePriority) {
		groupPlayer.setSafePriority(safePriority);
		groupPlayer.getTabTeam().createAndUpdateTeam();
	}

	/**
	 * Updates the specified player's group and sorts in the player list
	 * 
	 * @param player {@link Player}
	 * @return {@link GroupPlayer} or null if player is not exist
	 */
	public GroupPlayer addPlayer(Player player) {
		TabListUser user = plugin.getUser(player).orElse(null);
		if (user == null) {
			return null;
		}

		GroupPlayer groupPlayer = user.getGroupPlayer();

		groupPlayer.update();
		setToSort(true);
		sortedPlayers.add(groupPlayer);
		sortPlayers();

		return groupPlayer;
	}

	/**
	 * Removes the given player's group.
	 * 
	 * @param user {@link TabListUser}
	 */
	public void removePlayerGroup(TabListUser user) {
		GroupPlayer groupPlayer = user.getGroupPlayer();

		sortedPlayers.remove(groupPlayer);
		afkPlayersCache.remove(groupPlayer);

		groupPlayer.getTabTeam().unregisterTeam();
		groupPlayer.removeGroup();
	}

	public void removeTeam(String teamName) {
		getTeam(teamName).ifPresent(team -> {
			teams.remove(team);
			orderGroupsByWeight();
		});
	}

	public void addTeam(TeamHandler team) {
		teams.add(team);
		orderGroupsByWeight();
	}

	/**
	 * Cancels the current running task of groups and removes from players.
	 */
	public void cancelUpdate() {
		cancelTask();

		sortedPlayers.clear();
		afkPlayersCache.clear();

		for (TabListUser user : plugin.getUsers()) {
			GroupPlayer groupPlayer = user.getGroupPlayer();

			groupPlayer.getTabTeam().unregisterTeam();
			groupPlayer.removeGroup();
		}
	}

	public void cancelTask() {
		if (animationTask != null) {
			animationTask.cancelTask();
			animationTask = null;
		}
	}

	public void startTask() {
		if (!ConfigValues.isPrefixSuffixEnabled()) {
			return;
		}

		final int refreshInt = ConfigValues.getGroupsRefreshInterval();
		if (refreshInt < 1) {
			updatePlayers();
			return;
		}

		if (animationTask == null) {
			animationTask = plugin.newTLScheduler().submitAsync(() -> {
				if (plugin.performanceIsUnderValue() || plugin.getUsers().isEmpty()) {
					cancelTask();
					return;
				}

				// Skip method execution to sort players until the current thread is locked
				if (!lock.isLocked()) {
					updatePlayers();
				}
			}, 0, refreshInt);
		}
	}

	private void updatePlayers() {
		for (TabListUser user : plugin.getUsers()) {
			GroupPlayer gp = user.getGroupPlayer();

			if (gp.update()) {
				sortedPlayers.remove(gp);
				sortedPlayers.add(gp);
			}
		}

		sortPlayers();
	}

	private final ReentrantLock lock = new ReentrantLock();

	/**
	 * This method is used to sort and update players' groups. Includes sorting of AFK players.
	 */
	private void sortPlayers() {
		// Pauses the current thread until the stream unlocks.
		//
		// This was implemented to fix a less reproducible and undetectable exception.
		// This lock was needed for the stream collect, so it pauses the current thread
		// until the stream ends and unlocks the thread.
		// Without this lock some data will be lost.
		lock.lock();

		List<GroupPlayer> playerGroups;
		try {
			playerGroups = sortedPlayers.stream().sorted(Comparator.comparingInt(GroupPlayer::getPriority))
					.collect(Collectors.toList());
		} finally {
			lock.unlock();
		}

		sortedPlayers.clear();
		sortedPlayers.addAll(playerGroups);

		int priority = playerGroups.size();

		for (GroupPlayer groupPlayer : sortedPlayers) {
			if (ConfigValues.isAfkStatusEnabled() && (ConfigValues.isAfkSortLast()
					|| (groupPlayer.getGroup() != null && groupPlayer.getGroup().getAfkSortPriority() != -1))) {
				if (PluginUtils.isAfk(groupPlayer.getUser().getPlayer())) {
					if (afkPlayersCache.add(groupPlayer) && !toSort) {
						setToSort(true);
					}

					continue;
				}

				if (afkPlayersCache.remove(groupPlayer) && !toSort) {
					setToSort(true);
				}
			}

			setPlayerTeam(groupPlayer, priority--);
		}

		if (!afkPlayersCache.isEmpty()) {
			int size = playerGroups.size();

			for (GroupPlayer afk : afkPlayersCache) {
				setPlayerTeam(afk, (afk.getGroup() == null || afk.getGroup().getAfkSortPriority() == -1) ? size++
						: afk.getGroup().getAfkSortPriority());
			}
		}

		if (toSort) {
			setToSort(false);
		}
	}
}
