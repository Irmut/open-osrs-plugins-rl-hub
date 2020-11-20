package dekvall.inventoryscrabble;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.MenuOpcode;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.util.Text;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Inventory Scrabble",
	description = "Only allow interactions with entities which name you can spell with the items in your inventory",
	enabledByDefault = false,
	type = PluginType.MISCELLANEOUS
)
public class InventoryScrabblePlugin extends Plugin
{
	static final String CONFIG_GROUP = "inventoryscrabble";
	private static final Set<Integer> TUTORIAL_ISLAND_REGIONS = ImmutableSet.of(12336, 12335, 12592, 12080, 12079, 12436);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private InventoryScrabbleConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	private boolean onTutorialIsland;
	private Multiset<Character> counts;

	@Override
	protected void startUp() throws Exception
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(() -> {
				gatherItemNames();
				checkArea();
			});
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		counts.clear();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (client.getGameState() == GameState.LOGGED_IN && event.getGroup().equals(CONFIG_GROUP))
		{
			clientThread.invokeLater(this::gatherItemNames);
		}
	}



	private void gatherItemNames()
	{
		final ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		final ItemContainer equipment = client.getItemContainer(InventoryID.EQUIPMENT);
		counts = HashMultiset.create();

		addCharsFrom(inventory);

		if (config.wornItems())
		{
			addCharsFrom(equipment);
		}
	}

	private void addCharsFrom(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}

		Arrays.stream(container.getItems())
			.map(item -> itemManager.getItemDefinition(item.getId())
				.getName()
				.toLowerCase())
			.filter(name -> !name.equals("null"))
			.map(name -> Text.removeTags(name)
				.replaceAll("[^a-z]", "")
				.charAt(0))
			.forEach(c -> counts.add(c));
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			gatherItemNames();
			checkArea();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() == client.getItemContainer(InventoryID.INVENTORY)
			|| event.getItemContainer() == client.getItemContainer(InventoryID.EQUIPMENT))
		{
			gatherItemNames();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (onTutorialIsland)
		{
			return;
		}

		MenuEntry[] menuEntries = client.getMenuEntries();
		List<MenuEntry> cleaned = new ArrayList<>();

		Set<String> checked = new HashSet<>();
		Set<String> okTargets = new HashSet<>();

		for (MenuEntry entry : menuEntries)
		{
			int type = entry.getOpcode();

			if (isNpcEntry(type) || config.hardMode() && isObjectEntry(type))
			{
				String target = entry.getTarget();

				if (!checked.contains(target))
				{
					Multiset<Character> targetChars = cleanTarget(target);
					if (targetChars.entrySet().stream()
						.noneMatch(e -> e.getCount() > counts.count(e.getElement())))
					{
						okTargets.add(target);
					}
					checked.add(target);
				}

				if (!okTargets.contains(target))
				{
					continue;
				}
			}
			cleaned.add(entry);
		}

		MenuEntry[] newEntries = cleaned.toArray(new MenuEntry[0]);
		client.setMenuEntries(newEntries);
	}

	Multiset<Character> cleanTarget(String target)
	{
		String name = onlyName(target);
		Multiset<Character> targetCount = HashMultiset.create();
		char[] chars = name.toLowerCase().replaceAll("[^a-z]", "").toCharArray();
		for (char c : chars)
		{
			targetCount.add(c);
		}

		return targetCount;
	}

	private String onlyName(String target)
	{
		String noTags = Text.removeTags(target);

		// Do not include level in the comparison
		int idx = noTags.indexOf('(');

		String name = noTags;
		if (idx != -1)
		{
			name = noTags.substring(0, idx);
		}

		return name;
	}

	boolean isNpcEntry(int type)
	{
		if (type >= 2000)
		{
			type -= 2000;
		}

		MenuOpcode action = MenuOpcode.of(type);

		switch (action)
		{
			case SPELL_CAST_ON_NPC:
			case ITEM_USE_ON_NPC:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	boolean isObjectEntry(int type)
	{
		MenuOpcode action = MenuOpcode.of(type);

		switch (action)
		{
			case SPELL_CAST_ON_GAME_OBJECT:
			case ITEM_USE_ON_GAME_OBJECT:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
				return true;
			default:
				return false;
		}
	}

	private void checkArea()
	{
		final Player player = client.getLocalPlayer();
		if (player != null && TUTORIAL_ISLAND_REGIONS.contains(player.getWorldLocation().getRegionID()))
		{
			onTutorialIsland = true;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuOpcode() != MenuOpcode.EXAMINE_NPC
			&& (!config.hardMode() || event.getMenuOpcode() != MenuOpcode.EXAMINE_OBJECT))
		{
			return;
		}

		Multiset<Character> targetChars = cleanTarget(event.getTarget());
		Multiset<Character> diff = Multisets.difference(targetChars, counts);

		if (diff.isEmpty())
		{
			return;
		}

		String name = onlyName(event.getTarget());

		final StringBuilder sb = new StringBuilder();

		diff.entrySet().stream().forEach(e -> {
			Character c = Character.toUpperCase(e.getElement());
			sb.append(" ").append(c);
			if (e.getCount() > 1)
			{
				sb.append("x").append(e.getCount());
			}
		});

		sendChatMessage(sb.toString(), name.trim());
	}

	private void sendChatMessage(String missingChars, String name)
	{
		final String message = new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("[Scrabble]").append(" Missing")
			.append(ChatColorType.HIGHLIGHT)
			.append(missingChars)
			.append(ChatColorType.NORMAL)
			.append(" to allow interactions with ")
			.append(name)
			.append(".")
			.build();

		chatMessageManager.queue(
			QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(message)
				.build());
	}

	@Provides
	InventoryScrabbleConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(InventoryScrabbleConfig.class);
	}
}
