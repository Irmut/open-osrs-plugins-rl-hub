package dekvall.lowercaseusernames;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.MenuOpcode;
import static net.runelite.api.MenuOpcode.MENU_ACTION_DEPRIORITIZE_OFFSET;
import static net.runelite.api.MenuOpcode.WALK;
import net.runelite.api.Player;
import net.runelite.api.events.ClientTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Lowercase Usernames",
	description = "Lowercases right-click menu entries to prevent lookalike usernames",
	enabledByDefault = false,
	type = PluginType.MISCELLANEOUS
)
public class LowercaseUsernamesPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private LowercaseUsernamesConfig config;

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (client.isMenuOpen())
		{
			return;
		}

		MenuEntry[] menuEntries = client.getMenuEntries();
		boolean modified = false;


		for (MenuEntry entry : menuEntries)
		{
			int type = entry.getOpcode();

			if (type >= MENU_ACTION_DEPRIORITIZE_OFFSET)
			{
				type -= MENU_ACTION_DEPRIORITIZE_OFFSET;
			}

			if (!isPlayerEntry(type))
			{
				continue;
			}

			int identifier = entry.getIdentifier();

			Player[] players = client.getCachedPlayers();
			Player player = null;

			// 'Walk here' identifiers are offset by 1 because the default
			// identifier for this option is 0, which is also a player index.
			if (type == WALK.getId())
			{
				identifier--;
			}

			if (identifier >= 0 && identifier < players.length)
			{
				player = players[identifier];
			}

			if (player == null)
			{
				continue;
			}

			String target = entry.getTarget();
			String cased = config.uppercase() ? target.toUpperCase() : target.toLowerCase();
			// A target has tags that we want to preserve

			StringBuilder sb = new StringBuilder();
			boolean insideTag = false;

			for (int i = 0; i < target.length(); i++)
			{
				char part = target.charAt(i);
				// All tags have to start with < and there are no nested tags so this should work
				if (part == '<' || part == '>')
				{
					insideTag = !insideTag;
				}

				if (insideTag)
				{
					sb.append(part);
				}
				else
				{
					sb.append(cased.charAt(i));
				}
			}

			String newTarget = sb.toString();
			entry.setTarget(newTarget);
			modified = true;
		}

		if (modified)
		{
			client.setMenuEntries(menuEntries);
		}
	}

	boolean isPlayerEntry(int type)
	{
		MenuOpcode action = MenuOpcode.of(type);

		switch (action)
		{
			case WALK:
			case SPELL_CAST_ON_PLAYER:
			case ITEM_USE_ON_PLAYER:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGTH_OPTION:
			case RUNELITE:
				return true;
			default:
				return false;
		}
	}

	@Provides
	LowercaseUsernamesConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LowercaseUsernamesConfig.class);
	}
}
