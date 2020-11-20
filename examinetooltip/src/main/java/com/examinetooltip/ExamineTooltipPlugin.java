/*
 * Copyright (c) 2020, Cyborger1
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.examinetooltip;

import com.google.common.collect.EvictingQueue;
import com.google.inject.Provides;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.util.Text;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Examine Tooltip",
	description = "Shows tooltips or RS3 style hover boxes on examine",
	tags = {"examine", "tooltip", "text", "hover", "rs3"},
	enabledByDefault = false,
	type = PluginType.MISCELLANEOUS
)
public class ExamineTooltipPlugin extends Plugin
{
	private static Pattern PATCH_INSPECT_PATTERN = Pattern.compile("^This is an? .+\\. The (?:soil|patch) has");

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ExamineTooltipOverlay examineTooltipOverlay;

	@Inject
	private ExamineTooltipConfig config;

	@Getter
	private final EvictingQueue<ExamineTextTime> examines = EvictingQueue.create(5);

	private final Queue<ExamineTextTime> pendingExamines = new ArrayDeque<>();

	private ExamineTextTime pendingPatchInspect;

	@Provides
	ExamineTooltipConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ExamineTooltipConfig.class);
	}

	private void resetPlugin()
	{
		examines.clear();
		pendingExamines.clear();
		pendingPatchInspect = null;
	}

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(examineTooltipOverlay);
		resetPlugin();
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(examineTooltipOverlay);
		resetPlugin();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		resetPlugin();
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		String option = Text.removeTags(event.getOption());

		ExamineType type;
		if (option.equals("Examine"))
		{
			switch (event.getMenuOpcode())
			{
				case EXAMINE_ITEM:
					if (!config.showItemExamines())
					{
						return;
					}
					type = ExamineType.ITEM;
					break;
				case EXAMINE_ITEM_GROUND:
					if (!config.showGroundItemExamines())
					{
						return;
					}
					type = ExamineType.ITEM_GROUND;
					break;
				case CC_OP_LOW_PRIORITY:
					if (!config.showItemExamines())
					{
						return;
					}
					type = ExamineType.ITEM_INTERFACE;
					break;
				case EXAMINE_OBJECT:
					if (!config.showObjectExamines())
					{
						return;
					}
					type = ExamineType.OBJECT;
					break;
				case EXAMINE_NPC:
					if (!config.showNPCExamines())
					{
						return;
					}
					type = ExamineType.NPC;
					break;
				default:
					return;
			}
		}
		else if (option.equals("Inspect"))
		{
			switch (event.getMenuOpcode())
			{
				case GAME_OBJECT_FIRST_OPTION:
				case GAME_OBJECT_SECOND_OPTION:
				case GAME_OBJECT_THIRD_OPTION:
				case GAME_OBJECT_FOURTH_OPTION:
				case GAME_OBJECT_FIFTH_OPTION:
					type = ExamineType.PATCH_INSPECT;
					break;
				default:
					return;
			}
		}
		else
		{
			return;
		}

		int id = event.getIdentifier();
		int actionParam = event.getParam1();
		int wId = event.getParam0();

		ExamineTextTime examine = new ExamineTextTime();
		examine.setType(type);
		examine.setId(id);
		examine.setWidgetId(wId);
		examine.setActionParam(actionParam);

		if (type == ExamineType.PATCH_INSPECT)
		{
			// Patch inspects require the player to move up to the patch.
			// They have to be treated separately and cannot be queued.
			pendingPatchInspect = examine;
		}
		else
		{
			pendingExamines.offer(examine);
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ExamineType type;
		switch (event.getType())
		{
			case ITEM_EXAMINE:
				if (Text.removeTags(event.getMessage()).startsWith("Price of"))
				{
					type = ExamineType.PRICE_CHECK;
				}
				else
				{
					type = ExamineType.ITEM;
				}
				break;
			case OBJECT_EXAMINE:
				type = ExamineType.OBJECT;
				break;
			case NPC_EXAMINE:
				type = ExamineType.NPC;
				break;
			case GAMEMESSAGE:
				if (PATCH_INSPECT_PATTERN.matcher(event.getMessage()).lookingAt())
				{
					type = ExamineType.PATCH_INSPECT;
				}
				else
				{
					type = ExamineType.ITEM_INTERFACE;
				}
				break;
			default:
				return;
		}

		Instant now = Instant.now();
		String text = Text.removeTags(event.getMessage());

		if (type == ExamineType.PRICE_CHECK)
		{
			if (config.showPriceCheck())
			{
				ExamineTextTime examine = new ExamineTextTime();
				examine.setType(type);
				examine.setText(text);
				examine.setTime(now);
				examines.add(examine);
			}
			return;
		}

		ExamineTextTime pending;

		if (type == ExamineType.PATCH_INSPECT)
		{
			if (pendingPatchInspect != null && config.showPatchInspects())
			{
				pending = pendingPatchInspect;
				pendingPatchInspect = null;
			}
			else
			{
				return;
			}
		}
		else if (!pendingExamines.isEmpty())
		{
			pending = pendingExamines.poll();
		}
		else
		{
			return;
		}

		if (pending.getType() == type || (type == ExamineType.ITEM && pending.getType() == ExamineType.ITEM_GROUND))
		{
			pending.setTime(now);
			pending.setText(text);
			examines.removeIf(x -> x.getText().equals(text));
			examines.add(pending);
		}
		else
		{
			pendingExamines.clear();
		}
	}
}
